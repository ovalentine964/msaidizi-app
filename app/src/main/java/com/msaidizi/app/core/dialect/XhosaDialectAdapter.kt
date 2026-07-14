package com.msaidizi.app.core.dialect

/**
 * Xhosa dialect adapter for South Africa.
 *
 * Xhosa (isiXhosa) is a Bantu language spoken by ~8 million people
 * in South Africa (Eastern Cape, Western Cape).
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [XhosaDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object XhosaDialectAdapter : DialectAdapter(XhosaDialectData.config)
