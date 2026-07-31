package com.termux.app.git;

import android.os.Handler;
import android.os.Looper;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class GitManager {

    public interface GitStatusCallback {
        void onStatusResult(List<GitFile> files);
        void onError(String error);
    }

    public interface GitCommandCallback {
        void onSuccess(String output);
        void onError(String error);
    }

    private static ProcessBuilder getBaseProcessBuilder(String cwd, String command) {
        String bashPath = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/bash";
        ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", command);
        pb.directory(new java.io.File(cwd));
        pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        pb.environment().put("PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin:" + System.getenv("PATH"));
        pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib");
        pb.environment().put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
        return pb;
    }

    public static void getStatus(String cwd, GitStatusCallback callback) {
        new Thread(() -> {
            try {
                ProcessBuilder pb = getBaseProcessBuilder(cwd, "git status --porcelain");
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                List<GitFile> result = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 3) {
                        String status = line.substring(0, 2).trim();
                        String filename = line.substring(3);
                        result.add(new GitFile(filename, status));
                    }
                }
                process.waitFor();
                new Handler(Looper.getMainLooper()).post(() -> callback.onStatusResult(result));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    public static void commitAndPush(String cwd, String message, GitCommandCallback callback) {
        new Thread(() -> {
            try {
                // Escape message to prevent shell injection issues
                String escapedMessage = message.replace("'", "'\\''");
                String command = "git add . && git commit -m '" + escapedMessage + "' && git push";
                
                ProcessBuilder pb = getBaseProcessBuilder(cwd, command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                int exitCode = process.waitFor();
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (exitCode == 0) {
                        callback.onSuccess(output.toString());
                    } else {
                        callback.onError(output.toString());
                    }
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }
}
