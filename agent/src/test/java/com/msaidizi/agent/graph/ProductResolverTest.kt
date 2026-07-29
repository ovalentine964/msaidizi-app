package com.msaidizi.agent.graph

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ProductResolver] — contextual entity resolution.
 *
 * Tests all resolution signals:
 * 1. Exact synonym match
 * 2. Fuzzy string match (Levenshtein)
 * 3. Phonetic match (Swahili Soundex)
 * 4. Partial/substring match
 * 5. Edge cases and unknown products
 */
class ProductResolverTest {

    private lateinit var resolver: ProductResolver

    @Before
    fun setup() {
        resolver = ProductResolver()
    }

    // ═══════════════════════════════════════════════════════════════
    //  SIGNAL 1: EXACT SYNONYM MATCH
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `exact match - Swahili tomato`() = runTest {
        val result = resolver.resolve("nyanya")
        assertEquals("Vegetables", result.category)
        assertEquals(Method.EXACT_SYNONYM, result.method)
        assertEquals(1.0f, result.confidence, 0.01f)
    }

    @Test
    fun `exact match - English tomato`() = runTest {
        val result = resolver.resolve("tomato")
        assertEquals("Vegetables", result.category)
        assertEquals(Method.EXACT_SYNONYM, result.method)
    }

    @Test
    fun `exact match - plural tomatoes`() = runTest {
        val result = resolver.resolve("tomatoes")
        assertEquals("Vegetables", result.category)
        assertEquals(Method.EXACT_SYNONYM, result.method)
    }

    @Test
    fun `exact match - case insensitive`() = runTest {
        val result = resolver.resolve("NYANYA")
        assertEquals("Vegetables", result.category)
        assertEquals(Method.EXACT_SYNONYM, result.method)
    }

    @Test
    fun `exact match - with whitespace`() = runTest {
        val result = resolver.resolve("  sukuma  ")
        assertEquals("Vegetables", result.category)
        assertEquals(Method.EXACT_SYNONYM, result.method)
    }

    @Test
    fun `exact match - dairy products`() = runTest {
        assertEquals("Dairy", resolver.resolve("maziwa").category)
        assertEquals("Dairy", resolver.resolve("milk").category)
    }

    @Test
    fun `exact match - staples`() = runTest {
        assertEquals("Staples", resolver.resolve("unga").category)
        assertEquals("Staples", resolver.resolve("rice").category)
        assertEquals("Staples", resolver.resolve("sugar").category)
        assertEquals("Staples", resolver.resolve("salt").category)
    }

    @Test
    fun `exact match - household`() = runTest {
        assertEquals("Household", resolver.resolve("sabuni").category)
        assertEquals("Household", resolver.resolve("soap").category)
    }

    @Test
    fun `exact match - meat and fish`() = runTest {
        assertEquals("Meat", resolver.resolve("nyama").category)
        assertEquals("Fish", resolver.resolve("samaki").category)
    }

    @Test
    fun `exact match - fruits`() = runTest {
        assertEquals("Fruits", resolver.resolve("maembe").category)
        assertEquals("Fruits", resolver.resolve("ndizi").category)
        assertEquals("Fruits", resolver.resolve("banana").category)
    }

    // ═══════════════════════════════════════════════════════════════
    //  SIGNAL 2: FUZZY STRING MATCH (Levenshtein)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `fuzzy match - single typo in tomato`() = runTest {
        val result = resolver.resolve("tomatoe") // common misspelling
        assertEquals("Vegetables", result.category)
        assertEquals(Method.FUZZY_STRING, result.method)
        assertTrue("Confidence should be > 0.4", result.confidence > 0.4f)
    }

    @Test
    fun `fuzzy match - typo in sukuma`() = runTest {
        val result = resolver.resolve("skuma") // missing 'u'
        assertEquals("Vegetables", result.category)
        // Could be exact (it's in the synonym list) or fuzzy
        assertNotNull(result.category)
    }

    @Test
    fun `fuzzy match - typo in mchele`() = runTest {
        val result = resolver.resolve("mchle") // missing 'e'
        assertEquals("Staples", result.category)
    }

    @Test
    fun `fuzzy match - typo in maziwa`() = runTest {
        val result = resolver.resolve("maziwaa") // extra 'a'
        assertEquals("Dairy", result.category)
    }

    @Test
    fun `fuzzy match - typo in sabuni`() = runTest {
        val result = resolver.resolve("sabni") // missing 'u'
        assertEquals("Household", result.category)
    }

    // ═══════════════════════════════════════════════════════════════
    //  SIGNAL 3: PHONETIC MATCH (Swahili Soundex)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `phonetic encoding - consistent for same word`() {
        val code1 = resolver.swahiliSoundex("nyanya")
        val code2 = resolver.swahiliSoundex("nyanya")
        assertEquals(code1, code2)
    }

    @Test
    fun `phonetic encoding - handles Swahili digraphs`() {
        // 'ny' is a single phoneme in Swahili
        val code = resolver.swahiliSoundex("nyanya")
        assertNotNull(code)
        assertEquals(4, code.length)
    }

    @Test
    fun `phonetic encoding - handles 'ch' digraph`() {
        val code = resolver.swahiliSoundex("chai")
        assertNotNull(code)
        assertEquals(4, code.length)
    }

    @Test
    fun `phonetic encoding - handles 'sh' digraph`() {
        val code = resolver.swahiliSoundex("shaba")
        assertNotNull(code)
        assertEquals(4, code.length)
    }

    @Test
    fun `phonetic encoding - empty string`() {
        assertEquals("", resolver.swahiliSoundex(""))
    }

    @Test
    fun `phonetic encoding - single character`() {
        val code = resolver.swahiliSoundex("a")
        assertEquals(4, code.length) // padded to 4
    }

    // ═══════════════════════════════════════════════════════════════
    //  SIGNAL 5: PARTIAL/SUBSTRING MATCH
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `partial match - query contains synonym`() = runTest {
        val result = resolver.resolve("fresh tomatoes")
        assertEquals("Vegetables", result.category)
        assertEquals(Method.PARTIAL, result.method)
    }

    @Test
    fun `partial match - synonym contains query`() = runTest {
        val result = resolver.resolve("tomat")
        assertEquals("Vegetables", result.category)
        assertEquals(Method.PARTIAL, result.method)
    }

    // ═══════════════════════════════════════════════════════════════
    //  EDGE CASES
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `unknown product returns null category`() = runTest {
        val result = resolver.resolve("xyzunknownproduct")
        assertNull(result.category)
        assertEquals(Method.NONE, result.method)
        assertEquals(0.0f, result.confidence, 0.01f)
    }

    @Test
    fun `empty string returns null category`() = runTest {
        val result = resolver.resolve("")
        assertNull(result.category)
        assertEquals(Method.NONE, result.method)
    }

    @Test
    fun `blank string returns null category`() = runTest {
        val result = resolver.resolve("   ")
        assertNull(result.category)
        assertEquals(Method.NONE, result.method)
    }

    @Test
    fun `single character returns null category`() = runTest {
        val result = resolver.resolve("x")
        assertNull(result.category)
        assertEquals(Method.NONE, result.method)
    }

    @Test
    fun `bulk resolve works`() = runTest {
        val results = resolver.resolveAll(listOf("nyanya", "maziwa", "unknown"))
        assertEquals("Vegetables", results["nyanya"])
        assertEquals("Dairy", results["maziwa"])
        assertNull(results["unknown"])
    }

    @Test
    fun `get known categories returns all categories`() {
        val categories = resolver.getKnownCategories()
        assertTrue(categories.contains("Vegetables"))
        assertTrue(categories.contains("Fruits"))
        assertTrue(categories.contains("Dairy"))
        assertTrue(categories.contains("Staples"))
        assertTrue(categories.contains("Meat"))
        assertTrue(categories.contains("Fish"))
        assertTrue(categories.contains("Household"))
        assertTrue(categories.contains("Beverages"))
        assertTrue(categories.contains("Snacks"))
        assertTrue(categories.contains("Spices"))
        assertTrue(categories.size >= 10)
    }

    // ═══════════════════════════════════════════════════════════════
    //  LEVENSHTEIN DISTANCE (Internal Algorithm)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `levenshtein - identical strings`() {
        assertEquals(0, resolver.levenshtein("hello", "hello"))
    }

    @Test
    fun `levenshtein - empty strings`() {
        assertEquals(0, resolver.levenshtein("", ""))
        assertEquals(5, resolver.levenshtein("", "hello"))
        assertEquals(5, resolver.levenshtein("hello", ""))
    }

    @Test
    fun `levenshtein - single insertion`() {
        assertEquals(1, resolver.levenshtein("cat", "cats"))
    }

    @Test
    fun `levenshtein - single deletion`() {
        assertEquals(1, resolver.levenshtein("cats", "cat"))
    }

    @Test
    fun `levenshtein - single substitution`() {
        assertEquals(1, resolver.levenshtein("cat", "bat"))
    }

    @Test
    fun `levenshtein - multiple edits`() {
        assertEquals(3, resolver.levenshtein("kitten", "sitting"))
    }

    @Test
    fun `levenshtein - Swahili words`() {
        // "nyanya" → "nyanyaa" (one insertion)
        assertEquals(1, resolver.levenshtein("nyanya", "nyanyaa"))
        // "sukuma" → "skuma" (one deletion)
        assertEquals(1, resolver.levenshtein("sukuma", "skuma"))
    }

    // ═══════════════════════════════════════════════════════════════
    //  NEW CATEGORIES (Expanding beyond original 6)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `new category - beverages`() = runTest {
        assertEquals("Beverages", resolver.resolve("chai").category)
        assertEquals("Beverages", resolver.resolve("kahawa").category)
        assertEquals("Beverages", resolver.resolve("soda").category)
    }

    @Test
    fun `new category - snacks`() = runTest {
        assertEquals("Snacks", resolver.resolve("mandazi").category)
        assertEquals("Snacks", resolver.resolve("samosa").category)
        assertEquals("Snacks", resolver.resolve("bread").category)
    }

    @Test
    fun `new category - spices`() = runTest {
        assertEquals("Spices", resolver.resolve("haldi").category)
        assertEquals("Spices", resolver.resolve("jeera").category)
    }

    @Test
    fun `new products - expanded vegetables`() = runTest {
        assertEquals("Vegetables", resolver.resolve("pilipili").category)
        assertEquals("Vegetables", resolver.resolve("kabichi").category)
        assertEquals("Vegetables", resolver.resolve("karoti").category)
        assertEquals("Vegetables", resolver.resolve("tango").category)
    }

    @Test
    fun `new products - expanded fruits`() = runTest {
        assertEquals("Fruits", resolver.resolve("chungwa").category)
        assertEquals("Fruits", resolver.resolve("nanasi").category)
        assertEquals("Fruits", resolver.resolve("papai").category)
    }
}
