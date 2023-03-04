package com.wuzhu.rx.downloader.model

import androidx.annotation.IntDef

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE
)
@IntDef(State.PREPARE, State.START, State.DOWNLOADING, State.SUCCESS, State.STOPPED, State.ERROR)
@Retention(AnnotationRetention.SOURCE)
annotation class State {

    companion object {
        const val PREPARE = 10
        const val START = 200
        const val DOWNLOADING = 600
        const val SUCCESS = 2000
        const val STOPPED = 30000
        const val ERROR = 600000

        @JvmStatic
        fun toString(@State state: Int?): String {
            return when (state) {
                PREPARE -> "PREPARE"
                START -> "START"
                DOWNLOADING -> "DOWNLOADING"
                SUCCESS -> "SUCCESS"
                STOPPED -> "STOPPED"
                ERROR -> "ERROR"
                else -> "/"
            }
        }
    }
}