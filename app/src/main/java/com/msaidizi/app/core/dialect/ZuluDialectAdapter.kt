package com.msaidizi.app.core.dialect

/**
 * Zulu dialect adapter for South Africa.
 *
 * Zulu (isiZulu) is a Bantu language spoken by ~12 million people
 * as a first language in South Africa (KwaZulu-Natal, Gauteng).
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [ZuluDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object ZuluDialectAdapter : DialectAdapter(ZuluDialectData.config)
