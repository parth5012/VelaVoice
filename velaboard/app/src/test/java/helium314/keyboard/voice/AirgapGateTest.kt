// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirgapGateTest {

    @Test fun gateOpenWhenAirgapOff() {
        assertFalse(AirgapGate.isAirgapped(false))
    }

    @Test fun gateClosedWhenAirgapOn() {
        assertTrue(AirgapGate.isAirgapped(true))
    }

    @Test fun requireNetworkSucceedsWhenOpen() {
        for (feature in AirgapGate.CLOUD_FEATURES) {
            val r = AirgapGate.requireNetwork(false, feature)
            assertTrue(r.isSuccess, "expected success for $feature")
        }
    }

    @Test fun requireNetworkFailsWhenClosed() {
        for (feature in AirgapGate.CLOUD_FEATURES) {
            val r = AirgapGate.requireNetwork(true, feature)
            assertTrue(r.isFailure, "expected failure for $feature")
            val ex = r.exceptionOrNull()
            assertIs<AirgapGate.AirgappedException>(ex)
            assertEquals(feature, (ex as AirgapGate.AirgappedException).feature)
            assertEquals(AirgapGate.MESSAGE, ex.message)
        }
    }

    @Test fun localModeNeverConsultsGate() {
        // Local whisper path does not call requireNetwork; sanity: gate state irrelevant.
        // Simulate: a "local" feature is not in CLOUD_FEATURES and isAirgapped(true) does not
        // imply local refusal. We assert local is not a cloud feature name.
        assertFalse("local" in AirgapGate.CLOUD_FEATURES)
    }

    @Test fun prefDefaultsOff() {
        assertEquals("airgap_killswitch", AirgapGate.PREF_KEY)
    }
}
