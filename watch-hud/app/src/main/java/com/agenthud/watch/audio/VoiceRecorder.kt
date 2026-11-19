package com.agenthud.watch.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures 16kHz 16-bit mono WAV directly from the Pixel Watch 3 built-in mic.
 * Zero dependency on Google Assistant or phone pairing.
 */
class VoiceRecorder {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @Volatile
    private var recording = false
    private val pcmStream = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    suspend fun recordUntilStopped(onAmplitude: (Float) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        pcmStream.reset()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(3200)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuf * 2
        )

        val buf = ShortArray(minBuf / 2)
        recording = true
        try {
            recorder.startRecording()
            while (recording && isActive) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    var peak = 0
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val s = buf[i].toInt()
                        val abs = kotlin.math.abs(s)
                        if (abs > peak) peak = abs
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    pcmStream.write(bytes)
                    onAmplitude((peak / 32767f).coerceIn(0f, 1f))
                }
            }
        } finally {
            recording = false
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        wrapWav(pcmStream.toByteArray())
    }

    fun stop() {
        recording = false
    }

    private fun wrapWav(pcm: ByteArray): ByteArray {
        val totalDataLen = pcm.size + 36
        val byteRate = sampleRate * 2 // 16-bit mono
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)           // Subchunk1Size (PCM)
            putShort(1)          // AudioFormat = PCM
            putShort(1)          // NumChannels = 1
            putInt(sampleRate)   // SampleRate
            putInt(byteRate)     // ByteRate
            putShort(2)          // BlockAlign
            putShort(16)         // BitsPerSample
            put("data".toByteArray())
            putInt(pcm.size)
        }.array()
        return header + pcm
    }
}
