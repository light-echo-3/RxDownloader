package com.wuzhu.rx.downloader.model

import androidx.annotation.Keep
import com.wuzhu.rx.downloader.DownloadTask

/**
 * @author Hdq on 2021/9/8.
 */
@Keep
data class TaskStateModel @JvmOverloads constructor(
    var task: DownloadTask,
    @Volatile @State var state: Int = 0,
    var exception: Throwable? = null,
) {
    override fun toString(): String {
        return "TaskStateModel::${this.hashCode()}(state=${State.toString(state)})"
    }
}