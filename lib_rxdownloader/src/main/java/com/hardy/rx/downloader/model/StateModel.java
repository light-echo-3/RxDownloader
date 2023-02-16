package com.changba.plugin.livechorus.download.model;

import com.changba.plugin.livechorus.download.DownloadTask;

/**
 * @author Hdq on 2021/9/8.
 */
public class StateModel {
    private DownloadTask task;
    private @DownloadTask.State int state;

    public DownloadTask getTask() {
        return task;
    }

    public void setTask(DownloadTask task) {
        this.task = task;
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }
}
