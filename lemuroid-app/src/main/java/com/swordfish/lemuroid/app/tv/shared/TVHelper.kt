package com.swordfish.lemuroid.app.tv.shared

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Environment

object TVHelper {
    fun isSAFSupported(context: Context): Boolean {
        val packageManager = context.packageManager

        val isStandardHardware =
            listOf(
                !packageManager.hasSystemFeature("android.hardware.type.television"),
                !packageManager.hasSystemFeature("android.hardware.type.watch"),
                !packageManager.hasSystemFeature("android.hardware.type.automotive"),
            ).all { it }

        val isNotLegacyStorage =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Environment.isExternalStorageLegacy()

        return isStandardHardware || isNotLegacyStorage
    }

    fun isTV(context: Context): Boolean {
        val packageManager = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return packageManager.hasSystemFeature("android.hardware.type.television") ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
