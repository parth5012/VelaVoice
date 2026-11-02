package com.velavoice.sdk

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Shared airgap killswitch gate contract tests (map #211 tickets #233/#255).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirgapGateTest {

    @Test
    fun `pure gate boolean check`() {
        assertTrue(AirgapGate.isAirgapped(true))
        assertFalse(AirgapGate.isAirgapped(false))
    }

    @Test
    fun `fails closed when prefs or context is null`() {
        assertTrue("Null prefs must fail closed (airgap ON)", AirgapGate.isAirgapped(null as SharedPreferences?))
        assertTrue("Null context must fail closed (airgap ON)", AirgapGate.isAirgapped(null as Context?))
    }

    @Test
    fun `reads airgap pref correctly`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("test_airgap", Context.MODE_PRIVATE)

        prefs.edit().putBoolean(AirgapGate.PREF_KEY, false).commit()
        assertFalse(AirgapGate.isAirgapped(prefs))

        prefs.edit().putBoolean(AirgapGate.PREF_KEY, true).commit()
        assertTrue(AirgapGate.isAirgapped(prefs))
    }

    @Test
    fun `context overload reads canonical prefs`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(AirgapGate.PREFS_NAME, Context.MODE_PRIVATE)

        prefs.edit().putBoolean(AirgapGate.PREF_KEY, true).commit()
        assertTrue(AirgapGate.isAirgapped(context))

        prefs.edit().putBoolean(AirgapGate.PREF_KEY, false).commit()
        assertFalse(AirgapGate.isAirgapped(context))
    }
}
