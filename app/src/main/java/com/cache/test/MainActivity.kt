package com.cache.test

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cache.test.net.ApiService
import com.cache.test.net.NetWorkClient

class MainActivity : AppCompatActivity() {

    private val mBtnLogin by lazy { findViewById<Button>(R.id.btn_login) }

    private val mBtnArticle by lazy { findViewById<Button>(R.id.btn_article) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mBtnLogin.setOnClickListener {
            login()
        }
        mBtnArticle.setOnClickListener {
            getArticle()
        }
    }

    /**
     * post 缓存测试
     */
    private fun login() {
        lifecycleScope.launchWhenResumed {
            kotlin.runCatching {
                NetWorkClient.getInstance()
                    .create(ApiService::class.java)
                    .login("AleynText", "Aleyn123")
            }.onFailure {
                it.printStackTrace()
            }

        }
    }

    /**
     * get 缓存测试
     */
    private fun getArticle() {
        lifecycleScope.launchWhenResumed {
            kotlin.runCatching {
                NetWorkClient.getInstance()
                    .create(ApiService::class.java)
                    .getPage(0)
            }.onFailure {
                it.printStackTrace()
            }

        }
    }

}