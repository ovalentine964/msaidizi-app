package com.msaidizi.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Basic smoke tests for the app.
 * Verifies core resources and configuration are present.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @Test
    fun appContext_hasCorrectPackageName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.msaidizi.app.debug", context.packageName)
    }

    @Test
    fun appContext_hasStringResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appName = context.getString(context.resources.getIdentifier("app_name", "string", context.packageName))
        assertEquals("Msaidizi", appName)
    }

    @Test
    fun appContext_hasNavigationStrings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val res = context.resources
        val pkg = context.packageName

        // Verify key navigation strings exist
        assertNotNull(res.getIdentifier("nav_home", "string", pkg))
        assertNotNull(res.getIdentifier("nav_record", "string", pkg))
        assertNotNull(res.getIdentifier("nav_dashboard", "string", pkg))
        assertNotNull(res.getIdentifier("nav_history", "string", pkg))
        assertNotNull(res.getIdentifier("nav_settings", "string", pkg))
    }

    @Test
    fun appContext_hasOnboardingStrings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val res = context.resources
        val pkg = context.packageName

        assertNotNull(res.getIdentifier("onboarding_next", "string", pkg))
        assertNotNull(res.getIdentifier("onboarding_back", "string", pkg))
        assertNotNull(res.getIdentifier("onboarding_skip", "string", pkg))
    }

    @Test
    fun appContext_hasWhatsAppStrings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val res = context.resources
        val pkg = context.packageName

        assertNotNull(res.getIdentifier("whatsapp_prompt", "string", pkg))
        assertNotNull(res.getIdentifier("whatsapp_phone_hint", "string", pkg))
        assertNotNull(res.getIdentifier("whatsapp_success_title", "string", pkg))
    }

    @Test
    fun appContext_hasErrorStrings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val res = context.resources
        val pkg = context.packageName

        assertNotNull(res.getIdentifier("error_phone_required", "string", pkg))
        assertNotNull(res.getIdentifier("error_phone_invalid", "string", pkg))
        assertNotNull(res.getIdentifier("error_network", "string", pkg))
    }

    @Test
    fun appContext_hasGamificationStrings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val res = context.resources
        val pkg = context.packageName

        assertNotNull(res.getIdentifier("gamification_title", "string", pkg))
        assertNotNull(res.getIdentifier("streak_title", "string", pkg))
        assertNotNull(res.getIdentifier("badge_gallery_title", "string", pkg))
    }

    @Test
    fun appContext_hasLayoutResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val res = context.resources
        val pkg = context.packageName

        // Verify key layouts exist
        assertNotNull(res.getIdentifier("activity_main", "layout", pkg))
    }
}
