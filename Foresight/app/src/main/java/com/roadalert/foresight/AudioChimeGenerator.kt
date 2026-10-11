package com.roadalert.foresight

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.sin

/**
 * Generates crisp, pleasant synthesizer tones in-memory without external audio files.
 */
object AudioChimeGenerator {
    private const val SAMPLE_RATE = 44100

    /**
     * Dual-tone pleasant alert chime (e.g. 587Hz D5 -> 880Hz A5)
     * Used right before VRU or Speed Camera voice alerts.
     */
    fun playDualToneChime(onComplete: (() -> Unit)? = null) {
        Thread {
            try {
                val durationMs = 280
                val numSamples = (SAMPLE_RATE * (durationMs / 1000.0)).toInt()
                val half = numSamples / 2
                val buffer = ShortArray(numSamples)

                val freq1 = 587.33 // D5
                val freq2 = 880.00 // A5

                for (i in 0 until numSamples) {
                    val freq = if (i < half) freq1 else freq2
                    // Smooth envelope to prevent audio clicking
                    val envelope = when {
                        i < 500 -> i / 500.0
                        i > numSamples - 1000 -> (numSamples - i) / 1000.0
                        else -> 1.0
                    }
                    val sample = (sin(2.0 * Math.PI * i * freq / SAMPLE_RATE) * 32767 * 0.75 * envelope).toInt()
                    buffer[i] = sample.toShort()
                }

                playBuffer(buffer)
                onComplete?.invoke()
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete?.invoke()
            }
        }.start()
    }

    /**
     * Subtle single warning tone (e.g. 440Hz A4)
     * Used when vehicle exceeds speed limit (+10% tolerance).
     */
    fun playSpeedOvershootChime() {
        Thread {
            try {
                val durationMs = 180
                val numSamples = (SAMPLE_RATE * (durationMs / 1000.0)).toInt()
                val buffer = ShortArray(numSamples)
                val freq = 440.00 // A4

                for (i in 0 until numSamples) {
                    val envelope = when {
                        i < 400 -> i / 400.0
                        i > numSamples - 800 -> (numSamples - i) / 800.0
                        else -> 1.0
                    }
                    val sample = (sin(2.0 * Math.PI * i * freq / SAMPLE_RATE) * 32767 * 0.6 * envelope).toInt()
                    buffer[i] = sample.toShort()
                }

                playBuffer(buffer)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun playBuffer(buffer: ShortArray) {
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(buffer, 0, buffer.size)
        audioTrack.play()
        Thread.sleep(buffer.size * 1000L / SAMPLE_RATE + 50)
        audioTrack.release()
    }
}
