package com.aleyn.cache

import okhttp3.internal.cache.DiskLruCache
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.io.FileSystem
import okhttp3.internal.threadFactory
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * @author : Aleyn
 * @date : 2022/06/24 19:47
 */
internal object OkHttpHelper {

    /**
     * OkHttp 4.0.0 版本开始用 Kotlin 重构， DiskLruCache 的构造函数被 internal 来修饰了，导致kotlin 无法直接创建，坑爹啊。
     * 不过 Java 可以无视 Kotlin 的 internal 关键字，可以直接过编译期
     * 这里为了版本兼容没有用 Java 过度 ，还是统一反射创建
     */
    internal fun getDiskLruCache(
        fileSystem: FileSystem?,
        directory: File?,
        appVersion: Int,
        valueCount: Int,
        maxSize: Long
    ): DiskLruCache {
        val cls = DiskLruCache::class.java
        return try {
            val runnerClass = Class.forName("okhttp3.internal.concurrent.TaskRunner")
            val constructor = cls.getConstructor(
                FileSystem::class.java,
                File::class.java,
                Int::class.java,
                Int::class.java,
                Long::class.java,
                runnerClass
            )
            constructor.newInstance(
                fileSystem,
                directory,
                appVersion,
                valueCount,
                maxSize,
                TaskRunner.INSTANCE
            )
        } catch (e: Exception) {
            try {
                val constructor = cls.getConstructor(
                    FileSystem::class.java,
                    File::class.java,
                    Int::class.java,
                    Int::class.java,
                    Long::class.java,
                    Executor::class.java
                )
                val executor = ThreadPoolExecutor(
                    0, 1, 60L, TimeUnit.SECONDS,
                    LinkedBlockingQueue(), threadFactory("OkHttp DiskLruCache", true)
                )
                constructor.newInstance(
                    fileSystem,
                    directory,
                    appVersion,
                    valueCount,
                    maxSize,
                    executor
                )
            } catch (e: Exception) {
                throw IllegalArgumentException("Please use okhttp 4.0.0 or later")
            }
        }
    }
}