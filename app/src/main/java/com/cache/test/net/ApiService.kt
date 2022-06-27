package com.cache.test.net

import com.aleyn.cache.CacheMode
import com.aleyn.cache.CacheStrategy
import com.cache.test.net.bean.ArticleBean
import retrofit2.http.*

/**
 * @author : Aleyn
 * @date : 2022/06/23 14:13
 */
interface ApiService {

    @FormUrlEncoded
    @POST("user/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Header(CacheStrategy.CACHE_MODE) cacheMode: String = CacheMode.READ_CACHE_NETWORK_PUT,
        @Header(CacheStrategy.CACHE_TIME) cacheTime: String = "10"
    ): BaseResponse<Any>


    @Headers(
        "${CacheStrategy.CACHE_TIME}:-1",
        "${CacheStrategy.CACHE_MODE}:${CacheMode.READ_CACHE_NETWORK_PUT}"
    )
    @GET("article/list/{page}/json")
    suspend fun getPage(@Path("page") page: Any): BaseResponse<Page<ArticleBean>>

}