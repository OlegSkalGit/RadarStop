package com.example.radardetector.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build

class AcousticRadarEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var toneGenerator: ToneGenerator? = null
    @Volatile
    private var isBeeping = false
    private var beepThread: Thread? = null
    @Volatile
    private var currentDelayMs: Long = 1500

    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    @Synchronized
    private fun initToneGenerator() {
        if (toneGenerator == null) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun requestFocus() {
        if (hasAudioFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attr)
                    .build()
                val res = audioManager.requestAudioFocus(focusRequest!!)
                hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            } else {
                @Suppress("DEPRECATION")
                val res = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun abandonFocus() {
        if (!hasAudioFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        hasAudioFocus = false
    }

    @Synchronized
    fun startAlert(delayMs: Long) {
        currentDelayMs = delayMs
        if (!isBeeping) {
            isBeeping = true
            requestFocus()
            initToneGenerator()
            beepThread = Thread {
                while (isBeeping) {
                    try {
                        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                        Thread.sleep(currentDelayMs)
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.apply { start() }
        }
    }

    fun updateDelay(delayMs: Long) {
        currentDelayMs = delayMs
    }

    @Synchronized
    fun stopAlert() {
        if (isBeeping) {
            isBeeping = false
            beepThread?.interrupt()
            beepThread = null
            abandonFocus()
        }
    }

    @Synchronized
    fun release() {
        stopAlert()
        toneGenerator?.release()
        toneGenerator = null
    }
}
