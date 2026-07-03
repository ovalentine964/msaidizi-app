package com.msaidizi.app.core.dialect

/**
 * Sheng dialect adapter for Kenyan urban slang.
 *
 * Sheng is a Swahili-based cant/slang spoken by ~20 million people
 * in Nairobi and other Kenyan cities. It blends Swahili, English,
 * Dholuo, Kikuyu, and other Kenyan languages.
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [ShengDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object ShengDialectAdapter : DialectAdapter(ShengDialectData.config)
