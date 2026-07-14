package com.msaidizi.app.core.dialect

/**
 * Migori County Swahili dialect adapter.
 *
 * Migori Swahili has heavy Luo (Dholuo) substrate influence:
 * - Dholuo-Swahili code-switching at clause boundaries
 * - Luo vocabulary borrowed into Swahili context
 * - Phonological transfer: implosive consonants bleed into Swahili
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [MigoriDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object MigoriDialectAdapter : DialectAdapter(MigoriDialectData.config)
