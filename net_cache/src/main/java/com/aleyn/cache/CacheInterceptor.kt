package com.aleyn.cache

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.EMPTY_RESPONSE
import okhttp3.internal.cache.CacheRequest
import okhttp3.internal.closeQuietly
import okhttp3.internal.discard
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.RealResponseBody
import okio.Buffer
import okio.Source
import okio.buffer
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/**
 * @author : Aleyn
 * @date : 2022/06/23 14:45
 */
class CacheInterceptor(
    private val mCache: ICache
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val initialRequest = chain.request()
        val strategy = CacheUtil.getCacheStrategy(initialRequest)
        val newRequest = initialRequest.rmCacheHeader()

        if (strategy == null) return chain.proceed(newRequest)

        // ONLY_NETWORK 直接请求网络
        if (strategy.cacheMode == CacheMode.ONLY_NETWORK) return chain.proceed(newRequest)

        // ONLY_CACHE 只读取缓存
        if (strategy.cacheMode == CacheMode.ONLY_CACHE) {
            // 只读缓存模式,缓存为空,返回错误响应
            return (if (CacheManager.useExpiredData) mCache.getCache(strategy.cacheKey, newRequest)
            else redCache(strategy, newRequest)) ?: Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(HttpURLConnection.HTTP_GATEWAY_TIMEOUT)
                .message("no cached data")
                .body(EMPTY_RESPONSE)
                .sentRequestAtMillis(-1L)
                .receivedResponseAtMillis(System.currentTimeMillis())
                .build()
        }

        //先取缓存再取网络
        if (strategy.cacheMode == CacheMode.READ_CACHE_NETWORK_PUT) {
            val cacheResponse = redCache(strategy, newRequest)
            if (cacheResponse != null) return cacheResponse
        }

        try {
            val response = chain.proceed(newRequest)
            if (response.isSuccessful) {
                return cacheWritingResponse(mCache.putCache(strategy.cacheKey, response), response)
            }
            if (strategy.cacheMode == CacheMode.NETWORK_PUT_READ_CACHE) {
                return redCache(strategy, newRequest) ?: response
            }
            return response
        } catch (e: Throwable) {
            if (strategy.cacheMode == CacheMode.NETWORK_PUT_READ_CACHE) {
                return redCache(strategy, newRequest) ?: throw e
            }
            throw e
        }
    }

    /**
     * 读取有效缓存(未过期)
     */
    @Throws(IOException::class)
    private fun redCache(strategy: CacheStrategy, request: Request): Response? {
        val cacheResponse = mCache.getCache(strategy.cacheKey, request)
        if (cacheResponse != null) {
            val responseMillis = cacheResponse.receivedResponseAtMillis
            val now = System.currentTimeMillis()
            if (strategy.cacheTime == -1000L || now - responseMillis <= strategy.cacheTime) {
                return cacheResponse
            } else {
                cacheResponse.body?.closeQuietly()
            }
        }
        return null
    }


    private fun Request.rmCacheHeader(): Request {
        return newBuilder()
            .removeHeader(CacheStrategy.CACHE_MODE)
            .removeHeader(CacheStrategy.CACHE_TIME)
            .removeHeader(CacheStrategy.CUSTOM_CACHE_KEY)
            .build()
    }


    @Throws(IOException::class)
    private fun cacheWritingResponse(cacheRequest: CacheRequest?, response: Response): Response {
        if (cacheRequest == null) return response
        val cacheBodyUnbuffered = cacheRequest.body()

        val source = response.body!!.source()
        val cacheBody = cacheBodyUnbuffered.buffer()

        val cacheWritingSource = object : Source {
            private var cacheRequestClosed = false

            @Throws(IOException::class)
            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead: Long
                try {
                    bytesRead = source.read(sink, byteCount)
                } catch (e: IOException) {
                    if (!cacheRequestClosed) {
                        cacheRequestClosed = true
                        cacheRequest.abort()
                    }
                    throw e
                }

                if (bytesRead == -1L) {
                    if (!cacheRequestClosed) {
                        cacheRequestClosed = true
                        cacheBody.close()
                    }
                    return -1
                }

                sink.copyTo(cacheBody.buffer, sink.size - bytesRead, bytesRead)
                cacheBody.emitCompleteSegments()
                return bytesRead
            }

            override fun timeout() = source.timeout()

            @Throws(IOException::class)
            override fun close() {
                if (!cacheRequestClosed &&
                    !discard(ExchangeCodec.DISCARD_STREAM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                ) {
                    cacheRequestClosed = true
                    cacheRequest.abort()
                }
                source.close()
            }
        }

        val contentType = response.header("Content-Type")
        val contentLength = response.body!!.contentLength()
        return response.newBuilder()
            .body(RealResponseBody(contentType, contentLength, cacheWritingSource.buffer()))
            .build()
    }

}