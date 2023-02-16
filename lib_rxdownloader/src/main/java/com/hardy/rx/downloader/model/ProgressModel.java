package com.changba.plugin.livechorus.download.model;

import com.changba.plugin.livechorus.download.DownloadTask;

/**
 * @author Hdq on 2021/9/8.
 */
public class ProgressModel {
    private transient DownloadTask task;
    private transient float progress;//范围 0 - 100

    public DownloadTask getTask() {
        return task;
    }

    public void setTask(DownloadTask task) {
        this.task = task;
    }

    public float getProgress() {
        return progress;
    }

    public void setProgress(float progress) {
        this.progress = progress;
    }
}
