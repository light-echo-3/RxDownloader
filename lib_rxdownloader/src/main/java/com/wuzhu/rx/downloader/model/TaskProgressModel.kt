package com.wuzhu.rx.downloader.model

import androidx.annotation.Keep
import com.wuzhu.rx.downloader.DownloadTask

/**
 * @author Hdq on 2021/9/8.
 */
@Keep
data class TaskProgressModel @JvmOverloads constructor(
    var task: DownloadTask,
    @Volatile var progress: Float = 0f, //范围 0 - 100
) {
}