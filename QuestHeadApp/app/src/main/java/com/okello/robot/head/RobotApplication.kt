package com.okello.robot.head

import android.app.Application
import android.util.Log

class RobotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("RobotApp", "Okello Robot application started")
    }
}
