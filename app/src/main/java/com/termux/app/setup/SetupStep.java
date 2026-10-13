package com.termux.app.setup;

/** One row of the first-run setup wizard. */
public class SetupStep {

    public enum Status {
        PENDING,
        RUNNING,
        DONE,
        FAILED,
        SKIPPED
    }

    public final String id;
    public final String title;
    public String detail;
    public Status status;

    public SetupStep(String id, String title, String detail) {
        this.id = id;
        this.title = title;
        this.detail = detail;
        this.status = Status.PENDING;
    }
}
