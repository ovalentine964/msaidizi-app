package com.msaidizi.app.core.dialect

/**
 * Luhya dialect adapter for Western Kenya.
 *
 * Luhya (Oluluyia) is a cluster of Bantu languages spoken by ~6 million people
 * in Western Kenya (Kakamega, Bungoma, Busia, Vihiga counties).
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [LuhyaDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object LuhyaDialectAdapter : DialectAdapter(LuhyaDialectData.config)
