package com.spartronics4915.frc2019.lidar;

import com.spartronics4915.frc2019.Constants;
import com.spartronics4915.lib.util.Logger;

import edu.wpi.first.wpilibj.Timer;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Starts the <code>chezy_lidar</code> C++ program, parses its
 * output, and feeds the LIDAR points to the {@link LidarProcessor}.
 * <p>
 * Once started, a separate thread reads the stdout of the
 * <code>chezy_lidar</code> process and parses the (angle, distance)
 * values in each line. Each resulting {@link LidarPoint} is passed
 * to {@link LidarProcessor.addPoint(...)}.
 */
public class LidarServer
{

    private static LidarServer mInstance = null;
    private final LidarProcessor mLidarProcessor = LidarProcessor.getInstance();
    private static BufferedReader mBufferedReader;
    private boolean mRunning = false;
    private Thread mThread;
    private Process mProcess;
    private boolean mEnding = false;

    public static LidarServer getInstance()
    {
        if (mInstance == null)
        {
            mInstance = new LidarServer();
        }
        return mInstance;
    }

    private LidarServer()
    {
    }

    public boolean isLidarConnected()
    {
        try
        {
            Runtime r = Runtime.getRuntime();
            Process p = r.exec("/bin/ls /dev/serial/by-id/");
            InputStreamReader reader = new InputStreamReader(p.getInputStream());
            BufferedReader response = new BufferedReader(reader);
            String s;
            while ((s = response.readLine()) != null)
            {
                if (s.equals("usb-Silicon_Labs_CP2102_USB_to_UART_Bridge_Controller_0001-if00-port0"))
                    return true;
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        return false;
    }

    public boolean start()
    {
        if (!isLidarConnected())
        {
            Logger.error("Cannot start LidarServer: not connected");
            return false;
        }
        synchronized (this)
        {
            if (mRunning)
            {
                Logger.error("Cannot start LidarServer: already running");
                return false;
            }
            if (mEnding)
            {
                Logger.error("Cannot start LidarServer: thread ending");
                return false;
            }
            mRunning = true;
        }

        Logger.info("Starting lidar");
        try
        {
            mProcess = new ProcessBuilder().command(Constants.kLidarPath).start();
            mThread = new Thread(new ReaderThread());
            InputStreamReader reader = new InputStreamReader(mProcess.getInputStream());
            mBufferedReader = new BufferedReader(reader);
            mThread.start();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return true;
    }

    public boolean stop()
    {
        synchronized (this)
        {
            if (!mRunning)
            {
                Logger.error("Cannot stop LidarServer: not running");
                return false;
            }
            mRunning = false;
            mEnding = true;
        }

        Logger.info("Stopping Lidar...");

        try
        {
            mProcess.destroyForcibly();
            mProcess.waitFor();
            mThread.join();
        }
        catch (InterruptedException e)
        {
            Logger.error("Error: Interrupted while stopping lidar");
            e.printStackTrace();
            synchronized (this)
            {
                mEnding = false;
            }
            return false;
        }
        Logger.info("Lidar Stopped");
        synchronized (this)
        {
            mEnding = false;
        }
        return true;
    }

    public synchronized boolean isRunning()
    {
        return mRunning;
    }

    public synchronized boolean isEnding()
    {
        return mEnding;
    }

    private void handleLine(String line)
    {
        boolean isNewScan = line.substring(line.length() - 1).equals("s");
        if (isNewScan)
        {
            line = line.substring(0, line.length() - 1);
        }

        long curSystemTime = System.currentTimeMillis();
        double curFPGATime = Timer.getFPGATimestamp();

        String[] parts = line.split(",");
        if (parts.length == 3)
        {
            try
            {
                // It is assumed that ts is in sync with our system's clock
                long ts = Long.parseLong(parts[0]);
                long msAgo = curSystemTime - ts;
                double normalizedTs = curFPGATime - (msAgo / 1000.0d);
                double angle = Double.parseDouble(parts[1]);
                double distance = Double.parseDouble(parts[2]);
                if (distance != 0)
                    mLidarProcessor.addPoint(new LidarPoint(normalizedTs, angle, distance), isNewScan);
            }
            catch (java.lang.NumberFormatException e)
            {
                e.printStackTrace();
            }
        }
    }

    private class ReaderThread implements Runnable
    {

        @Override
        public void run()
        {
            while (isRunning())
            {
                try
                {
                    if (mBufferedReader.ready())
                    {
                        String line = mBufferedReader.readLine();
                        if (line == null)
                        { // EOF
                            throw new EOFException("End of chezy-lidar process InputStream");
                        }
                        handleLine(line);
                    }
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                    if (isLidarConnected())
                    {
                        Logger.error("Lidar sensor disconnected");
                        stop();
                    }
                }
            }
        }
    }

}
