package com.wuzhu.rx.downloader.model

import com.wuzhu.rx.downloader.DownloadTask

/**
 * @author Hdq on 2021/9/8.
 */
data class TaskStateModel @JvmOverloads constructor(
    var task: DownloadTask,
    @Volatile @State var state: Int = 0,
    var exception: Exception? = null,
) {
    override fun toString(): String {
        return "TaskStateModel::${this.hashCode()}(state=${State.toString(state)})"
    }
}