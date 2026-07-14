package com.msaidizi.app.core.dialect

/**
 * Yoruba dialect adapter for Southwestern Nigeria.
 *
 * Yoruba is a Niger-Congo language spoken by ~45 million people
 * in Nigeria, Benin, and Togo.
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [YorubaDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object YorubaDialectAdapter : DialectAdapter(YorubaDialectData.config)
