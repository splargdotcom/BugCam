package com.splarg.bugcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Permissions {
    const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    fun required(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37) add(LOCAL_NETWORK)
    }
    fun missing(context: Context) = required().filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
}
