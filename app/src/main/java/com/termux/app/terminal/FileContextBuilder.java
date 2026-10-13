package com.termux.app.terminal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FileContextBuilder {

    private final File projectRoot;

    public FileContextBuilder(File projectRoot) {
        this.projectRoot = projectRoot;
    }

    public String buildFileTree(int maxDepth) {
        StringBuilder sb = new StringBuilder();
        sb.append("PROJECT STRUCTURE:\n");
        sb.append(projectRoot.getName()).append("/\n");
        walkDir(projectRoot, sb, 0, maxDepth, "  ");
        return sb.toString();
    }

    private void walkDir(File dir, StringBuilder sb, int depth, int maxDepth, String prefix) {
        if (depth >= maxDepth) return;
        
        Set<String> skipDirs = new HashSet<>(Arrays.asList(".git", "build", "node_modules", "__pycache__", ".gradle", ".idea", "target"));
        
        File[] files = dir.listFiles();
        if (files == null) return;
        
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            }
        });
        
        for (File file : files) {
            if (skipDirs.contains(file.getName())) continue;
            
            if (file.isDirectory()) {
                sb.append(prefix).append(file.getName()).append("/\n");
                walkDir(file, sb, depth + 1, maxDepth, prefix + "  ");
            } else {
                sb.append(prefix).append(file.getName()).append("\n");
            }
        }
    }

    public String buildOpenFilesContext(List<File> openFiles, int maxCharsPerFile) {
        StringBuilder sb = new StringBuilder();
        for (File file : openFiles) {
            if (file.exists() && file.isFile()) {
                sb.append("\n--- FILE: ").append(file.getName()).append(" ---\n");
                String content = readFileContent(file);
                if (content.length() > maxCharsPerFile) {
                    sb.append(content.substring(0, maxCharsPerFile));
                    sb.append("\n... [truncated, ").append(content.length()).append(" total chars]\n");
                } else {
                    sb.append(content).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String readFileContent(File file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            sb.append("<Error reading file: ").append(e.getMessage()).append(">");
        }
        return sb.toString();
    }

    public String buildSystemPrompt(List<File> openFiles, String userQuery) {
        String tree = buildFileTree(3);
        String filesContext = buildOpenFilesContext(openFiles, 3000);
        
        return "You are an AI coding assistant embedded in Mobile Code.\n" +
               "You have access to the user's codebase context below.\n\n" +
               tree + "\n\n" +
               "CURRENTLY OPEN/TAGGED FILES:\n" +
               filesContext + "\n\n" +
               "Respond to the user's query with specific, accurate help based on their actual code.\n" +
               "Always refer to actual file names and line context when relevant.";
    }
}
