package com.aleyn.cache

import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.cache.CacheRequest
import java.io.IOException

/**
 * @author : Aleyn
 * @date : 2022/06/23 16:41
 */
interface ICache {

    @Throws(IOException::class)
    fun getCache(cacheKey: String?, request: Request): Response?

    @Throws(IOException::class)
    fun putCache(cacheKey: String?, response: Response): CacheRequest?

    @Throws(IOException::class)
    fun remove(cacheKey: String?)

    @Throws(IOException::class)
    fun removeAll()

}