package com.termux.app.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ErrorDetector {

    public enum ErrorType {
        JAVA_KOTLIN, PYTHON, NODE_JS, GRADLE_BUILD,
        BASH_COMMAND, C_CPP, RUST, GIT, NPM, GENERIC
    }

    public static class DetectedError {
        public ErrorType type;
        public String message;
        public String file;
        public Integer line;
        public String fullBlock;
        public String command;

        public DetectedError(ErrorType type, String message, String file, Integer line, String fullBlock, String command) {
            this.type = type;
            this.message = message;
            this.file = file;
            this.line = line;
            this.fullBlock = fullBlock;
            this.command = command;
        }
    }

    private static class PatternPair {
        ErrorType type;
        Pattern pattern;

        PatternPair(ErrorType type, Pattern pattern) {
            this.type = type;
            this.pattern = pattern;
        }
    }

    private static final List<PatternPair> PATTERNS = new ArrayList<>();

    static {
        PATTERNS.add(new PatternPair(ErrorType.JAVA_KOTLIN, Pattern.compile(
                "(Exception in thread|FATAL EXCEPTION|java\\.\\w+Exception|kotlin\\.\\w+Exception|at \\w[\\w.]+\\([\\w.]+:\\d+\\))",
                Pattern.MULTILINE)));
        PATTERNS.add(new PatternPair(ErrorType.PYTHON, Pattern.compile(
                "(Traceback \\(most recent call last\\)|^\\s*(TypeError|ValueError|AttributeError|ImportError|ModuleNotFoundError|NameError|IndexError|KeyError|SyntaxError|IndentationError):\\s*.+)",
                Pattern.MULTILINE)));
        PATTERNS.add(new PatternPair(ErrorType.NODE_JS, Pattern.compile(
                "(TypeError:|ReferenceError:|SyntaxError:|UnhandledPromiseRejection|Error: Cannot find module|npm ERR!|node:internal)",
                Pattern.MULTILINE)));
        PATTERNS.add(new PatternPair(ErrorType.GRADLE_BUILD, Pattern.compile(
                "(FAILED|BUILD FAILED|error: |Execution failed for task|> Could not resolve|Caused by:)",
                Pattern.MULTILINE)));
        PATTERNS.add(new PatternPair(ErrorType.BASH_COMMAND, Pattern.compile(
                "(command not found|No such file or directory|Permission denied|bash: .*: not found|-bash:)",
                Pattern.MULTILINE)));
        PATTERNS.add(new PatternPair(ErrorType.RUST, Pattern.compile(
                "(error\\[E\\d+\\]|error: |thread '.*' panicked at)",
                Pattern.MULTILINE)));
        PATTERNS.add(new PatternPair(ErrorType.NPM, Pattern.compile(
                "(npm ERR!|npm WARN.*ENOENT|npm ERR! code E)",
                Pattern.MULTILINE)));
        PATTERNS.add(new PatternPair(ErrorType.GIT, Pattern.compile(
                "(fatal: |error: failed to push|CONFLICT|merge conflict|hint: Updates were rejected)",
                Pattern.MULTILINE)));
        PATTERNS.add(new PatternPair(ErrorType.GENERIC, Pattern.compile(
                "(?i)(^\\s*error:|^\\s*fatal:|^\\s*failed:|^\\s*critical:|unexpected argument|invalid option|unknown command)",
                Pattern.MULTILINE)));
    }

    public static DetectedError detect(String outputBuffer, String lastCommand) {
        if (outputBuffer == null || outputBuffer.isEmpty()) return null;

        for (PatternPair pair : PATTERNS) {
            Matcher matcher = pair.pattern.matcher(outputBuffer);
            if (matcher.find()) {
                String matchValue = matcher.group();
                
                String[] lines = outputBuffer.split("\n");
                int errorLineIdx = -1;
                for (int i = 0; i < lines.length; i++) {
                    if (pair.pattern.matcher(lines[i]).find()) {
                        errorLineIdx = i;
                        break;
                    }
                }
                
                String block = "";
                if (errorLineIdx != -1) {
                    int contextStart = Math.max(0, errorLineIdx - 3);
                    int contextEnd = Math.min(lines.length - 1, errorLineIdx + 8);
                    StringBuilder sb = new StringBuilder();
                    for (int i = contextStart; i <= contextEnd; i++) {
                        sb.append(lines[i]).append("\n");
                    }
                    block = sb.toString().trim();
                }

                String file = null;
                Integer lineNum = null;
                
                if (pair.type == ErrorType.JAVA_KOTLIN) {
                    Matcher m = Pattern.compile("\\((\\w+\\.(kt|java)):(\\d+)\\)").matcher(outputBuffer);
                    if (m.find()) {
                        file = m.group(1);
                        try { lineNum = Integer.parseInt(m.group(3)); } catch (Exception e) {}
                    }
                } else if (pair.type == ErrorType.PYTHON) {
                    Matcher m = Pattern.compile("File \"([^\"]+)\", line (\\d+)").matcher(outputBuffer);
                    if (m.find()) {
                        file = m.group(1);
                        try { lineNum = Integer.parseInt(m.group(2)); } catch (Exception e) {}
                    }
                } else if (pair.type == ErrorType.NODE_JS) {
                    Matcher m = Pattern.compile("\\(([^)]+\\.js):(\\d+):\\d+\\)").matcher(outputBuffer);
                    if (m.find()) {
                        file = m.group(1);
                        try { lineNum = Integer.parseInt(m.group(2)); } catch (Exception e) {}
                    }
                }

                String msg = matchValue;
                if (msg.length() > 200) {
                    msg = msg.substring(0, 200);
                }

                return new DetectedError(pair.type, msg, file, lineNum, block, lastCommand);
            }
        }
        return null;
    }
}
