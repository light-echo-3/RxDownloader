package com.wuzhu.rx.downloader;

/**
 * @author Hdq on 2021/3/10.
 */
public class Downloader {
    private static final String TAG = "----Downloader";
    private static volatile DownloadGroup defaultDownloadGroup;
    public static DownloadGroup defaultDownloadGroup(){
        if (defaultDownloadGroup == null) {
            synchronized (Downloader.class){
                if (defaultDownloadGroup == null) {
                    defaultDownloadGroup = new DownloadGroup(5);
                    defaultDownloadGroup.start();
                }
            }
        }
        return defaultDownloadGroup;
    }

    public static void stopDefaultDownloadGroup(){
        stopDownloadGroup(defaultDownloadGroup);
    }

    public static DownloadGroup newDownloadGroup(){
        return newDownloadGroup(5);
    }

    /**
     * 最大并发下载数量
     * @param limit
     * @return
     */
    public static DownloadGroup newDownloadGroup(int limit){
        return new DownloadGroup(limit);
    }

    public static void stopDownloadGroup(DownloadGroup downloadGroup){
        if (downloadGroup != null) {
            downloadGroup.stopGroup();
        }
    }

}
