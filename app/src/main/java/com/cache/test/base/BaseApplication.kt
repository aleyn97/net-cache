package com.cache.test.base

import android.app.Application

/**
 * @author : Aleyn
 * @date : 2022/6/26 : 0:32
 */
class BaseApplication : Application() {

    companion object {
        var _instance: BaseApplication? = null

        fun get(): BaseApplication {
            return _instance!!
        }
    }

    override fun onCreate() {
        super.onCreate()
        _instance = this
    }

}