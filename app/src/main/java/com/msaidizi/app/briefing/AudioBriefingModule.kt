package com.msaidizi.app.voice.briefing

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt DI module for the audio briefing system.
 *
 * All classes use @Inject constructor — no @Provides needed.
 * - AudioBriefingTextTransformer: Text → spoken Swahili conversion
 * - AudioBriefingGenerator: BriefingResult → WAV audio file
 * - AudioBriefingDelivery: Audio file → WhatsApp/notification delivery
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioBriefingModule {
    // All bindings resolved via @Inject constructor on each class.
    // No @Provides methods needed — avoids duplicate binding errors.
}
