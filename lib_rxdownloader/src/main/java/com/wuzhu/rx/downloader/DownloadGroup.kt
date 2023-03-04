package com.wuzhu.rx.downloader

import android.util.Log
import com.wuzhu.rx.downloader.model.*
import io.reactivex.BackpressureOverflowStrategy
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.observers.DisposableObserver
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.ReplaySubject
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.io.File
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val TAG = "----DownloadGroup"

/**
 * @author Hdq on 2021/3/9.
 * 可参考：https://www.cnblogs.com/baiqiantao/p/10679677.html
 * https://github.com/lingochamp/FileDownloader/blob/master/README-zh.md
 * @param limit 最大并发下载数量
 */
class DownloadGroup(private val limit: Int) {
    private val business by lazy { DownloadGroupBusiness() }

    private val progressSubject = BehaviorSubject.create<GroupProgressModel>().toSerialized()

    /**
     * 这里提前new出来，避免使用时候频繁创建对象
     * 范围 0 - 100
     */
    private val progressModel = GroupProgressModel(this)
    private val stateSubject = BehaviorSubject.create<GroupStateModel>().toSerialized()

    /**
     * 这里提前new出来，避免使用时候频繁创建对象
     */
    private val groupStateModel = GroupStateModel(this)

    private val downloadTaskSubject = ReplaySubject.create<DownloadTask>().toSerialized()
    private var downloadQueueSubscription: Subscription? = null

    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        0, limit * 2, 1000 * 60, TimeUnit.MILLISECONDS, SynchronousQueue(), NamedThreadFactory("DownloadGroup")
    ) { r: Runnable, executor: ThreadPoolExecutor ->
        throw RejectedExecutionException(
            "Task $r rejected from $executor,无法下载，超出最大线程限制"
        )
    }
    private var count = 0

    @Volatile
    private var isCreated = false

    @Volatile
    private var isStarted = false

    private val lifecycleLock = Object()

    init {
        groupStateModel.state = State.PREPARE
        stateSubject.onNext(groupStateModel)
    }


    private fun produce(task: DownloadTask) {
        Log.w(TAG, "produce: $task")
        business.waitingQueue.add(task)
        downloadTaskSubject.onNext(task)
    }

    private fun consume(task: DownloadTask) {
        synchronized(lifecycleLock) {
            if (!isStarted) {
                lifecycleLock.wait() //阻断消费
            }
            if (task == business.emptyTask) {
                Log.e(TAG, "收到空任务，说明有异常")
                return
            }
            count++
            business.downloadingTasks.add(task)
            startNextDownloadTask(task)
            business.waitingQueue.remove(task)
            Log.w(TAG, "consume: $task , count = $count")
        }
    }

    private fun startNextDownloadTask(downloadTask: DownloadTask) {
        try {
            executor.execute {
                downloadTask.startSync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            downloadTask.notifyError(e)
        }
    }

    private fun registerDownloadState(downloadTask: DownloadTask) {
        downloadTask.getStateObservable().subscribe(object : DisposableObserver<TaskStateModel>() {
            override fun onNext(t: TaskStateModel) {
                handleDownloadState(t)
            }

            override fun onError(e: Throwable) {
                e.printStackTrace()
            }

            override fun onComplete() {
                Log.e(TAG, "DownloadState--onCompleted: ")
            }
        })

    }

    private fun registerDownloadProgress(downloadTask: DownloadTask) {
        downloadTask.getProgressObservable().sample(10, TimeUnit.MILLISECONDS,true).subscribe(object : DisposableObserver<TaskProgressModel>() {
            override fun onNext(t: TaskProgressModel) {
                progressModel.progress = business.getGroupProgress()
                progressSubject.onNext(progressModel)
            }

            override fun onError(e: Throwable) {
                e.printStackTrace()
            }

            override fun onComplete() {
                Log.e(TAG, "DownloadProgress--onComplete: ")
            }
        })
    }

    private fun handleDownloadState(taskStateModel: TaskStateModel) {
        when (taskStateModel.state) {
            State.PREPARE -> {}
            State.START,State.DOWNLOADING -> {
                groupStateModel.state = taskStateModel.state
                stateSubject.onNext(groupStateModel)
            }
            State.SUCCESS, State.STOPPED, State.ERROR -> {
                business.downloadingTasks.remove(taskStateModel.task)
                downloadQueueSubscription?.request(1)
                if (business.downloadingTasks.isEmpty()) {
                    groupStateModel.state = business.getGroupState()
                    Log.w(TAG, "handleDownloadState: group下载结束，通知下载状态::state=$groupStateModel")
                    stateSubject.onNext(groupStateModel)
                }
            }
        }
    }


    fun create() {
        Log.w(TAG, "create: $this")
        synchronized(lifecycleLock) {
            if (isCreated) {
                return
            }
            downloadTaskSubject.toFlowable(BackpressureStrategy.BUFFER).onBackpressureBuffer(Long.MAX_VALUE, {
                    Log.e(TAG, "背压队列溢出，删除最新的数据::超过能处理的最大下载量，会丢失下载任务")
                }, BackpressureOverflowStrategy.DROP_LATEST).onErrorReturn {
                    it.printStackTrace()
                    business.emptyTask
                }.observeOn(Schedulers.io(), false, Flowable.bufferSize() * 2)//buffer是背压缓存队列大小
                .subscribe(object : Subscriber<DownloadTask> {
                    override fun onSubscribe(s: Subscription) {
                        downloadQueueSubscription = s
                        s.request(limit.toLong())
                    }

                    override fun onError(e: Throwable) {
                        e.printStackTrace()
                    }

                    override fun onComplete() {}
                    override fun onNext(downloadTask: DownloadTask) {
                        consume(downloadTask)
                    }
                })
            isCreated = (true)
        }
        Log.w(TAG, "created: $this")
    }

    fun start() {
        Log.w(TAG, "start: $this")
        synchronized(lifecycleLock) {
            if (isStarted) {
                return
            }
            //判断任务是否需要重新下载
            business.allTasks.forEach {
                reDownloadTask(it)
            }
            isStarted = (true)
            lifecycleLock.notify()//通知开始消费
        }
        Log.w(TAG, "started: $this")
    }

    /**
     * 停止下载
     * 只是停止，重新调用start还会继续之前的下载
     */
    fun stop() {
        Log.w(TAG, "stop: $this")
        synchronized(lifecycleLock) {
            //先阻断消费
            isStarted = (false)
            business.downloadingTasks.forEach {
                //停掉任务
                it.stop()
            }
        }
        Log.w(TAG, "stoped: $this")
    }

    /**
     * 停止下载
     * 清空下载任务
     */
    fun destroy() {
        Log.w(TAG, "destroy: $this")
        synchronized(lifecycleLock) {
            stop()
            downloadQueueSubscription?.cancel()
            business.destroyAllTask()
            isCreated = (false)
        }
        Log.w(TAG, "destroyed: $this")
    }

    @JvmOverloads
    fun addTask(
        downloadUrl: String, localFileName: String, weight: Float = 1f, isOnlyCheckLocalFileExit: Boolean = false
    ): DownloadTask {
        synchronized(lifecycleLock) {
            var task = findTask(downloadUrl)
            if (task == null) {
                Log.w(TAG, "addTask::任务不存在准备新建下载任务")
                task = createTask(downloadUrl, localFileName, weight, isOnlyCheckLocalFileExit)
                produce(task)
            } else {
                task.isOnlyCheckLocalFileExit = isOnlyCheckLocalFileExit
                Log.w(TAG, "addTask::下载任务已存在，进入reDownloadTask，判断是否需要重新下载")
                reDownloadTask(task)
            }
            return task
        }
    }

    /***
     * 根据状态判断任务是否需要重新下载
     */
    private fun reDownloadTask(task: DownloadTask) {
        when (task.getState()) {
            State.PREPARE, State.START, State.DOWNLOADING -> {
                Log.w(TAG, "reDownloadTask:在下载流程中，无需重新下载- $task")
            }
            State.SUCCESS -> {
                //处理异常情况，状态成功，文件不存在
                //这种情况发生在：app开着，手动删除本地文件
                if (!File(task.localFileName).exists()) {
                    Log.w(TAG, "reDownloadTask:触发重新下载::本地文件被删除- $task")
                    task.reset(true)
                    produce(task)
                } else {
                    Log.w(TAG, "reDownloadTask:已下载成功，无需重新下载- $task")
                }
            }
            State.STOPPED, State.ERROR -> {
                //重新下载任务
                Log.w(TAG, "reDownloadTask:触发重新下载-- $task")
                task.reset(!task.isTempFileExists())//STOPPED 和 ERROR 也可能删除了本地文件
                produce(task)
            }
            else -> {
                Log.w(TAG, "reDownloadTask:other，无需重新下载- $task")
            }
        }
    }


    private fun createTask(
        downloadUrl: String, localFileName: String, weight: Float = 1f, isOnlyCheckLocalFileExit: Boolean = false
    ): DownloadTask {
        val task = DownloadTask(downloadUrl, localFileName, weight)
        task.isOnlyCheckLocalFileExit = isOnlyCheckLocalFileExit
        business.allTasks.add(task)
        business.isReComputeTotalWeight.getAndSet(true)
        //订阅进度 - 只订阅一次
        registerDownloadProgress(task)
        //订阅下载状态 - 只订阅一次
        registerDownloadState(task)
        Log.w(TAG, "createTask::新建下载任务 $task")
        return task
    }

    fun findTask(url: String): DownloadTask? = business.findTask(url)

    fun getProgressObservable(): Flowable<GroupProgressModel> = progressSubject.toFlowable(BackpressureStrategy.LATEST).onBackpressureBuffer(10000, {
            Log.e(TAG, "背压队列溢出，删除最旧的数据")
        }, BackpressureOverflowStrategy.DROP_OLDEST).sample(100, TimeUnit.MILLISECONDS,true)

    fun getProgress() = progressModel.progress

    fun getStateObservable(): Flowable<GroupStateModel> = stateSubject.toFlowable(BackpressureStrategy.LATEST).onBackpressureBuffer(10000, {
            Log.e(TAG, "背压队列溢出，删除最旧的数据")
        }, BackpressureOverflowStrategy.DROP_OLDEST)

    fun getState() = groupStateModel.state

    override fun toString(): String {
        return "DownloadGroup::${this.hashCode()}(isCreated=$isCreated, isStarted=$isStarted)"
    }


}