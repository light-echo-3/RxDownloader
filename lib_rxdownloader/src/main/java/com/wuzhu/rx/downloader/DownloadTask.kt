package com.wuzhu.rx.downloader

import android.util.Log
import androidx.annotation.Keep
import com.wuzhu.rx.downloader.exceptions.DownloadException
import com.wuzhu.rx.downloader.model.State
import com.wuzhu.rx.downloader.model.TaskProgressModel
import com.wuzhu.rx.downloader.model.TaskStateModel
import com.wuzhu.rx.downloader.utils.MD5Utils
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author Hdq on 2021/3/9.
 */
@Keep
class DownloadTask {

    companion object {
        private const val TAG = "---DownloadTask"

        @JvmStatic
        internal fun getTempFileName(localFileName: String): String {
            return "$localFileName.tmp"
        }
    }

    private val subscriptions = CompositeDisposable()
    private val progressSubject = BehaviorSubject.create<TaskProgressModel>()

    /**
     * 这里提前new出来，避免使用时候频繁创建对象
     * 范围 0 - 100
     */
    private val taskProgressModel = TaskProgressModel(this)
    private val stateSubject = BehaviorSubject.create<TaskStateModel>()

    /**
     * 这里提前new出来，避免使用时候频繁创建对象
     */
    private val taskStateModel = TaskStateModel(this)
    private var downloadCall: Call? = null
    private val isStop = AtomicBoolean(false)
    lateinit var downloadUrl: String
    lateinit var localFileName: String
    var weight: Float = 1f//记录该任务的权重值.用于多个任务在一起时候计算总进度

    /**
     * 是否仅检查本地文件是否存在
     * true:仅检查本地文件是否存在，如果存在，就不下载
     * fixme 鸡肋功能，可改成md5检测
     */
    var md5: String? = null

    var client: OkHttpClient? = null
        get() {
            field ?: run {
                field = OkHttpClient.Builder().connectTimeout(15 * 1000, TimeUnit.MILLISECONDS).build()
            }
            return field
        }

    private val mHeaders = mutableMapOf<String, String>()

    fun create(downloadUrl: String, localFileName: String, weight: Float = 1f) {
        if (downloadUrl.isBlank()) {
            throwException("DownloadTask: 传入的url为空，localFileName = $localFileName")
        }
        if (localFileName.isBlank()) {
            throwException("DownloadTask: 传入的本地文件路径为空，downloadUrl = $downloadUrl")
        }
        if (weight <= 0) {
            throwException("DownloadTask: fileLength不能<=0")
        }
        this.downloadUrl = downloadUrl
        this.localFileName = localFileName
        this.weight = weight
        reset(true)
    }


    /**
     * 整段代码是同步执行的
     * 不能直接在主线程执行，如果主线程执行，请调用[DownloadTask.start]
     */
    fun startSync() {
        Log.w(TAG, "task--start: $this")
        try {
            isStop.getAndSet(false)
            notifyStart()
            checkDownload(downloadUrl, localFileName)
            Log.w(TAG, "task--任务执行结束: $this")
        } catch (e: Exception) {
            notifyError(e)
            Log.e(TAG, "task--任务执行错误: $this", e)
        }
    }

    /**
     * 可在主线程直接调用
     */
    fun start() {
        Schedulers.io().scheduleDirect { startSync() }
    }

    /***
     * 要支持异步调用，不能加锁，否则call?.cancel()不能立即生效
     */
    fun stop() {
        Log.e(TAG, "task--stop: $this")
        downloadCall?.let {
            if (!it.isCanceled) {
                it.cancel()
                Log.e(TAG, "task--stop--cancel--downloadCall: $this")
            }
        }
        isStop.getAndSet(true)
    }


    fun destroy() {
        stop()
        subscriptions.dispose()
    }


    private fun checkDownload(downloadUrl: String, localFileName: String) {
        val localFile = File(localFileName)
        md5?.let {
            if (localFile.exists() && localFile.length() > 0 && it.equals(MD5Utils.calculateMD5(localFile), true)) {
                notifySuccess()
                return
            }
        }
        download(downloadUrl, localFileName)
    }


    fun reset(isResetProgress: Boolean) {
        if (isResetProgress) {
            taskProgressModel.progress = 0f
        }
        progressSubject.onNext(taskProgressModel)
        notifyPrepare()
    }

    private fun download(downloadUrl: String, localFileName: String) {
        var inputStream: InputStream? = null
        var savedFile: RandomAccessFile? = null
        var downloadedLength: Long = 0 //记录已经下载的文件长度
        val tempFile = File(getTempFileName(localFileName)) //临时文件
        if (tempFile.exists()) {
            //如果文件存在的话，得到文件的大小
            downloadedLength = tempFile.length()
        }
        /**
         * HTTP请求是有一个Header的，里面有个Range属性是定义下载区域的，它接收的值是一个区间范围，
         * 比如：Range:bytes=0-10000。这样我们就可以按照一定的规则，将一个大文件拆分为若干很小的部分，
         * 然后分批次的下载，每个小块下载完成之后，再合并到文件中；这样即使下载中断了，重新下载时，
         * 也可以通过文件的字节长度来判断下载的起始点，然后重启断点续传的过程，直到最后完成下载过程。
         */
        val requestBuilder = Request.Builder()
        addCustomHeaders(requestBuilder)
        val request = requestBuilder.addHeader("RANGE", "bytes=$downloadedLength-") //断点续传要用到的，指示下载的区间
            .url(downloadUrl).build()
        try {
            downloadCall = client!!.newCall(request)
            val response = downloadCall!!.execute()
            if (response.code() !in 200 until 300) {//处理错误码
                notifyError(Exception(response.body()?.string() ?: "${response.code()}"))
                return
            }
            response.body() ?: let {
                notifyError(Exception("server返回的数据是空的"))
                return
            }
            response.body()?.let { responseBody ->
                val breakpointContentLength = responseBody.contentLength()
                if (isFileLengthUnKnow(breakpointContentLength)) {//文件长度未知，不支持断点
                    Log.e(TAG, "download--length: 文件长度未知，不支持断点")
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                    downloadedLength = 0
                }
                val totalFileLength = downloadedLength + breakpointContentLength
                Log.e(TAG, "download--length: 已经下载的length：$downloadedLength")
                Log.e(TAG, "download--length: server返回的content-length=$breakpointContentLength")
                Log.e(TAG, "download--length: 计算的文件总大小：$totalFileLength")
                val localFile = File(localFileName)
                if (localFile.exists() && !isFileLengthUnKnow(breakpointContentLength) && localFile.length() != totalFileLength) {
                    Log.e(TAG, "download: 文件有错误,删除本地文件（一次错误检测）localFile=$localFile")
                    localFile.delete()
                }
                inputStream = responseBody.byteStream()
                //判断文件夹是否存在，不存在创建文件夹
                createTempFileDir(tempFile)
                savedFile = RandomAccessFile(tempFile, "rw")
                savedFile?.seek(downloadedLength) //跳过已经下载的字节
                val buffer = ByteArray(1024)
                var total = 0
                var len = 0
                notifyDownloading()
                while (!isStop.get() && inputStream?.read(buffer).also { len = it ?: -1 } != -1) {
                    total += len
                    savedFile?.write(buffer, 0, len)
                    //计算已经下载的百分比
                    if (isFileLengthUnKnow(breakpointContentLength)) {
                        taskProgressModel.progress = 100f
                    } else {
                        taskProgressModel.progress = (total + downloadedLength) / totalFileLength.toFloat() * 100f
                    }
                    progressSubject.onNext(taskProgressModel)
                    Log.d("----Download", "progress--0: " + taskProgressModel.progress)
                }
                response.body()?.close()
                val progress = taskProgressModel.progress
                if (progress > 100) {
                    throwException(
                        "progress =$progress,下载的文件大小 > server给的大小,localFileLength=${tempFile.length()},serverFileLength=$totalFileLength",
                        localFile,
                        tempFile,
                    )
                }
                if (progress == 100f) {
                    val success = tempFile.renameTo(File(localFileName)) //重命名
                    if (success) {
                        notifySuccess()
                    } else {
                        notifyError(Exception("重命名失败"))
                    }
                } else if (isStop.get()) {
                    Log.e(TAG, "task--stopped(没有异常): $this")
                    notifyStop(null)
                } else {
                    throwException(
                        "不是stop，且progress!=100 (说明本地文件的长度!=server文件的长度),localFileLength=${tempFile.length()},serverFileLength=$totalFileLength",
                        localFile,
                        tempFile,
                    )
                }
            }
        } catch (e: Exception) {
            if (e is FileNotFoundException) {
                Log.e(TAG, "发生严重错误！！！写本地文件失败")
            }
            handleCancelException(e)
        } finally {
            try {
                savedFile?.close()
                inputStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun throwException(errorMessage: String, localFile: File? = null, tempFile: File? = null) {
        localFile?.takeIf {
            it.exists()
        }?.delete()
        tempFile?.takeIf {
            it.exists()
        }?.delete()
        throw DownloadException(errorMessage)
    }

    private fun addCustomHeaders(requestBuilder: Request.Builder) {
        mHeaders.forEach {
            requestBuilder.addHeader(it.key, it.value)
        }
    }

    @Suppress("unused")
    fun addHeader(key: String, value: String) {
        mHeaders[key] = value
    }

    fun addHeaders(headers: Map<String, String>) {
        mHeaders.putAll(headers)
    }

    private fun handleCancelException(e: Exception) {
        //调用call.cancel会抛出异常
        if (isStop.get()) {
            Log.e(TAG, "task--stopped(有异常)--调用call.cancel，进入异常处理逻辑: $this", e)
            notifyStop(e)
        } else {
            notifyError(e)
        }
    }

    private fun createTempFileDir(tempFile: File) {
        tempFile.parentFile?.let {
            if (it.isFile) {
                Log.e(TAG, "发生严重错误！！！删除本地文件！！！期望是个文件夹，实际是个文件：dir=$it")
                it.delete()
            }
            if (!it.exists()) {
                if (it.mkdirs()) {
                    Log.e(TAG, "发生严重错误！！！创建要下载的文件夹失败：dir=$it")
                }
            }
        }
    }

    private fun isFileLengthUnKnow(contentLength: Long): Boolean {
        return contentLength < 0
    }

    fun getProgressObservable(): Observable<TaskProgressModel> = progressSubject.toSerialized().doOnSubscribe {
        subscriptions.add(it)
    }.onErrorReturn {
        it.printStackTrace()
        taskProgressModel
    }

    fun getProgress(): Float = taskProgressModel.progress
    fun getStateObservable(): Observable<TaskStateModel> = stateSubject.toSerialized().doOnSubscribe {
        subscriptions.add(it)
    }.onErrorReturn {
        it.printStackTrace()
        taskStateModel.state = State.ERROR
        taskStateModel.exception = it
        taskStateModel
    }

    fun getState(): @State Int = taskStateModel.state

    override fun toString(): String {
        return "DownloadTask::${this.hashCode()}(isStop='$isStop',state='$taskStateModel',downloadUrl='$downloadUrl', localFileName='$localFileName')"
    }

    fun isTempFileExists(): Boolean {
        return File(getTempFileName(localFileName)).exists()
    }

    private fun notifyPrepare() {
        taskStateModel.state = State.PREPARE
        stateSubject.onNext(taskStateModel)
        Log.w(TAG, "notifyPrepare: $this")
    }

    private fun notifyStart() {
        taskStateModel.state = State.START
        stateSubject.onNext(taskStateModel)
        Log.w(TAG, "notifyDownloading: $this")
    }

    private fun notifyDownloading() {
        taskStateModel.state = State.DOWNLOADING
        stateSubject.onNext(taskStateModel)
        Log.w(TAG, "notifyDownloading: $this")
    }

    private fun notifySuccess() {
        taskProgressModel.progress = 100f
        progressSubject.onNext(taskProgressModel)
        taskStateModel.state = State.SUCCESS
        stateSubject.onNext(taskStateModel)
        Log.w(TAG, "notifySuccess: $this")
    }

    private fun notifyStop(e: Exception?) {
        taskStateModel.state = State.STOPPED
        stateSubject.onNext(taskStateModel)
        Log.w(TAG, "notifyStop: $this", e)
    }

    fun notifyError(e: Exception?) {
        Log.w(TAG, "notifyError: $this", e)
        taskStateModel.exception = e
        taskStateModel.state = State.ERROR
        stateSubject.onNext(taskStateModel)
        isStop.getAndSet(true)
    }


}