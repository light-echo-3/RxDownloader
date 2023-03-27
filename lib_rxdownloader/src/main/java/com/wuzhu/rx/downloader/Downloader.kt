package com.wuzhu.rx.downloader

import android.util.Log
import androidx.annotation.Keep
import com.wuzhu.rx.downloader.exceptions.DownloadException
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * @author Hdq on 2021/3/10.
 */
@Keep
object Downloader {
    private const val TAG = "----Downloader"
    private val downloadGroups = ConcurrentHashMap<String, DownloadGroup>()
    var client: OkHttpClient? = null

    @JvmStatic
    fun init(client: OkHttpClient?) {
        Log.d(TAG, "init: ")
        this.client = client
    }

    /**
     * 最大并发下载数量
     *
     * @param limit
     * @return
     */
    @JvmOverloads
    @JvmStatic
    @Synchronized
    fun createDownloadGroup(key: String, limit: Int = 5): DownloadGroup {
        if (downloadGroups.containsKey(key)) {
            throw DownloadException("key重复：：以key=$key 创建的任务已经存在")
        }
        val downloadGroup = DownloadGroup(limit)
        downloadGroup.create(key, client)
        downloadGroups[key] = downloadGroup
        return downloadGroup
    }

    @JvmStatic
    fun findDownloadGroup(key: String): DownloadGroup? {
        return downloadGroups[key]
    }

    @JvmOverloads
    @JvmStatic
    @Synchronized
    fun findOrCreateDownloadGroup(key: String, limit: Int = 5): DownloadGroup {
        var downloadGroup = downloadGroups[key]
        downloadGroup ?: run { downloadGroup = createDownloadGroup(key, limit) }
        return downloadGroup!!
    }

    @JvmStatic
    fun isExistDownloadGroup(key: String): Boolean {
        return downloadGroups.containsKey(key)
    }

    @JvmStatic
    fun getDownloadGroups() = downloadGroups as Map<String, DownloadGroup>

    @JvmOverloads
    @JvmStatic
    @Synchronized
    fun startDownloadGroup(key: String, limit: Int = 5): DownloadGroup {
        val downloadGroup = findOrCreateDownloadGroup(key, limit)
        downloadGroup.start()
        return downloadGroup
    }

    @JvmStatic
    @Synchronized
    fun stopDownloadGroup(key: String) {
        findDownloadGroup(key)?.stop()
    }


    @JvmStatic
    @Synchronized
    fun destroyAllGroup() {
        downloadGroups.forEach {
            it.value.destroy()
        }
        downloadGroups.clear()
    }

    @JvmStatic
    @Synchronized
    fun destroyGroup(key: String) {
        findDownloadGroup(key)?.destroy()
        downloadGroups.remove(key)
    }

    @JvmStatic
    fun deleteTempFile(localFile: String): Boolean {
        val tempFile = File(DownloadTask.getTempFileName(localFile))
        if (tempFile.exists()) {
            return tempFile.delete()
        }
        return false
    }

}