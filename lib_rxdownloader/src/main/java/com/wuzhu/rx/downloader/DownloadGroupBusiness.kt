package com.wuzhu.rx.downloader

import android.util.Log
import com.wuzhu.rx.downloader.model.State
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "----DownloadGroup"

/**
 * 业务实现相关代码
 */
class DownloadGroupBusiness {
    /**
     * 这个空任务用户处理异常情况，不会进入下载流程
     */
    val emptyTask = DownloadTask("empty_", "empty__", 0f)

    /***
     * 等待下载的队列
     */
    val waitingQueue = CopyOnWriteArrayList<DownloadTask>()
    val downloadingTasks = CopyOnWriteArrayList<DownloadTask>()
    val allTasks = CopyOnWriteArrayList<DownloadTask>()
    val isReComputeTotalWeight = AtomicBoolean(false)
    private var totalWeight = 0f
        get() {
            if (isReComputeTotalWeight.get()) {
                var result = 0f
                allTasks.forEach { task ->
                    result += task.weight
                }
                field = result
                isReComputeTotalWeight.getAndSet(false)
            }
            return field
        }

    fun getGroupState(): @State Int {
        var groupState = State.PREPARE
        allTasks.forEach {
            val taskState = it.getState()
            if (taskState > groupState) {
                groupState = taskState
            }
        }
        return groupState
    }

    fun getGroupProgress(): Float {
        val totalWeight = totalWeight
        var avgProgress = 0f
        allTasks.forEach { task ->
            avgProgress += task.getProgress() * (task.weight / totalWeight)
        }
        Log.d(TAG, "group--progress-----1: $avgProgress")
        return avgProgress
    }


    /**
     * 当前的所有任务下载完成
     *
     * @return
     */
    fun isAllTaskDownloadSuccess(): Boolean {
        allTasks.forEach {
            if (it.getState() != State.SUCCESS) {
                return false
            }
        }
        return true
    }

    fun findTask(url: String): DownloadTask? {
        allTasks.forEach {
            if (it.downloadUrl == url) {
                return it
            }
        }
        return null
    }


    fun destroyTask(task: DownloadTask) {
        downloadingTasks.remove(task)
        allTasks.remove(task)
        task.destroy()
    }

    fun destroyAllTask() {
        allTasks.forEach {
            it.destroy()
        }
        downloadingTasks.clear()
        allTasks.clear()
    }


}