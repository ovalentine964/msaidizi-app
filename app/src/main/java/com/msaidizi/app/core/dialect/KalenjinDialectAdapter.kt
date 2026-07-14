package com.msaidizi.app.core.dialect

/**
 * Kalenjin dialect adapter for Rift Valley Kenya.
 *
 * Kalenjin is a Nilotic language cluster spoken by ~5 million people
 * in the Rift Valley (Nandi, Baringo, Uasin Gishu, Elgeyo-Marakwet counties).
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [KalenjinDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object KalenjinDialectAdapter : DialectAdapter(KalenjinDialectData.config)
