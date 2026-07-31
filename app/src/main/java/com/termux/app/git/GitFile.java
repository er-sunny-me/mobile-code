package com.termux.app.git;

public class GitFile {
    private String filename;
    private String status;

    public GitFile(String filename, String status) {
        this.filename = filename;
        this.status = status;
    }

    public String getFilename() {
        return filename;
    }

    public String getStatus() {
        return status;
    }
}
