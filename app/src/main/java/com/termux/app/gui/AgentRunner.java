package com.termux.app.gui;

import android.os.Handler;
import android.os.Looper;

import com.termux.app.terminal.CLIAgentManager;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Runs a CLI coding agent as a background process and streams its output.
 *
 * <p>Supports sending live stdin (such as 'y\n' / 'n\n') for in-chat permission approvals.</p>
 */
public class AgentRunner {

    /** Where a command actually ran. */
    public enum Environment {
        /** Native Termux ($PREFIX/...). */
        TERMUX,
        /** The Ubuntu proot rootfs the app's terminal opens in. */
        UBUNTU_WORKSHOP
    }

    public interface Listener {
        /** Called on the main thread right after the process starts. */
        void onStarted(String command);

        /** Called on the main thread for each output line. */
        void onOutput(String line);

        /** Called on the main thread when the process exits. */
        void onFinished(int exitCode);

        /** Called on the main thread when the process could not be started at all. */
        void onFailure(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Process process;
    private OutputStream processStdin;
    private Thread readerThread;
    private volatile boolean cancelled;
    private Environment lastEnvironment = Environment.TERMUX;

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /** Which environment the most recent run used. */
    public Environment getLastEnvironment() {
        return lastEnvironment;
    }

    /**
     * Send input (such as "y\n" or "n\n") to the currently running agent process.
     * Used for interactive permission approvals directly from in-chat cards.
     *
     * @return true if successfully sent, false if no process is active or write failed.
     */
    public synchronized boolean sendInput(String input) {
        if (input == null) return false;
        if (process != null && process.isAlive() && processStdin != null) {
            try {
                processStdin.write(input.getBytes(StandardCharsets.UTF_8));
                processStdin.flush();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * True when the Ubuntu workshop rootfs the app's terminal uses is installed.
     * Delegates to {@link CLIAgentManager} so the GUI and the agent layer cannot disagree
     * about which environment agents live in.
     */
    public static boolean isWorkshopAvailable() {
        return CLIAgentManager.isWorkshopAvailable();
    }

    /**
     * Wrap {@code command} so it runs inside the Ubuntu workshop rootfs — the same environment
     * the app's terminal opens in, and the one the install flow writes into.
     * Delegates to {@link CLIAgentManager}.
     */
    public static String wrapForWorkshop(String command) {
        return CLIAgentManager.wrapForWorkshop(command);
    }

    /**
     * Start {@code command} in {@code cwd}.
     *
     * @param cwd      working directory (usually the opened workspace)
     * @param command  full shell command line, already quoted
     * @param listener callbacks, always delivered on the main thread
     */
    public void run(String cwd, String command, Listener listener) {
        cancel();

        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) {
            main.post(() -> listener.onFailure(
                    "Termux bootstrap is not installed yet. Open the terminal once to finish setup."));
            return;
        }

        boolean inWorkshop = isWorkshopAvailable();
        lastEnvironment = inWorkshop ? Environment.UBUNTU_WORKSHOP : Environment.TERMUX;
        String effectiveCommand = inWorkshop ? wrapForWorkshop(command) : command;

        ProcessBuilder pb = new ProcessBuilder(bash, "-lc", effectiveCommand);
        File dir = new File(cwd == null ? TermuxConstants.TERMUX_HOME_DIR_PATH : cwd);
        if (!dir.isDirectory()) {
            dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        }
        pb.directory(dir);

        // Mirror the environment TermuxService gives the login shell.
        pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/"
                + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
        pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib");
        pb.environment().put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("LANG", "en_US.UTF-8");
        pb.redirectErrorStream(true);

        cancelled = false;

        try {
            process = pb.start();
            processStdin = process.getOutputStream();
        } catch (Exception e) {
            main.post(() -> listener.onFailure("Failed to start agent: " + e.getMessage()));
            return;
        }

        main.post(() -> listener.onStarted(effectiveCommand));

        final Process started = process;
        readerThread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    final String out = line;
                    if (!cancelled) {
                        main.post(() -> listener.onOutput(out));
                    }
                }
            } catch (Exception e) {
                if (!cancelled) {
                    final String msg = e.getMessage();
                    main.post(() -> listener.onOutput("[stream closed: " + msg + "]"));
                }
            }

            int exit;
            try {
                exit = started.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exit = -1;
            }

            synchronized (AgentRunner.this) {
                if (processStdin != null) {
                    try {
                        processStdin.close();
                    } catch (Exception ignored) {
                    }
                    processStdin = null;
                }
            }

            final int code = exit;
            if (!cancelled) {
                main.post(() -> listener.onFinished(code));
            }
        }, "agent-runner");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Kill the running agent, if any. Safe to call repeatedly. */
    public synchronized void cancel() {
        cancelled = true;
        if (processStdin != null) {
            try {
                processStdin.close();
            } catch (Exception ignored) {
            }
            processStdin = null;
        }
        Process p = process;
        process = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
        }
        Thread t = readerThread;
        readerThread = null;
        if (t != null) {
            t.interrupt();
        }
    }
}
