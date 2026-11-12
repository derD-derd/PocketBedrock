package com.pocketbedrock.app

import android.app.Application

class PocketBedrockApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
