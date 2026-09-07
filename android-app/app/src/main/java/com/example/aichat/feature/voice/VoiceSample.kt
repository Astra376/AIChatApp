package com.example.aichat.feature.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

internal object VoiceSample {
    data class Prepared(val file: File, val mime: String)
    fun prepare(context: Context, uri: Uri): Prepared {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val reader = MediaMetadataRetriever()
        val duration = try {
            reader.setDataSource(context, uri)
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { reader.release() }
        require(duration in 3_000L..300_000L) { "Choose 3 seconds to 5 minutes of clear speech." }
        if (!mime.startsWith("video/")) {
            require(mime.startsWith("audio/")) { "Choose an audio or video recording." }
            val extension = when (mime) { "audio/mpeg" -> ".mp3"; "audio/wav", "audio/x-wav" -> ".wav"; "audio/ogg" -> ".ogg"; "audio/flac" -> ".flac"; else -> ".m4a" }
            val file = File.createTempFile("voice_sample_", extension, context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(16_384); var total = 0
                    while (true) { val count = input.read(buffer); if (count < 0) break; total += count
                        require(total <= 10_000_000) { "Choose a sample under 10 MB." }; output.write(buffer, 0, count) }
                } }
                return Prepared(file, mime)
            } catch (error: Throwable) { file.delete(); throw error }
        }
        // Android's extractor/muxer copies only audio packets; no video bytes go to the provider.
        val output = File.createTempFile("voice_audio_", ".m4a", context.cacheDir)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("This video has no audio track.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val destination = writer.addTrack(format)
            writer.start()
            val buffer = ByteBuffer.allocate(1_048_576)
            val info = MediaCodec.BufferInfo()
            while (extractor.sampleTime in 0L..60_000_000L) {
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) break
                info.set(0, count, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                writer.writeSampleData(destination, buffer, info)
                require(output.length() <= 10_000_000L) { "Audio sample is too large." }
                extractor.advance()
            }
            writer.stop()
            return Prepared(output, "audio/mp4")
        } catch (error: Throwable) { output.delete(); throw error }
        finally { extractor.release(); muxer?.release() }
    }
}
