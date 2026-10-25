package com.cheick.traduction

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts the 16-bit PCM WAV written by TextToSpeech into an AAC .m4a file. */
object AacEncoder {

    private const val TIMEOUT_US = 10_000L

    fun wavToM4a(wav: File, out: File) {
        val bytes = wav.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataStart = -1
        var dataEnd = -1
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunk = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = header.getInt(pos + 4)
            if (chunk == "fmt ") {
                channels = header.getShort(pos + 10).toInt()
                sampleRate = header.getInt(pos + 12)
                bitsPerSample = header.getShort(pos + 22).toInt()
            } else if (chunk == "data") {
                dataStart = pos + 8
                // Some engines leave the size at 0 / -1 while streaming: use the rest of the file.
                dataEnd = if (size <= 0 || dataStart + size > bytes.size) bytes.size else dataStart + size
                break
            }
            if (size < 0) break
            pos += 8 + size + (size and 1)
        }
        require(dataStart > 0 && channels in 1..2 && sampleRate > 0 && bitsPerSample == 16) { "Unsupported WAV" }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val frameSize = channels * 2
            val bytesPerSecond = sampleRate.toLong() * frameSize
            val info = MediaCodec.BufferInfo()
            var track = -1
            var offset = dataStart
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        var count = minOf(buffer.remaining(), dataEnd - offset)
                        count -= count % frameSize
                        val ptsUs = (offset - dataStart) * 1_000_000L / bytesPerSecond
                        if (count <= 0) {
                            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.put(bytes, offset, count)
                            codec.queueInputBuffer(index, 0, count, ptsUs, 0)
                            offset += count
                        }
                    }
                }

                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
        check(muxerStarted && out.length() > 0) { "Encoding failed" }
    }
}
