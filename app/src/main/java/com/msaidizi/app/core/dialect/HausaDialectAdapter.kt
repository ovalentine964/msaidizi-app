package com.msaidizi.app.core.dialect

/**
 * Hausa dialect adapter for West Africa.
 *
 * Hausa is an Afro-Asiatic language spoken by ~80 million people
 * across Nigeria, Niger, Ghana, Cameroon, and Chad.
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [HausaDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object HausaDialectAdapter : DialectAdapter(HausaDialectData.config)
