package com.msaidizi.app.core.dialect

/**
 * Kikuyu dialect adapter for Central Kenya.
 *
 * Kikuyu (Gĩkũyũ) is a Bantu language spoken by ~8 million people
 * in Central Kenya (Nyeri, Murang'a, Kiambu, Kirinyaga counties).
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [KikuyuDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object KikuyuDialectAdapter : DialectAdapter(KikuyuDialectData.config)
