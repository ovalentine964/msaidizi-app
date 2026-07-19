package com.msaidizi.app.voice.briefing

import android.content.Context
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.voice.KokoroTtsEngine
import com.msaidizi.app.voice.TextToSpeech
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module for the audio briefing system.
 *
 * Provides:
 * - AudioBriefingTextTransformer: Text → spoken Swahili conversion
 * - AudioBriefingGenerator: BriefingResult → WAV audio file
 * - AudioBriefingDelivery: Audio file → WhatsApp/notification delivery
 *
 * Dependencies:
 * - KokoroTtsEngine (existing) — primary TTS for Swahili
 * - TextToSpeech / Piper (existing) — fallback TTS
 * - BriefingDelivery (existing) — text briefing generation
 *
 * Add to MsaidiziApp.kt:
 * ```kotlin
 * // In Application class
 * @Inject lateinit var audioBriefingDelivery: AudioBriefingDelivery
 *
 * // After onboarding:
 * AudioBriefingWorker.scheduleAllAudioBriefings(this)
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioBriefingModule {

    @Provides
    @Singleton
    fun provideAudioBriefingTextTransformer(): AudioBriefingTextTransformer {
        return AudioBriefingTextTransformer()
    }

    @Provides
    @Singleton
    fun provideAudioBriefingGenerator(
        @ApplicationContext context: Context,
        textTransformer: AudioBriefingTextTransformer,
        kokoroTts: KokoroTtsEngine,
        piperTts: TextToSpeech
    ): AudioBriefingGenerator {
        return AudioBriefingGenerator(context, textTransformer, kokoroTts, piperTts)
    }

    @Provides
    @Singleton
    fun provideAudioBriefingDelivery(
        @ApplicationContext context: Context,
        audioGenerator: AudioBriefingGenerator
    ): AudioBriefingDelivery {
        return AudioBriefingDelivery(context, audioGenerator)
    }
}
