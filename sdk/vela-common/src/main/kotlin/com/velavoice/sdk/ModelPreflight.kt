package com.velavoice.sdk

import android.app.ActivityManager
import android.content.Context

/**
 * RAM pre-flight gate (Ticket 603 / 804, maps offline-model-downloader & #290):
 * refuses to start a download or load a model whose registry `minRamMb`
 * exceeds the device's currently available RAM. Registry rows carry the
 * hint (`ModelDefinition.minRamMb`); this object owns the device check so
 * both app (velavoice) and keyboard (velaboard) share one contract.
 */
object ModelPreflight {

    /**
     * @return true when [minRamMb] <= 0 (no gate) or the device's
     * [ActivityManager.MemoryInfo.availMem] is at least [minRamMb] MB.
     */
    fun hasEnoughRam(context: Context, minRamMb: Int): Boolean {
        if (minRamMb <= 0) return true
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)
        return availMb >= minRamMb
    }
}
