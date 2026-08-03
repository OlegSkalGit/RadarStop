package com.example.radardetector.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import com.example.radardetector.util.AppLogger
import java.util.concurrent.Executors

class AcousticRadarEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var toneGenerator: ToneGenerator? = null
    @Volatile
    private var isBeeping = false
    private var beepThread: Thread? = null
    @Volatile
    private var currentDelayMs: Long = 1500
    @Volatile
    private var lastGpsUpdateMs: Long = 0L

    fun notifyLocationUpdate() {
        lastGpsUpdateMs = System.currentTimeMillis()
    }

    private val audioExecutor = Executors.newSingleThreadExecutor()
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    @Synchronized
    private fun initToneGenerator() {
        if (toneGenerator == null) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                AppLogger.log("AcousticRadarEngine", "initToneGenerator", true, "Single Hardware ToneGenerator (STREAM_MUSIC) initialized.")
            } catch (e: Exception) {
                AppLogger.log("AcousticRadarEngine", "initToneGenerator", false, "STREAM_MUSIC ToneGenerator failed: ${e.message}")
            }
        }
    }

    private fun emitBeep() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            AppLogger.log("AcousticRadarEngine", "emitBeep", false, "Error emitting beep: ${e.message}")
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
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            }
            AppLogger.log("AcousticRadarEngine", "requestFocus", hasAudioFocus, "Audio focus granted: $hasAudioFocus")
        } catch (e: Exception) {
            AppLogger.log("AcousticRadarEngine", "requestFocus", false, "Failed: ${e.message}")
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
            AppLogger.log("AcousticRadarEngine", "abandonFocus", true, "Audio focus abandoned.")
        } catch (e: Exception) {
            AppLogger.log("AcousticRadarEngine", "abandonFocus", false, "Failed: ${e.message}")
        }
        hasAudioFocus = false
    }

    fun playSingleBeep() {
        audioExecutor.execute {
            try {
                synchronized(this@AcousticRadarEngine) {
                    requestFocus()
                    initToneGenerator()
                    emitBeep()
                }
                AppLogger.log("AcousticRadarEngine", "playSingleBeep", true, "Single startup beep played.")
            } catch (e: Exception) {
                AppLogger.log("AcousticRadarEngine", "playSingleBeep", false, "Error: ${e.message}")
            }
        }
    }

    fun playBeeps(count: Int, intervalMs: Long) {
        audioExecutor.execute {
            try {
                synchronized(this@AcousticRadarEngine) {
                    requestFocus()
                    initToneGenerator()
                }
                AppLogger.log("AcousticRadarEngine", "playBeeps", true, "Playing $count test beeps at ${intervalMs}ms interval...")
                for (i in 1..count) {
                    synchronized(this@AcousticRadarEngine) {
                        emitBeep()
                    }
                    if (i < count) {
                        Thread.sleep(intervalMs)
                    }
                }
            } catch (e: Exception) {
                AppLogger.log("AcousticRadarEngine", "playBeeps", false, "Error: ${e.message}")
            }
        }
    }

    @Synchronized
    fun startAlert(delayMs: Long) {
        currentDelayMs = delayMs
        lastGpsUpdateMs = System.currentTimeMillis()
        if (!isBeeping) {
            isBeeping = true
            requestFocus()
            initToneGenerator()
            AppLogger.log("AcousticRadarEngine", "startAlert", true, "Beep thread started with delay: ${delayMs}ms")
            beepThread?.interrupt()
            beepThread = Thread {
                while (isBeeping) {
                    try {
                        val now = System.currentTimeMillis()
                        if (lastGpsUpdateMs != 0L && (now - lastGpsUpdateMs > 3500L)) {
                            AppLogger.log(
                                "AcousticRadarEngine",
                                "beepThread",
                                false,
                                "GPS stalled for >3.5s during alert (${now - lastGpsUpdateMs}ms). Automatically stopping sound."
                            )
                            stopAlert()
                            break
                        }
                        synchronized(this@AcousticRadarEngine) {
                            if (isBeeping) {
                                emitBeep()
                            }
                        }
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
            AppLogger.log("AcousticRadarEngine", "stopAlert", true, "Beep thread stopped.")
        }
    }

    @Synchronized
    fun release() {
        val threadToJoin = beepThread
        stopAlert()
        try {
            threadToJoin?.join(500)
        } catch (e: InterruptedException) {
            // Ignore
        }
        audioExecutor.shutdownNow()
        toneGenerator?.release()
        toneGenerator = null
        AppLogger.log("AcousticRadarEngine", "release", true, "Single STREAM_MUSIC ToneGenerator released.")
    }
}
