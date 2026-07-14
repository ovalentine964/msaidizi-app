package com.msaidizi.app.core.dialect

/**
 * Igbo dialect adapter for Southeastern Nigeria.
 *
 * Igbo is a Niger-Congo language spoken by ~45 million people
 * in southeastern Nigeria (Anambra, Enugu, Imo, Abia, Ebonyi states).
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [IgboDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object IgboDialectAdapter : DialectAdapter(IgboDialectData.config)
