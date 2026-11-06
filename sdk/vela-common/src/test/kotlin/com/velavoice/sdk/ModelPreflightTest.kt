package com.velavoice.sdk

import android.app.ActivityManager
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Ticket 603/804: RAM pre-flight contract — TDD red first.
 */
@RunWith(RobolectricTestRunner::class)
class ModelPreflightTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    private fun setAvailableRam(mb: Long) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        info.availMem = mb * 1024L * 1024L
        shadowOf(am).setMemoryInfo(info)
    }

    @Test
    fun `gate passes when available ram exceeds minRamMb`() {
        setAvailableRam(4096)
        assertTrue(ModelPreflight.hasEnoughRam(context, 2048))
    }

    @Test
    fun `gate fails when available ram is below minRamMb`() {
        setAvailableRam(1024)
        assertFalse(ModelPreflight.hasEnoughRam(context, 2048))
    }

    @Test
    fun `gate is a no-op for minRamMb`() {
        setAvailableRam(0)
        assertTrue(
            "minRamMb<=0 means no gate — must pass regardless of device",
            ModelPreflight.hasEnoughRam(context, 0)
        )
    }
}
