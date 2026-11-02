// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.share

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes audio shared into [ShareTranscribeActivity] into 16 kHz mono 16-bit
 * little-endian PCM, which is what whisper expects (F16).
 *
 * The previous implementation fed openInputStream(uri).readBytes() straight to
 * the transcriber, so a WAV with its 44-byte RIFF header, any compressed .mp3 /
 * .m4a container, or 44.1 kHz stereo audio was transcribed as garbage.
 *
 * Compressed containers are decoded through MediaExtractor/MediaCodec (runs on
 * device for the audio-mime intents this activity declares); WAV files are parsed
 * directly in Kotlin: RIFF chunks walked, PCM deinterleaved, downmixed,
 * low-passed (to avoid aliasing on decimation) and resampled to 16 kHz.
 */
object ShareAudioDecoder {

    private const val TARGET_RATE = 16000

    /** 20 minutes at 16 kHz — bounds memory and transcribe time for shared files. */
    private const val MAX_FRAMES = 20 * 60 * TARGET_RATE

    /** Safety net so a corrupt/looping MediaCodec stream cannot hang the dialog. */
    private const val DECODE_DEADLINE_MS = 15_000L

    /**
     * Test seam: MediaCodec cannot run on the Robolectric JVM. Production value
     * is the real decoder below; tests must restore the previous value.
     */
    internal var compressedDecoder: (Context, Uri) -> ByteArray = { context, uri ->
        decodeCompressed(context, uri)
    }

    fun decodeTo16kMono(context: Context, uri: Uri): ByteArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not read the shared audio")
        if (isWav(bytes)) return decodeWav(bytes)
        return compressedDecoder(context, uri)
    }

    private fun isWav(bytes: ByteArray) =
        bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE"

    /** WAV path: walk RIFF chunks, take the first fmt+data pair, convert to 16 kHz mono. */
    internal fun decodeWav(bytes: ByteArray): ByteArray {
        if (!isWav(bytes)) throw IllegalArgumentException("Not a WAV file")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12)
        var fmt: WavFormat? = null
        var data: ByteArray? = null
        while (buf.remaining() >= 8) {
            val id = String(ByteArray(4) { buf.get() })
            if (buf.remaining() < 4) break
            val size = buf.int
            val chunkEnd = (buf.position() + size).coerceAtMost(buf.limit())
            when (id) {
                "fmt " -> {
                    if (size < 16 || buf.remaining() < 16) throw IllegalArgumentException("Invalid WAV fmt chunk")
                    val audioFormat = buf.short.toInt() and 0xFFFF
                    val channels = buf.short.toInt()
                    val sampleRate = buf.int
                    buf.position(buf.position() + 6) // byteRate (4) + blockAlign (2)
                    val bitsPerSample = buf.short.toInt()
                    fmt = WavFormat(audioFormat, channels, sampleRate, bitsPerSample)
                }
                "data" -> if (data == null) {
                    val actual = (buf.position() + size).coerceAtMost(buf.limit()) - buf.position()
                    data = ByteArray(actual) { buf.get() }
                }
                else -> {}
            }
            buf.position(chunkEnd)
            if (size % 2 == 1 && buf.remaining() > 0) buf.position(buf.position() + 1) // pad byte
        }
        val format = fmt ?: throw IllegalArgumentException("WAV file has no fmt chunk")
        val payload = data ?: throw IllegalArgumentException("WAV file has no data chunk")
        if (payload.isEmpty()) throw IllegalArgumentException("Shared WAV contains no audio")
        return to16kMonoPcm(pcmToShorts(payload, format), format.channels, format.sampleRate)
    }

    private class WavFormat(val audioFormat: Int, val channels: Int, val sampleRate: Int, val bitsPerSample: Int)

    /** Deinterleave raw sample bytes into shorts. 8-bit PCM is unsigned, centred at 128. */
    private fun pcmToShorts(data: ByteArray, format: WavFormat): ShortArray {
        val isFloat = format.audioFormat == 3
        val isPcm = format.audioFormat == 1 || format.audioFormat == 0xFFFE // extensible = PCM in practice
        if (!isPcm && !isFloat)
            throw IllegalArgumentException(
                "Unsupported WAV audio format ${format.audioFormat} (${format.bitsPerSample}-bit); " +
                    "try uncompressed PCM WAV, MP3 or M4A"
            )
        val bits = format.bitsPerSample
        if (isFloat && bits != 32 || isPcm && bits !in intArrayOf(8, 16, 24, 32))
            throw IllegalArgumentException("Unsupported WAV encoding: ${if (isFloat) "float" else "PCM"} with $bits bits")
        if (format.channels < 1) throw IllegalArgumentException("Invalid WAV channel count")

        val bytesPerSample = bits / 8
        val usable = data.size / bytesPerSample * bytesPerSample
        val shorts = ShortArray(usable / bytesPerSample)
        var sample = 0
        var pos = 0
        while (pos < usable) {
            val raw: Long = when (bits) {
                8 -> ((data[pos].toInt() and 0xFF) - 128).toLong()
                16 -> readUnsigned(data, pos, 2).toLong().let { if (it >= 0x8000) it - 0x10000 else it }
                24 -> readUnsigned(data, pos, 3).toLong().let { if (it >= 0x800000) it - 0x1000000 else it }
                else -> if (isFloat)
                    ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
                else readUnsigned(data, pos, 4).toLong().let { if (it >= 0x80000000) it - 0x100000000 else it }
            }
            shorts[sample++] = when {
                isFloat -> roundToShort(java.lang.Float.intBitsToFloat(raw.toInt()).coerceIn(-1f, 1f) * 32767f)
                else -> raw.coerceIn(-32768, 32767).toInt().toShort()
            }
            pos += bytesPerSample
        }
        return shorts
    }

    private fun readUnsigned(data: ByteArray, pos: Int, count: Int): Int {
        var value = 0
        for (i in count - 1 downTo 0) value = value shl 8 or (data[pos + i].toInt() and 0xFF)
        return value
    }

    private fun roundToShort(value: Float): Short = (if (value >= 0f) (value + 0.5f).toInt() else (value - 0.5f).toInt()).toShort()

    /**
     * Core conversion shared by the WAV path and the MediaCodec path: downmix
     * interleaved [channels]-channel audio at [sampleRate] into 16 kHz mono PCM bytes.
     */
    internal fun to16kMonoPcm(interleaved: ShortArray, channels: Int, sampleRate: Int): ByteArray {
        require(channels >= 1 && sampleRate > 0)
        val frameCount = interleaved.size / channels
        if (frameCount > MAX_FRAMES) throw IllegalArgumentException("Shared audio too long (max 20 minutes)")
        val mono = ShortArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0
            for (channel in 0 until channels) sum += interleaved[frame * channels + channel].toInt()
            mono[frame] = (sum / channels).toShort()
        }
        val filtered = if (sampleRate > TARGET_RATE) lowPassForDecimation(mono, sampleRate) else mono
        val outFrameCount = ((filtered.size.toLong() * TARGET_RATE) / sampleRate).toInt()
        val out = ShortArray(outFrameCount)
        for (i in 0 until outFrameCount) {
            val sourcePosition = i.toLong() * sampleRate
            val srcIdx = (sourcePosition / TARGET_RATE).toInt().coerceIn(0, filtered.size - 1)
            val frac = (sourcePosition % TARGET_RATE).toDouble() / TARGET_RATE
            val base = filtered[srcIdx].toDouble()
            val next = if (srcIdx + 1 < filtered.size) filtered[srcIdx + 1].toDouble() else base
            out[i] = Math.round(base + frac * (next - base)).coerceIn(-32768, 32767).toShort()
        }
        return littleEndian(out)
    }

    private fun littleEndian(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    /**
     * Two cascaded biquad low-passes at 7 kHz (a bit under the 16 kHz Nyquist):
     * speech survives intact while the 8–22 kHz band content that would alias
     * into the transcript on decimation is strongly attenuated.
     */
    private fun lowPassForDecimation(input: ShortArray, sampleRate: Int): ShortArray {
        var out = input
        for (section in 0 until 2) out = biquadLowPass(out, sampleRate)
        return out
    }

    /** Butterworth low-pass, direct form 1. DC gain is exactly 1 (sum of a's equals sum of b's). */
    private fun biquadLowPass(input: ShortArray, sampleRate: Int): ShortArray {
        val w0 = 2.0 * Math.PI * 7000.0 / sampleRate
        val cosine = Math.cos(w0)
        val alpha = Math.sin(w0) / (2.0 * 0.70711)
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosine
        val a2 = 1.0 - alpha
        val b0 = (1.0 - cosine) / (2.0 * a0)
        val b1 = (1.0 - cosine) / a0
        val b2 = b0
        val a1Norm = a1 / a0
        val a2Norm = a2 / a0
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        val out = ShortArray(input.size)
        for (n in input.indices) {
            val x0 = input[n].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1Norm * y1 - a2Norm * y2
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            out[n] = Math.round(y0).coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * Generic compressed-container path (MP3, M4A/AAC, OGG, FLAC, ...):
     * MediaExtractor picks the audio track, MediaCodec decodes it, and the
     * resulting interleaved PCM goes through the shared 16 kHz mono conversion.
     */
    private fun decodeCompressed(context: Context, uri: Uri): ByteArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                if (trackFormat.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = trackFormat
                    break
                }
            }
            if (trackIndex < 0 || format == null)
                throw IllegalArgumentException("No audio track found in the shared file")

            // The container's advertised rate/channels are only a fallback:
            // decoders report the real (possibly different) output format.
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val samples = drainCodec(codec, extractor, System.currentTimeMillis() + DECODE_DEADLINE_MS) { rate, chans ->
                    if (rate != null) sampleRate = rate
                    if (chans != null) channels = chans
                }
                val mono = to16kMonoPcm(samples, channels, sampleRate)
                if (mono.isEmpty()) throw IllegalArgumentException("Decoder produced no audio from the shared file")
                return mono
            } finally {
                try {
                    codec.stop()
                } catch (_: Exception) {
                }
                codec.release()
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not decode shared audio: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Standard MediaCodec pump loop; returns the decoded interleaved samples. */
    private fun drainCodec(
        codec: MediaCodec,
        extractor: MediaExtractor,
        deadlineAt: Long,
        onFormatChanged: (sampleRate: Int?, channels: Int?) -> Unit
    ): ShortArray {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val chunks = ArrayList<ShortArray>()
        var total = 0L
        while (!outputDone) {
            if (System.currentTimeMillis() > deadlineAt)
                throw IllegalArgumentException("Shared audio took too long to decode")
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)
                    val size = if (buffer == null) 0 else {
                        buffer.clear()
                        extractor.readSampleData(buffer, 0).coerceAtLeast(0).coerceAtMost(buffer.capacity())
                    }
                    if (size == 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when (outIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = codec.outputFormat
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) onFormatChanged(outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), null)
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) onFormatChanged(null, outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
                else -> if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val shortCount = info.size / 2
                        val chunk = ShortArray(shortCount)
                        buffer.asShortBuffer().get(chunk)
                        chunks.add(chunk)
                        total += shortCount
                        if (total / 2 > MAX_FRAMES)
                            throw IllegalArgumentException("Shared audio too long (max 20 minutes)")
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        val out = ShortArray(total.toInt())
        var pos = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, out, pos, chunk.size)
            pos += chunk.size
        }
        return out
    }
}
