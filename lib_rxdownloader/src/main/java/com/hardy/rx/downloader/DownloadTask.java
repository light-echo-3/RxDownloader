package com.hardy.rx.downloader;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.IntDef;

import com.hardy.rx.downloader.model.ProgressModel;
import com.hardy.rx.downloader.model.StateModel;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * @author Hdq on 2021/3/9.
 */
public class DownloadTask {
    private static final String TAG = "----DownloadTask";

    private Subject<ProgressModel> progressSubject;
    private ProgressModel progressModel = new ProgressModel();//范围 0 - 100
    private Subject<StateModel> stateSubject;
    private StateModel stateModel = new StateModel();
    private OkHttpClient client;
    private AtomicBoolean isStop = new AtomicBoolean(false);
    private String downloadUrl;
    private String localFileName;
    private float weight;//记录该任务的权重值.用于多个任务在一起时候计算总进度
    /**
     * 是否仅检查本地文件是否存在
     * true:仅检查本地文件是否存在
     * false:会通过网络检查文件是否已下载，从server拉取文件长度，检查本地文件长度是否一致，一致则认为下载完成
     * **/
    private boolean isOnlyCheckLocalFileExit = false;

    public DownloadTask(String downloadUrl, String localFileName) {
        this(downloadUrl,localFileName,1);
    }

    public DownloadTask(String downloadUrl, String localFileName, float weight) {
        if (TextUtils.isEmpty(downloadUrl)) {
            Log.e(TAG, "DownloadTask: 传入的url为空，localFileName = " + localFileName);
        }
        if (TextUtils.isEmpty(localFileName)) {
            Log.e(TAG, "DownloadTask: 传入的本地文件路径为空，downloadUrl = " + downloadUrl);
        }
        client = new OkHttpClient();
        progressSubject = PublishSubject.<ProgressModel>create().toSerialized();
        stateSubject = PublishSubject.<StateModel>create().toSerialized();
        this.downloadUrl = downloadUrl;
        this.localFileName = localFileName;

        progressModel.setTask(this);
        stateModel.setTask(this);
        stateModel.setState(State.PREPARE);
        stateSubject.onNext(stateModel);
        this.weight = weight;
    }

    /**
     * 不能直接在主线程执行，如果主线程执行，请调用{@link DownloadTask#start()}
     */
    protected void startSync(){
        isStop.getAndSet(false);
        checkDownload(downloadUrl,localFileName);
    }

    /**
     * 可在主线程直接调用
     */
    public void start(){
        Schedulers.io().scheduleDirect(() -> startSync());
    }

    public void stop(){
        isStop.getAndSet(true);
    }

    private void checkDownload(String downloadUrl, String localFileName) {
        File localFile = new File(localFileName);
        if (isOnlyCheckLocalFileExit && localFile.exists() && localFile.length() > 0) {
            notifyDownloadSuccess();
            return;
        }
        //得到下载内容的大小
        long serverContentLength = 0;
        try {
            serverContentLength = getContentLength(downloadUrl);
        } catch (IOException e) {
            progressSubject.onError(e);
            stateModel.setState(State.ERROR);
            stateSubject.onNext(stateModel);
            stateSubject.onError(e);
            return;
        }

        if(serverContentLength == 0){
            Exception e = new Exception("server返回文件长度=0");
            progressSubject.onError(e);
            stateModel.setState(State.ERROR);
            stateSubject.onNext(stateModel);
            stateSubject.onError(e);
            return;
        }
        if (localFile.exists()) {
            if (localFile.length() == serverContentLength) {//文件已经下载完
                notifyDownloadSuccess();
                return;//---!!!
            } else {//文件有错误（相当于一次错误检测）
                localFile.delete();
            }
        }
        download(serverContentLength,downloadUrl,localFileName);
    }

    private void notifyDownloadSuccess() {
        progressModel.setProgress(100);
        progressSubject.onNext(progressModel);
        progressSubject.onComplete();
        stateModel.setState(State.DOWNLOAD_SUCCESS);
        stateSubject.onNext(stateModel);
        stateSubject.onComplete();
    }

    private void download(long serverContentLength,String downloadUrl, String localFileName) {
        InputStream is = null;
        RandomAccessFile savedFile = null;
        long downloadLength = 0;   //记录已经下载的文件长度
        File tempFile = new File(localFileName + ".tmp");//临时文件
        if(tempFile.exists()){
            //如果文件存在的话，得到文件的大小
            downloadLength = tempFile.length();
            Log.d(TAG, "download: 本地临时文件大小：" + downloadLength);
        }
        Log.d(TAG, "download: server文件总大小：" + serverContentLength);
        if(serverContentLength == downloadLength){
            notifyDownloadSuccess();
            tempFile.renameTo(new File(localFileName));
            return;
        }
        /**
         * HTTP请求是有一个Header的，里面有个Range属性是定义下载区域的，它接收的值是一个区间范围，
         * 比如：Range:bytes=0-10000。这样我们就可以按照一定的规则，将一个大文件拆分为若干很小的部分，
         * 然后分批次的下载，每个小块下载完成之后，再合并到文件中；这样即使下载中断了，重新下载时，
         * 也可以通过文件的字节长度来判断下载的起始点，然后重启断点续传的过程，直到最后完成下载过程。
         */
        Request request = new Request.Builder()
                .addHeader("RANGE","bytes=" + downloadLength + "-")  //断点续传要用到的，指示下载的区间
                .url(downloadUrl)
                .build();
        try {
            Response response = client.newCall(request).execute();
            if(response != null){
                is = response.body().byteStream();
                savedFile = new RandomAccessFile(tempFile,"rw");
                savedFile.seek(downloadLength);//跳过已经下载的字节
                byte[] b = new byte[1024];
                int total = 0;
                int len;
                stateModel.setState(State.DOWNLOADING);
                stateSubject.onNext(stateModel);
                while(!isStop.get() && (len = is.read(b)) != -1){
                    total += len;
                    savedFile.write(b,0,len);
                    //计算已经下载的百分比
                    progressModel.setProgress((total + downloadLength) / (float)serverContentLength * 100f);
                    progressSubject.onNext(progressModel);
                    Log.d("----Download", "progress--0: " + progressModel.getProgress());
                }
                response.body().close();
                float progress = progressModel.getProgress();
                if (progress > 100) {
                    Log.e(TAG, "download: progress =" + progress);
                    new RuntimeException("progress =" + progress + "下载的文件大小 > server给的大小");
                }
                if (progress == 100) {
                    stateModel.setState(State.DOWNLOAD_SUCCESS);
                    boolean success = tempFile.renameTo(new File(localFileName));//重命名
                    if (!success) {
                        notifyError(new Exception("重命名失败"));
                        return;
                    }
                } else {
                    stateModel.setState(State.STOPPED);
                }
                stateSubject.onNext(stateModel);
                stateSubject.onComplete();
                progressSubject.onComplete();

            }
        } catch (IOException e) {
            e.printStackTrace();
            notifyError(e);
        } finally {
            try{
                if(is != null){
                    is.close();
                }
                if(savedFile != null){
                    savedFile.close();
                }
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    public void notifyError(Exception e) {
        stateModel.setState(State.ERROR);
        stateSubject.onNext(stateModel);
        stateSubject.onError(e);
        progressSubject.onError(e);
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getLocalFileName() {
        return localFileName;
    }

    public void setLocalFileName(String localFileName) {
        this.localFileName = localFileName;
    }

    /**
     * 得到下载内容的大小
     * @param downloadUrl
     * @return
     */
    private long getContentLength(String downloadUrl) throws IOException {
        Request request = new Request.Builder().url(downloadUrl).build();
        Response response = client.newCall(request).execute();
        if(response !=null && response.isSuccessful()){
            long contentLength = response.body().contentLength();
            response.body().close();
            return contentLength;
        }
        return  0;
    }

    public Observable<ProgressModel> getProgressObservable() {
        return progressSubject.toSerialized();
    }

    public float getProgress() {
        return progressModel.getProgress();
    }

    public Observable<StateModel> getStateObservable() {
        return stateSubject.toSerialized();
    }

    public @State int getState() {
        return stateModel.getState();
    }

    public float getWeight() {
        return weight;
    }

    public void setWeight(float weight) {
        this.weight = weight;
    }

    public boolean isOnlyCheckLocalFileExit() {
        return isOnlyCheckLocalFileExit;
    }

    public void setOnlyCheckLocalFileExit(boolean onlyCheckLocalFileExit) {
        isOnlyCheckLocalFileExit = onlyCheckLocalFileExit;
    }

    @IntDef({State.PREPARE, State.DOWNLOADING, State.DOWNLOAD_SUCCESS, State.STOPPED, State.ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State{
        int PREPARE = 0x1;
        int DOWNLOADING = 0x10;
        int DOWNLOAD_SUCCESS = 0x100;
        int STOPPED = 0x1000;

        int ERROR = -0x1;

        final class Print{
            public static String toString(@State int state){
                switch (state){
                    case PREPARE:
                        return "PREPARE";
                    case DOWNLOADING:
                        return "DOWNLOADING";
                    case DOWNLOAD_SUCCESS:
                        return "DOWNLOAD_SUCCESS";
                    case STOPPED:
                        return "STOPPED";
                    case ERROR:
                        return "ERROR";
                    default:
                        return "/";
                }
            }
        }
    }
}
