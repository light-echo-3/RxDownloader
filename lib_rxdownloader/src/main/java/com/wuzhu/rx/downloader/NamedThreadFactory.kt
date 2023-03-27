package com.wuzhu.rx.downloader

import androidx.annotation.Keep
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author Hdq on 2021/3/10.
 */
@Keep
class NamedThreadFactory(name: String) : ThreadFactory {
    private val poolNumber = AtomicInteger(1)
    private val group: ThreadGroup?
    private val threadNumber = AtomicInteger(1)
    private val namePrefix: String

    init {
        val s = System.getSecurityManager()
        group = if (s != null) s.threadGroup else Thread.currentThread().threadGroup
        namePrefix = "pool-" + poolNumber.getAndIncrement() + "-" + name + "-"
    }

    override fun newThread(r: Runnable): Thread {
        val t = Thread(
            group, r, namePrefix + threadNumber.getAndIncrement(), 0
        )
        if (t.isDaemon) t.isDaemon = false
        if (t.priority != Thread.NORM_PRIORITY) t.priority = Thread.NORM_PRIORITY
        return t
    }
}