package com.wuzhu.rx.downloader.model

import com.wuzhu.rx.downloader.DownloadGroup

/**
 * @author Hdq on 2021/9/8.
 */
data class GroupProgressModel @JvmOverloads constructor(
    var group: DownloadGroup,
    @Volatile var progress: Float = 0f,//范围 0 - 100
) {

    override fun hashCode(): Int {
        return super.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return this === other
    }
}