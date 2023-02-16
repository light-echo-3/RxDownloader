package com.changba.plugin.livechorus.download;

import com.changba.library.commonUtils.KTVLog;
import com.changba.plugin.livechorus.download.model.ProgressModel;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;

/**
 * @author Hdq on 2021/3/9.
 * 可参考：https://www.cnblogs.com/baiqiantao/p/10679677.html
 * https://github.com/lingochamp/FileDownloader/blob/master/README-zh.md
 */
public class DownloadGroup {
    private static final String TAG = "----DownloadGroup";

    private Subject<DownloadTask> downloadTaskSubject;
    private LinkedList<DownloadTask> waitingTasks = new LinkedList<>();
    private LinkedList<DownloadTask> downloadingTasks = new LinkedList<>();
    private List<DownloadTask> allTasks = new ArrayList<>();

    private Subscription downloadQueueSubscription;
    private CompositeDisposable mSubscriptions = new CompositeDisposable();

    private int limit;

    private ThreadPoolExecutor executor;

    private int count;

    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private AtomicBoolean isStarted = new AtomicBoolean(false);
    private AtomicBoolean isDestroy = new AtomicBoolean(false);
    private Object startStopLock = new Object();

    /**
     *
     * @param limit 最大并发下载数量
     */
    public DownloadGroup(int limit) {
        this.limit = limit;
        Collections.synchronizedList(waitingTasks);
        Collections.synchronizedList(downloadingTasks);
        executor = new ThreadPoolExecutor(0, limit * 2, 1000 * 60, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new NamedThreadFactory("DownloadGroup"), new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                throw new RejectedExecutionException("Task " + r.toString() +
                        " rejected from " +
                        executor.toString() + ",无法下载，超出最大线程限制");
            }
        });
        downloadTaskSubject = PublishSubject.<DownloadTask>create().toSerialized();
    }

    public void start() {
        if (isStarted.get()) { return; }
        synchronized (startStopLock) {
            isStarted.getAndSet(true);
            isDestroy.getAndSet(false);
            downloadTaskSubject.toFlowable(BackpressureStrategy.DROP)
                    .observeOn(Schedulers.io(), false, 10000)
                    .subscribe(new Subscriber<DownloadTask>() {

                        @Override
                        public void onSubscribe(Subscription s) {
                            downloadQueueSubscription = s;
                            s.request(limit);
                        }

                        @Override
                        public void onError(Throwable e) {
                            e.printStackTrace();
                        }

                        @Override
                        public void onComplete() {

                        }

                        @Override
                        public void onNext(DownloadTask downloadTask) {
                            count++;
                            KTVLog.d(TAG, "onNext: 从队列中取出一个下载任务 count = " + count);
                            try {
                                lock.writeLock().lock();
                                waitingTasks.remove(downloadTask);
                                downloadingTasks.add(downloadTask);
                            } catch (Exception e) {
                                e.printStackTrace();
                            } finally {
                                lock.writeLock().unlock();
                            }
                            mSubscriptions.add(downloadTask.getProgressObservable().subscribe(
                                    progress -> {//onNext
                                    }, throwable -> {//onError
                                        throwable.printStackTrace();
                                    }, () -> {//onCompleted
                                        KTVLog.d(TAG, "下载完成onCompleted: ");
                                        if (downloadQueueSubscription != null) {
                                            downloadQueueSubscription.request(1);
                                        }
                                    }
                            ));

                            try {
                                KTVLog.d(TAG, "onNext: 开线程执行下载");
                                executor.execute(() -> {
                                    KTVLog.d(TAG, "downloadTask.start - thread name=" + Thread.currentThread());
                                    try {
                                        downloadTask.startSync();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                });
                            } catch (Exception e) {
                                KTVLog.e(TAG, "线程池错误", e);
                                downloadTask.notifyError(e);
                            }
                        }
                    });
        }
    }

    /**
     * 停止下载
     * 只是停止，重新调用start还会继续之前的下载
     */
    public void stopGroup() {
        synchronized (startStopLock) {
            if (downloadQueueSubscription != null) {
                downloadQueueSubscription.cancel();
            }
            mSubscriptions.clear();
            try {
                lock.writeLock().lock();
                Iterator<DownloadTask> iterator = downloadingTasks.iterator();
                while (iterator.hasNext()) {
                    DownloadTask task  = iterator.next();
                    task.stop();
                    if (task.getState() != DownloadTask.State.DOWNLOAD_SUCCESS) {
                        iterator.remove();
                        waitingTasks.add(task);//放回waitingTasks
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                lock.writeLock().unlock();
            }
            isStarted.getAndSet(false);
        }
    }

    /**
     * 停止下载
     * 清空下载任务
     */
    public void destroyGroup() {
        synchronized (startStopLock) {
            try {
                lock.writeLock().lock();
                stopGroup();
                waitingTasks.clear();
                downloadingTasks.clear();
                allTasks.clear();
                isDestroy.getAndSet(true);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    public DownloadTask enqueue(String downloadUrl, String localFileName) {
        return enqueue(downloadUrl,localFileName,1);
    }

    public DownloadTask enqueue(String downloadUrl, String localFileName, float weight) {
        return enqueue(downloadUrl,localFileName,weight,false);
    }

    public DownloadTask enqueue(String downloadUrl, String localFileName, boolean isOnlyCheckLocalFileExit) {
        return enqueue(downloadUrl,localFileName,1,isOnlyCheckLocalFileExit);
    }

    public DownloadTask enqueue(String downloadUrl, String localFileName, float weight, boolean isOnlyCheckLocalFileExit) {
        DownloadTask task = findTask(downloadUrl);
        if (task != null && task.getState() == DownloadTask.State.DOWNLOAD_SUCCESS && !new File(localFileName).exists()) {
            //处理异常情况，这种情况发生在：app开着，手动删除本地文件
            downloadingTasks.remove(task);
            allTasks.remove(task);
            task = null;
        }
        if (task == null) {
            task = new DownloadTask(downloadUrl,localFileName,weight);
            task.setOnlyCheckLocalFileExit(isOnlyCheckLocalFileExit);
            try {
                lock.writeLock().lock();
                waitingTasks.add(task);
                allTasks.add(task);
                isReComputeTotalWeight.getAndSet(true);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                lock.writeLock().unlock();
            }
            downloadTaskSubject.onNext(task);
            KTVLog.d(TAG, "enqueue: downloadUrl=" + downloadUrl + ",localFileName=" + localFileName);
        } else {
            task.setOnlyCheckLocalFileExit(isOnlyCheckLocalFileExit);
            KTVLog.w(TAG, "enqueue: 下载任务已存在 downloadUrl=" + downloadUrl + ",localFileName=" + localFileName);
        }
        return task;
    }


    public DownloadTask findTask(String url) {
        try {
            lock.readLock().lock();
            Iterator<DownloadTask> iterator = allTasks.iterator();
            while (iterator.hasNext()) {
                DownloadTask task = iterator.next();
                if (task.getDownloadUrl().equals(url)) {
                    return task;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.readLock().unlock();
        }
        return null;
    }

    public List<DownloadTask> getWaitingTasks() {
        return waitingTasks;
    }

    public List<DownloadTask> getDownloadingTasks() {
        return downloadingTasks;
    }

    public List<DownloadTask> getAllTasks() {
        List<DownloadTask> tempList = new ArrayList<>();
        try {
            lock.readLock().lock();
            tempList.addAll(allTasks);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.readLock().unlock();
        }
        return tempList;
    }


    public float getTotalProgress(){
        float totalWeight = getTotalWeight();
        float avgProgress = 0;
        try {
            lock.readLock().lock();
            Iterator<DownloadTask> iterable = allTasks.iterator();
            while (iterable.hasNext()) {
                DownloadTask task = iterable.next();
                avgProgress += task.getProgress() * (task.getWeight() / totalWeight);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.readLock().unlock();
        }
        KTVLog.d(TAG, "progress-----1: " + avgProgress);
        return avgProgress;
    }

    private AtomicBoolean isReComputeTotalWeight = new AtomicBoolean(false);
    private float totalWeight = 0;
    public float getTotalWeight() {
        if (isReComputeTotalWeight.get()) {
            totalWeight = 0;
            try {
                lock.readLock().lock();
                Iterator<DownloadTask> iterable = allTasks.iterator();
                while (iterable.hasNext()) {
                    DownloadTask task = iterable.next();
                    totalWeight += task.getWeight();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                lock.readLock().unlock();
            }
            isReComputeTotalWeight.getAndSet(false);
            KTVLog.d(TAG, "getTotalWeight: " + totalWeight);
        }
        return totalWeight;
    }

    /***
     * 所有任务的总进度
     * @return
     */
    public Observable<ProgressModel> getTotalProgressObservable(){
        List<Observable<ProgressModel>> allProgressObservable = new ArrayList<>();
        ProgressModel allProgressModel = new ProgressModel();
        try {
            lock.readLock().lock();
            Iterator<DownloadTask> iterable = allTasks.iterator();
            while (iterable.hasNext()) {
                DownloadTask task = iterable.next();
                allProgressObservable.add(task.getProgressObservable());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.readLock().unlock();
        }
        return Observable.merge(allProgressObservable)
                .sample(200,TimeUnit.MILLISECONDS)
                .map(progressModel -> {
                    allProgressModel.setTask(progressModel.getTask());
                    allProgressModel.setProgress(getTotalProgress());
                    return allProgressModel;
                });
    }

    public void testReset(){
        allTasks.clear();
        downloadingTasks.clear();
        waitingTasks.clear();
    }

    /**
     * 当前的所有任务下载完成
     * @return
     */
    public boolean isAllTaskDownloadSuccess() {
        List<DownloadTask> tasks = getAllTasks();
        for (DownloadTask task : tasks) {
            if (task.getState() != DownloadTask.State.DOWNLOAD_SUCCESS) {
                return false;
            }
        }
        return true;
    }

    public boolean isDestroy() {
        return isDestroy.get();
    }
}
