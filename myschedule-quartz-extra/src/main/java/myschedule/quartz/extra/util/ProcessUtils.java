package myschedule.quartz.extra.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Utilities to help run external sub-process, and external java process that has the same classpath setup as the one
 * started the parent JVM.
 * <p/>
 * <p>Note that if process has timeout, it is destroyed. In case of JVM sub process, it will not invoke shutdown hook!
 *
 * @author Zemian Deng
 */
public class ProcessUtils {
    /**
     * NO timeout constant
     */
    public static final long NO_TIMEOUT = -1;

    private static final Logger logger = LoggerFactory.getLogger(ProcessUtils.class);


    /**
     * Run an external command and read STDOUT and STDERR from the sub-process and process each line from output.
     * <p/>
     * <p/>
     * This method will NOT block caller and return Process object after it started the command.
     *
     * @param commandArguments External command and arguments
     * @param lineAction       A action processing each output line.
     * @return exitCode - the external program exit code if it completed successfully.
     * @throws TimeoutException - if timeout >=0 and has reached it.
     */
    public static BackgroundProcess runInBackground(String[] commandArguments, final LineAction lineAction) {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.redirectErrorStream(true);
        processBuilder.command(commandArguments);
        try {
            final Process process = processBuilder.start();

            // Setup another read thread that we can control the timeout.
            Thread stdoutReadingThread = new Thread() {
                public void run() {
                    InputStream inStream = process.getInputStream();
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(inStream));
                        String line = null;
                        while ((line = reader.readLine()) != null) {
                            lineAction.onLine(line);
                        }
                    } catch (IOException e) {
                        // If we get a IO exception, it's likely that the process has already died, so do nothing.
                        //logger.error("Failed to read process STDOUT.", e);
                    } finally {
                        if (inStream != null)
                            try {
                                inStream.close();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                    }
                }

                ;
            };
            stdoutReadingThread.start();
            BackgroundProcess bgProcess = new BackgroundProcess(commandArguments, process, stdoutReadingThread);
            logger.debug("Command started: {}", bgProcess);
            return bgProcess;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Run an external command and read STDOUT and STDERR from the sub-process and process each line from output.
     * <p/>
     * <p/>
     * This method will block caller until command is done, or timeout is up (if set that is.)
     * <p/>
     * <p/>
     * You may set an timeout value to ensure sub-process will terminate after certain milliseconds. A timeout value of
     * <= 0 will be ignored and run as long as the sub-process will take. If timeout is set, and exceeded, it will throw
     * an RuntimeException.
     *
     * @param timeout          Timeout in milliseconds if external command did not return.
     * @param commandArguments External command and arguments
     * @param lineAction       A action processing each output line.
     * @return exitCode - the external program exit code if it completed successfully.
     * @throws TimeoutException - if timeout >=0 and has reached it.
     */
    public static int run(long timeout, String[] commandArguments, LineAction lineAction) {
        // We will use 10% of the timeout period as check interval.
        long checkInterval = (long) (timeout * 0.10);
        return run(timeout, checkInterval, commandArguments, lineAction);
    }

    /**
     * Run an external command and read STDOUT and STDERR from the sub-process and process each line from output.
     * <p/>
     * <p/>
     * This method will block caller until command is done, or timeout is up (if set that is.)
     * <p/>
     * <p/>
     * You may set an timeout value to ensure sub-process will terminate after certain milliseconds. A timeout value of
     * <= 0 will be ignored and run as long as the sub-process will take. If timeout is set, and exceeded, it will throw
     * an RuntimeException.
     *
     * @param timeout              Timeout in milliseconds if external command did not return.
     * @param timeoutCheckInterval if timeout > 0, then this will set pausing interval in millis to check for timeout/process completion.
     * @param commandArguments     External command and arguments
     * @param lineAction           A action processing each output line.
     * @return exitCode - the external program exit code if it completed successfully.
     * @throws TimeoutException - if timeout >=0 and has reached it.
     */
    public static int run(long timeout, long timeoutCheckInterval, final String[] commandArguments,
                          final LineAction lineAction) {
        try {
            long startTime = System.currentTimeMillis();
            final BackgroundProcess bgProcess = runInBackground(commandArguments, lineAction);
            int exitCode = 0;
            if (timeout > 0) {
                // Let's loop until timeout or Process is done.
                // The loop is necessary because in some OS, the process.getInputStream() might return null
                // prematurely before the process is started or even terminated, so we can not depend on just
                // reading the STDOUT steam.
                logger.debug("Monitoring process with timeout period of {} ms with check interval: {} ms.",
                        timeout, timeoutCheckInterval);
                while ((System.currentTimeMillis() - startTime) < timeout && !bgProcess.isDone()) {
                    Thread.sleep(timeoutCheckInterval);
                }
                if (bgProcess.isDone()) {
                    exitCode = bgProcess.getExitCode();
                } else {
                    long stopTime = System.currentTimeMillis();
                    String msg = "Process has timed-out. It ran for " + (stopTime - startTime) + "/" + timeout + " ms.";
                    logger.debug(msg);

                    // Process is still running. We must force determination of the Process.
                    bgProcess.destroy();

                    // Throw timeout exception.
                    throw new TimeoutException(msg);
                }
            } else {
                exitCode = bgProcess.waitForExit();
            }

            long stopTime = System.currentTimeMillis();
            logger.info("Process completed in {} ms. ExitCode: {}", (stopTime - startTime), exitCode);

            return exitCode;
        } catch (Exception e) {
            // If it's a timeout exception, re-throw it as it.
            if (e instanceof TimeoutException)
                throw (TimeoutException) e;

            // Ohoh, we have other problems, let's re-throw as generic RuntimeException.
            throw new RuntimeException(e);
        }
    }

    /**
     * Run an external command and read STDOUT and STDERR from the sub-process and return the output as List of String.
     * <p/>
     * <p/>
     * This method will block caller until command is done, or timeout is up (if set that is.)
     * <p/>
     * <p/>
     * You may set an timeout value to ensure sub-process will terminate after certain milliseconds. A timeout value of
     * <= 0 will be ignored and run as long as the sub-process will take. If timeout is set, and exceeded, it will throw
     * an RuntimeException.
     *
     * @param timeout          Timeout in milliseconds if external command did not return.
     * @param commandArguments External command and arguments
     * @return A list of all output from the external program.
     * @throws TimeoutException - if timeout >=0 and has reached it.
     */
    public static List<String> run(final long timeout, final String... commandArguments) {
        LineCollector lineCollector = new LineCollector();
        run(timeout, commandArguments, lineCollector);
        List<String> outputs = lineCollector.getLines();
        logger.debug("Process completed with output lines size {}.", outputs.size());
        return outputs;
    }

    /**
     * Run an external command and read STDOUT and STDERR from the sub-process and return the output as List of String.
     * <p/>
     * <p/>
     * This method will block caller until command is done, or timeout is up (if set that is.)
     *
     * @param commandArguments External command and arguments
     * @return List of output strings if command is successfully. Null if timeout reached.
     */
    public static List<String> run(final String... commandArguments) {
        // Run and wait until command is done.
        return run(NO_TIMEOUT, commandArguments);
    }

    /**
     * Execute a java command that use same classpath that started it's original process.
     * <p/>
     * <p/>
     * This method will block caller until command is done, or timeout is up (if set that is.)
     *
     * @param timeout     Timeout in millis if external command did not return.
     * @param javaCmdArgs Arguments to the java command.
     * @return List of output strings.
     */
    public static List<String> runJava(final long timeout, final String... javaCmdArgs) {
        String[] noJavaOpts = null;
        return runJavaWithOpts(timeout, noJavaOpts, javaCmdArgs);
    }

    /**
     * Execute a java command that use same classpath that started it's original process, with additional user JAVA_OPTS
     * array values.
     * <p/>
     * <p/>
     * This method will block caller until command is done, or timeout is up (if set that is.)
     *
     * @param timeout     Timeout in milliseconds if external command did not return.
     * @param javaOpts    Arguments to the java options. It can be null to mean not set any.
     * @param javaCmdArgs Arguments to the java command.
     * @return List of output strings.
     */
    public static List<String> runJavaWithOpts(final long timeout, final String[] javaOpts, final String[] javaCmdArgs) {
        LineCollector lineCollector = new LineCollector();
        runJavaWithOpts(timeout, javaOpts, javaCmdArgs, lineCollector);
        List<String> outputs = lineCollector.getLines();
        if (logger.isDebugEnabled()) {
            logger.debug("Java process completed with output lines size {}.", outputs.size());
            for (String line : outputs)
                logger.debug("OUT> {}", line);
        }
        return outputs;
    }

    /**
     * Execute a java command that use same classpath that started it's original process, with additional user JAVA_OPTS
     * array values.
     * <p/>
     * <p/>
     * This method will block caller until command is done, or timeout is up (if set that is.)
     *
     * @param timeout     Timeout in milliseconds if external command did not return.
     * @param lineAction  a action object to process each line.
     * @param javaOpts    Arguments to the java options. It can be null to mean not set any.
     * @param javaCmdArgs Arguments to the java command.
     * @return exitCode - the Java (JVM) exit code if it completed successfully.
     * @throws TimeoutException - if timeout >=0 and has reached it.
     */
    public static int runJavaWithOpts(final long timeout, final String[] javaOpts, final String[] javaCmdArgs,
                                      final LineAction lineAction) {
        String pathSep = File.separator;
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + pathSep + "bin" + pathSep + "java";
        String classpath = System.getProperty("java.class.path");
        List<String> args = new ArrayList<String>();
        args.add(javaBin);
        if (javaOpts != null && javaOpts.length > 0) {
            for (String javaOpt : javaOpts)
                args.add(javaOpt);
        }
        args.add("-cp");
        args.add(classpath);
        for (String javaArg : javaCmdArgs)
            args.add(javaArg);
        String[] arguments = args.toArray(new String[0]);

        return run(timeout, arguments, lineAction);
    }

    /**
     * Determine whether a process has exited or not.
     * <p/>
     * <p/>
     * This is really a bad way to check, but is there any better other way?
     *
     * @param process Process
     * @return true if exitValue is obtainable, false otherwise.
     */
    private static boolean isProcessDone(final Process process) {
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException e) {
            return false;
        }
    }

    /**
     * @return A enum value for OS name.
     */
    public static EOSType getOsType() {
        String osName = getOsName().toLowerCase();
        if (osName.startsWith("windows"))
            return EOSType.WINDOWS;
        else if (osName.toLowerCase().startsWith("sun"))
            return EOSType.SUNOS;
        else
            return EOSType.UNKNOWN;
    }

    /**
     * @return Get the system Operating System name from sys prop: "os.name".
     */
    public static String getOsName() {
        return System.getProperty("os.name");
    }

    /**
     * Enum types for different type of OS.
     *
     * @author Zemian Deng
     */
    public static enum EOSType {
        /**
         * Windows
         */
        WINDOWS,

        /**
         * SunOS - Solaris
         */
        SUNOS,

        /**
         * Unkown yet
         */
        UNKNOWN,
    }

    /**
     * Comment for TimeoutException.
     *
     * @author Zemian Deng
     */
    public static class TimeoutException extends RuntimeException {

        /**
         * serialVersionUID - long
         */
        private static final long serialVersionUID = 1L;

        /**
         * Constructor
         *
         * @param message param.
         */
        public TimeoutException(final String message) {
            super(message);
        }
    }

    /**
     * Collect lines for each line processing into a list.
     *
     * @author Zemian Deng
     */
    public static class LineCollector implements LineAction {

        private static final Logger logger = LoggerFactory.getLogger(ProcessUtils.LineCollector.class);

        /**
         * Line storage.
         */
        protected List<String> lines = new ArrayList<String>();

        /**
         * Getter.
         *
         * @return the lines - List<String>
         */
        public List<String> getLines() {
            return lines;
        }

        /**
         * Collect line into list.
         *
         * @param line input.
         */
        public void onLine(final String line) {
            logger.debug("Line: {}", line);
            lines.add(line);
        }
    }

    /**
     * Line processing action callback interface.
     *
     * @author Zemian Deng
     */
    public static interface LineAction {
        void onLine(String line);
    }

    public static class BackgroundProcess {
        protected boolean destroyed;
        protected Date startTime = new Date();
        protected String[] commandArgs;
        protected Process process;
        protected Thread stdoutReadingThread;

        public BackgroundProcess(String[] commandArgs, Process process, Thread stdoutReadingThread) {
            this.commandArgs = commandArgs;
            this.process = process;
            this.stdoutReadingThread = stdoutReadingThread;
        }

        public boolean isDestroyed() {
            return destroyed;
        }

        public int getExitCode() {
            return process.exitValue();
        }

        public void destroy() {
            logger.debug("Destroying running process {}.", this);
            stdoutReadingThread.interrupt();
            process.destroy();
            destroyed = true;
            logger.info("{} destroyed.", this);
        }

        public boolean isDone() {
            return ProcessUtils.isProcessDone(process);
        }

        public int waitForExit() {
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        public Process getProcess() {
            return process;
        }

        public Thread getStdoutReadingThread() {
            return stdoutReadingThread;
        }

        public Date getStartTime() {
            return startTime;
        }

        @Override
        public String toString() {
            return "Process[" + Arrays.asList(commandArgs) + ", startTime=" + startTime + "]";
        }
    }
}
