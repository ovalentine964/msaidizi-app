package com.msaidizi.app.core.dialect

/**
 * Dholuo dialect adapter for Western Kenya.
 *
 * Dholuo (Luo) is a Nilotic language spoken by ~6 million people
 * around Lake Victoria (Kisumu, Siaya, Homa Bay, Migori counties).
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [DholuoDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object DholuoDialectAdapter : DialectAdapter(DholuoDialectData.config)
