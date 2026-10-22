// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.share

import android.content.Intent
import android.net.Uri
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareTranscribeActivityTest {

    @Test fun extractSharedUriFromExtraStream() {
        val uri = Uri.parse("content://x/audio.wav")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        assertEquals(uri, ShareTranscribeActivity.extractSharedUri(intent))
    }

    @Test fun extractSharedUriFromData() {
        val uri = Uri.parse("content://x/audio.wav")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            data = uri
        }
        assertEquals(uri, ShareTranscribeActivity.extractSharedUri(intent))
    }

    @Test fun extractSharedUriNullForNonSend() {
        val intent = Intent(Intent.ACTION_VIEW)
        assertNull(ShareTranscribeActivity.extractSharedUri(intent))
    }

    @Test fun extractSharedUriNullWhenNoUri() {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "audio/wav" }
        assertNull(ShareTranscribeActivity.extractSharedUri(intent))
    }
}
