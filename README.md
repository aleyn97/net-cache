# NetCache

Network caching based on okhttp and retrofit

基于 Retrofit 和 OkHttp 的缓存库，用OkHttp 的拦截器做读取存储操作，Retrofit 的动态代理模式有相当高的解解耦性， 利用Retrofit 提供的 @Header()
、@Headers() 注解来定义缓存策略、有效时间以及自定义Key。

# 引入方式

- setting.gradle

``` grovvy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
       ...
        maven { url "https://jitpack.io" }
    }
}
```

- build.gradle

``` grovvy
dependencies {
    implementation 'com.github.AleynP:net-cache:1.0.0'
}
```

# 缓存策略

- ONLY_NETWORK ：只请求网络，不加缓存
- ONLY_CACHE ：只读取缓存(没有缓存抛出异常)
- NETWORK_PUT_CACHE : 先请求网络，再写入缓存
- READ_CACHE_NETWORK_PUT ：先读取缓存，如果缓存失效再请求网络更新缓存
- NETWORK_PUT_READ_CACHE ：先请求网络，网络请求失败使用缓存  (未过期缓存)

# 添加拦截器

```kotlin
OkHttpClient.Builder()
    .addInterceptor(CacheInterceptor(CacheManager(cacheDir, maxSize)))
    .build()
```

# 全局配置

``` kotlin
CacheManager.setCacheModel(CacheMode.ONLY_NETWORK)// 设置全局缓存模式 (默认 ONLY_NETWORK)
            .setCacheTime(15 * 1000) // 设置全局 过期时间 (毫秒)
            .useExpiredData(true)// 缓存过期时是否继续使用，仅对 ONLY_CACHE 生效
```

# 单独指定

- 方式一： 使用 @Header() 注解,声明成参数

``` kotlin 
    @FormUrlEncoded
    @POST("user/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Header(CacheStrategy.CACHE_MODE) cacheMode: String = CacheMode.READ_CACHE_NETWORK_PUT, // 缓存模式
        @Header(CacheStrategy.CACHE_TIME) cacheTime: String = "10" // 有效时长（秒）
    ): BaseResponse<Any>
```

- 方式二：使用 @Headers() 注解，注解到方法上

``` kotlin 
    @Headers(
        "${CacheStrategy.CACHE_TIME}:10",
        "${CacheStrategy.CACHE_MODE}:${CacheMode.READ_CACHE_NETWORK_PUT}"
    )
    @GET("article/list/{page}/json")
    suspend fun getPage(@Path("page") page: Any): BaseResponse<Page<ArticleBean>>
```

# 注意事项

仅支持 Okhttp 4.0.0 以上版本，也就是用Kotlin 重构过的版本，目前 Retrofit 最新版本 2.9.0 ，默认使用的是 Okhttp -> 3.14.9, 在引入
retrofit 依赖后要再 指定下 Okhttp 4.0.0 及以上版本版本
