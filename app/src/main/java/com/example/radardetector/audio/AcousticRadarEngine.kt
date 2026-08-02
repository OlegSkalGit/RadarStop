package com.example.radardetector.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import com.example.radardetector.util.AppLogger
import java.util.concurrent.Executors

class AcousticRadarEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var toneGeneratorMusic: ToneGenerator? = null
    private var toneGeneratorAlarm: ToneGenerator? = null
    @Volatile
    private var isBeeping = false
    private var beepThread: Thread? = null
    @Volatile
    private var currentDelayMs: Long = 1500

    private val audioExecutor = Executors.newSingleThreadExecutor()
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var lastBtLogState: Boolean? = null

    private fun isBluetoothAudioConnected(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                devices.any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
            }
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    private fun initToneGenerators() {
        if (toneGeneratorMusic == null) {
            try {
                toneGeneratorMusic = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                AppLogger.log("AcousticRadarEngine", "initToneGenerators", true, "Hardware ToneGenerator (STREAM_MUSIC) initialized.")
            } catch (e: Exception) {
                AppLogger.log("AcousticRadarEngine", "initToneGenerators", false, "STREAM_MUSIC ToneGenerator failed: ${e.message}")
            }
        }
        if (toneGeneratorAlarm == null) {
            try {
                toneGeneratorAlarm = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                AppLogger.log("AcousticRadarEngine", "initToneGenerators", true, "Hardware ToneGenerator (STREAM_ALARM) initialized.")
            } catch (e: Exception) {
                AppLogger.log("AcousticRadarEngine", "initToneGenerators", false, "STREAM_ALARM ToneGenerator failed: ${e.message}")
            }
        }
    }

    private fun emitBeep() {
        val btConnected = isBluetoothAudioConnected()
        if (lastBtLogState != btConnected) {
            lastBtLogState = btConnected
            AppLogger.log("AcousticRadarEngine", "emitBeep", true, "Audio Output Routing: ${if (btConnected) "DUAL (Bluetooth STREAM_MUSIC + Phone Speaker STREAM_ALARM)" else "SINGLE (STREAM_MUSIC)"}")
        }
        if (btConnected) {
            toneGeneratorMusic?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            toneGeneratorAlarm?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } else {
            if (toneGeneratorMusic != null) {
                toneGeneratorMusic?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            } else {
                toneGeneratorAlarm?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
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
                    initToneGenerators()
                    emitBeep()
                }
                Thread.sleep(250)
                synchronized(this@AcousticRadarEngine) {
                    abandonFocus()
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
                    initToneGenerators()
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
                Thread.sleep(250)
                synchronized(this@AcousticRadarEngine) {
                    abandonFocus()
                }
            } catch (e: Exception) {
                AppLogger.log("AcousticRadarEngine", "playBeeps", false, "Error: ${e.message}")
            }
        }
    }

    @Synchronized
    fun startAlert(delayMs: Long) {
        currentDelayMs = delayMs
        if (!isBeeping) {
            isBeeping = true
            requestFocus()
            initToneGenerators()
            AppLogger.log("AcousticRadarEngine", "startAlert", true, "Beep thread started with delay: ${delayMs}ms")
            beepThread?.interrupt()
            beepThread = Thread {
                while (isBeeping) {
                    try {
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
        toneGeneratorMusic?.release()
        toneGeneratorMusic = null
        toneGeneratorAlarm?.release()
        toneGeneratorAlarm = null
        AppLogger.log("AcousticRadarEngine", "release", true, "Dual ToneGenerators released.")
    }
}
