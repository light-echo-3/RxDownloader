package com.wuzhu.rx.downloader.model

import com.wuzhu.rx.downloader.DownloadGroup

/**
 * @author Hdq on 2021/9/8.
 */
data class GroupStateModel @JvmOverloads constructor(
    var group: DownloadGroup, @Volatile @State var state: Int = 0
) {
    override fun toString(): String {
        return "GroupStateModel::${this.hashCode()}(state=${State.toString(state)})"
    }

    /**
     * 不能删，否则作为map中的key，会重复
     */
    override fun hashCode(): Int {
        return super.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other === this
    }
}