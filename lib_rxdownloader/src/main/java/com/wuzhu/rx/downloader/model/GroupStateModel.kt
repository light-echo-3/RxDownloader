package com.wuzhu.rx.downloader.model

import androidx.annotation.Keep
import com.wuzhu.rx.downloader.DownloadGroup

/**
 * @author Hdq on 2021/9/8.
 * 要作为key使用，不要用data class，如果用，需要重写 hashCode和equals方法
 */
@Keep
class GroupStateModel @JvmOverloads constructor(
    var group: DownloadGroup,
    @Volatile @State var state: Int = 0,
    var exception: Throwable? = null,
) {
    override fun toString(): String {
        return "GroupStateModel::${this.hashCode()}(state=${State.toString(state)})"
    }
}