package com.msaidizi.app.core.dialect

/**
 * Amharic dialect adapter for Ethiopia.
 *
 * Amharic is a Semitic language spoken by ~57 million people in Ethiopia.
 * This adapter handles romanized (transliterated) input from ASR.
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [AmharicDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object AmharicDialectAdapter : DialectAdapter(AmharicDialectData.config)
