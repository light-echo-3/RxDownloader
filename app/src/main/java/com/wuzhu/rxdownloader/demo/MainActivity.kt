package com.wuzhu.rxdownloader.demo

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.wuzhu.rx.downloader.Downloader

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Downloader.findOrCreateDownloadGroup("test")
    }
}