package com.cache.test.net

import com.aleyn.cache.CacheInterceptor
import com.aleyn.cache.CacheManager
import com.aleyn.cache.CacheMode
import com.cache.test.base.BaseApplication
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit


/**
 * @author : Aleyn
 * @date : 2021/03/31  18:17
 */
class NetWorkClient private constructor() {

    private var retrofit: Retrofit

    private val timeOut = 8L

    companion object {

        const val BASE_URL = "https://www.wanandroid.com/"

        @JvmStatic
        fun getInstance() = SingletonHolder.INSTANCE

    }

    private object SingletonHolder {
        val INSTANCE = NetWorkClient()
    }

    init {
        retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okhttpClient())
            .build()
    }

    private fun okhttpClient(): OkHttpClient {
        CacheManager.setCacheModel(CacheMode.READ_CACHE_NETWORK_PUT)// 设置全局缓存模式
            .setCacheTime(15 * 1000) // 设置全局 过期时间 (毫秒)
            .useExpiredData(true)// 缓存过期时是否继续使用，仅对 ONLY_CACHE 生效

        return OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(timeOut, TimeUnit.SECONDS)
            .readTimeout(timeOut, TimeUnit.SECONDS)
            .writeTimeout(timeOut, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .addInterceptor(CacheInterceptor(CacheManager(BaseApplication.get().cacheDir)))
            .build()
    }

    fun <T> create(cls: Class<T>): T =
        retrofit.create(cls) ?: throw RuntimeException("Api service is null!")

}