package com.wuzhu.rx.downloader.model

import androidx.annotation.Keep
import com.wuzhu.rx.downloader.DownloadGroup

/**
 * @author Hdq on 2021/9/8.
 * 要作为key使用，不要用data class，如果用，需要重写 hashCode和equals方法
 */
@Keep
class GroupProgressModel @JvmOverloads constructor(
    var group: DownloadGroup,
    @Volatile var progress: Float = 0f,//范围 0 - 100
) {
}