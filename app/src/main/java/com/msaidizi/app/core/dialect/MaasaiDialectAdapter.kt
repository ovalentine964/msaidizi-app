package com.msaidizi.app.core.dialect

/**
 * Maasai dialect adapter for Southern Kenya and Northern Tanzania.
 *
 * Maasai (Maa) is a Nilotic language spoken by ~1.5 million people
 * in Kajiado, Narok (Kenya) and Arusha, Manyara (Tanzania).
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [MaasaiDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object MaasaiDialectAdapter : DialectAdapter(MaasaiDialectData.config)
