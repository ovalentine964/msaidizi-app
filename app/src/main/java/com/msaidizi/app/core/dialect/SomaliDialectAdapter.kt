package com.msaidizi.app.core.dialect

/**
 * Somali dialect adapter for Horn of Africa.
 *
 * Somali is an Afro-Asiatic language spoken by ~22 million people
 * in Somalia, Djibouti, Somali Region (Ethiopia), and NE Kenya.
 *
 * This adapter uses the data-driven [DialectAdapter] base class.
 * All dialect-specific data is in [SomaliDialectData].
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object SomaliDialectAdapter : DialectAdapter(SomaliDialectData.config)
