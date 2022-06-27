package com.cache.test.net

/**
 * @author : Aleyn
 * @date : 2022/06/16 12:05
 */
data class BaseResponse<T>(
    val errorMsg: String,
    val errorCode: Int,
    val data: T?
)

data class Page<T>(
    val curPage: Int,
    val datas: List<T>,
)