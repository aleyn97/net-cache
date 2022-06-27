package com.aleyn.cache

/**
 * @author : Aleyn
 * @date : 2022/06/24 19:54
 */
data class CacheStrategy(
    var cacheKey: String = "", //缓存key
    val cacheTime: Long = -1, //过期时间  默认-1 不过期
    @CacheMode.CacheModel val cacheMode: String? = null //缓存模式
) {
    companion object {

        const val CACHE_MODE = "Custom-Cache-Mode"

        const val CACHE_TIME = "Custom-Cache-Time"

        const val CUSTOM_CACHE_KEY = "Custom-Cache-Key"

    }
}