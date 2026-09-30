package com.xmmtr.navlite

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsAnnouncer(context: Context) {
    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var pending: String? = null

    var chineseReady = false
        private set

    init {
        engine = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = engine?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                chineseReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                pending?.let { speak(it) }
            }
            pending = null
        }
    }

    fun announce(text: String) {
        if (text.isBlank() || LocalStore.isMuted(appContext)) return
        if (!chineseReady) {
            pending = text
            return
        }
        speak(text)
    }

    private fun speak(text: String) {
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        engine?.stop()
    }

    companion object {
        private const val UTTERANCE_ID = "navlite"
    }
}
