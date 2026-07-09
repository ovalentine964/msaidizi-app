package com.msaidizi.app.core.model

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * CurrencyFormatter tests — because a mama mboga needs to see "KSh 500" not "500.000000001".
 *
 * Tests cover:
 * - KES formatting (the primary currency)
 * - All African currencies
 * - formatAmount (display formatting)
 * - formatForSpeech (TTS output)
 * - parseAmount (input parsing)
 * - Edge cases (zero, very large, subunits)
 */
@RunWith(JUnit4::class)
class CurrencyFormatterTest {

    // ═══════════════════════════════════════════════════════════════════
    // KES formatAmount — What mama mboga sees on her screen
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `KES formatAmount formats basic amounts correctly`() {
        // 50000 cents = KSh 500
        assertEquals("KSh 500", AfricanCurrency.KES.formatAmount(50000))
    }

    @Test
    fun `KES formatAmount formats with decimals when requested`() {
        assertEquals("KSh 500.00", AfricanCurrency.KES.formatAmount(50000, showDecimals = true))
    }

    @Test
    fun `KES formatAmount handles zero`() {
        assertEquals("KSh 0", AfricanCurrency.KES.formatAmount(0))
        assertEquals("KSh 0.00", AfricanCurrency.KES.formatAmount(0, showDecimals = true))
    }

    @Test
    fun `KES formatAmount handles one shilling`() {
        assertEquals("KSh 0", AfricanCurrency.KES.formatAmount(100))  // 100 cents = 1 KSh
        assertEquals("KSh 1", AfricanCurrency.KES.formatAmount(100))
    }

    @Test
    fun `KES formatAmount handles large amounts with comma separation`() {
        // 1,000,000 cents = KSh 10,000
        assertEquals("KSh 10,000", AfricanCurrency.KES.formatAmount(1_000_000))
        // 100,000,000 cents = KSh 1,000,000
        assertEquals("KSh 1,000,000", AfricanCurrency.KES.formatAmount(100_000_000))
    }

    @Test
    fun `KES formatAmount handles common M-Pesa amounts`() {
        // KSh 100 (mia moja)
        assertEquals("KSh 100", AfricanCurrency.KES.formatAmount(10_000))
        // KSh 1,000 (elfu moja)
        assertEquals("KSh 1,000", AfricanCurrency.KES.formatAmount(100_000))
        // KSh 500
        assertEquals("KSh 500", AfricanCurrency.KES.formatAmount(50_000))
        // KSh 10,000
        assertEquals("KSh 10,000", AfricanCurrency.KES.formatAmount(1_000_000))
    }

    @Test
    fun `KES formatAmount handles sub-shilling amounts`() {
        // 50 cents = KSh 0.50
        assertEquals("KSh 1", AfricanCurrency.KES.formatAmount(50))  // Rounds to 1
        assertEquals("KSh 0.50", AfricanCurrency.KES.formatAmount(50, showDecimals = true))
    }

    // ═══════════════════════════════════════════════════════════════════
    // formatForSpeech — What the TTS engine reads aloud
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `KES formatForSpeech reads small amounts correctly`() {
        val result = AfricanCurrency.KES.formatForSpeech(50_000) // KSh 500
        assertTrue("Should contain 'mia tano'", result.contains("mia tano"))
    }

    @Test
    fun `KES formatForSpeech reads thousands correctly`() {
        val result = AfricanCurrency.KES.formatForSpeech(100_000) // KSh 1,000
        assertTrue("Should contain 'elfu'", result.contains("elfu"))
    }

    @Test
    fun `KES formatForSpeech reads hundreds of thousands correctly`() {
        val result = AfricanCurrency.KES.formatForSpeech(5_000_000) // KSh 50,000
        assertTrue("Should contain 'laki'", result.contains("laki"))
    }

    @Test
    fun `KES formatForSpeech reads millions correctly`() {
        val result = AfricanCurrency.KES.formatForSpeech(100_000_000) // KSh 1,000,000
        assertTrue("Should contain 'milioni'", result.contains("milioni"))
    }

    @Test
    fun `KES formatForSpeech reads plain numbers for small amounts`() {
        val result = AfricanCurrency.KES.formatForSpeech(5_000) // KSh 50
        assertTrue("Should contain '50'", result.contains("50"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Other African Currencies
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `NGN formatAmount formats correctly`() {
        assertEquals("₦ 500", AfricanCurrency.NGN.formatAmount(50000))
        assertEquals("₦ 1,000", AfricanCurrency.NGN.formatAmount(100000))
    }

    @Test
    fun `TZS formatAmount formats correctly`() {
        assertEquals("TSh 500", AfricanCurrency.TZS.formatAmount(50000))
        assertEquals("TSh 1,000", AfricanCurrency.TZS.formatAmount(100000))
    }

    @Test
    fun `ZAR formatAmount formats correctly`() {
        assertEquals("R 500", AfricanCurrency.ZAR.formatAmount(50000))
    }

    @Test
    fun `GHS formatAmount formats correctly`() {
        assertEquals("GH₵ 500", AfricanCurrency.GHS.formatAmount(50000))
    }

    @Test
    fun `ETB formatAmount formats correctly`() {
        assertEquals("Br 500", AfricanCurrency.ETB.formatAmount(50000))
    }

    // ═══════════════════════════════════════════════════════════════════
    // fromCode and forCountry lookups
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `fromCode finds KES by code`() {
        assertEquals(AfricanCurrency.KES, AfricanCurrency.fromCode("KES"))
        assertEquals(AfricanCurrency.KES, AfricanCurrency.fromCode("kes"))
    }

    @Test
    fun `fromCode returns null for unknown code`() {
        assertNull(AfricanCurrency.fromCode("USD"))
        assertNull(AfricanCurrency.fromCode("XYZ"))
    }

    @Test
    fun `forCountry finds Kenya`() {
        assertEquals(AfricanCurrency.KES, AfricanCurrency.forCountry("Kenya"))
        assertEquals(AfricanCurrency.KES, AfricanCurrency.forCountry("kenya"))
    }

    @Test
    fun `forCountry returns null for unknown country`() {
        assertNull(AfricanCurrency.forCountry("Japan"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // parseAmount — Parsing user input like "KSh 500"
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseAmount parses KES amount`() {
        val result = AfricanCurrency.parseAmount("KSh 500")
        assertNotNull(result)
        assertEquals(50000L, result!!.first)  // 500 * 100 cents
        assertEquals(AfricanCurrency.KES, result.second)
    }

    @Test
    fun `parseAmount parses KES with commas`() {
        val result = AfricanCurrency.parseAmount("KSh 1,500")
        assertNotNull(result)
        assertEquals(150000L, result!!.first)
    }

    @Test
    fun `parseAmount parses KES with decimals`() {
        val result = AfricanCurrency.parseAmount("KSh 500.50")
        assertNotNull(result)
        assertEquals(50050L, result!!.first)
    }

    @Test
    fun `parseAmount returns null for unrecognized format`() {
        assertNull(AfricanCurrency.parseAmount("500"))
        assertNull(AfricanCurrency.parseAmount(""))
        assertNull(AfricanCurrency.parseAmount("hello"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Short Codes — M-Pesa style abbreviations
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `KES has correct short codes`() {
        assertEquals(100L, AfricanCurrency.KES.mshortCodes["mia"])
        assertEquals(1000L, AfricanCurrency.KES.mshortCodes["elfu"])
        assertEquals(100_000L, AfricanCurrency.KES.mshortCodes["laki"])
        assertEquals(1_000_000L, AfricanCurrency.KES.mshortCodes["milioni"])
        assertEquals(10_000_000L, AfricanCurrency.KES.mshortCodes["ngiri"])
    }

    @Test
    fun `KES has correct subunit factor`() {
        assertEquals(100, AfricanCurrency.KES.subunitFactor)
    }

    @Test
    fun `KES has correct common denominations`() {
        val denoms = AfricanCurrency.KES.commonDenominations
        assertTrue(denoms.contains(50L))
        assertTrue(denoms.contains(100L))
        assertTrue(denoms.contains(500L))
        assertTrue(denoms.contains(1000L))
        assertTrue(denoms.contains(5000L))
    }

    // ═══════════════════════════════════════════════════════════════════
    // CurrencyPreference
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `CurrencyPreference has sensible defaults`() {
        val pref = CurrencyPreference(primaryCurrency = AfricanCurrency.KES)
        assertEquals(AfricanCurrency.KES, pref.primaryCurrency)
        assertNull(pref.secondaryCurrency)
        assertEquals(1.0, pref.exchangeRate, 0.001)
    }

    @Test
    fun `CurrencyPreference supports cross-border traders`() {
        val pref = CurrencyPreference(
            primaryCurrency = AfricanCurrency.KES,
            secondaryCurrency = AfricanCurrency.TZS,
            exchangeRate = 25.0  // 1 KES ≈ 25 TZS
        )
        assertEquals(AfricanCurrency.KES, pref.primaryCurrency)
        assertEquals(AfricanCurrency.TZS, pref.secondaryCurrency)
        assertEquals(25.0, pref.exchangeRate, 0.001)
    }
}
