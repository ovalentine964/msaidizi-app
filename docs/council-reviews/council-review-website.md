# Council Review: Angavu Intelligence Website Architecture

**Document Reviewed:** `architecture-website-superagent.md`
**Review Date:** 2026-07-24
**Council:** 5 Specialized Reviewers
**Verdict:** See below

---

## 1. Security Reviewer

**Approval Status:** APPROVED WITH CONDITIONS

### Key Findings

1. **CSP is too permissive on `script-src`.** `'unsafe-inline'` in the Content-Security-Policy allows inline JavaScript injection. If any user-generated content or reflected input reaches the page (e.g., via URL parameters for language toggles, analytics, or future form inputs), this is an XSS vector. The architecture has no mention of nonce-based or hash-based CSP.

2. **No HTTPS enforcement documented.** The site is on GitHub Pages (which supports HTTPS), but the architecture doesn't specify `Strict-Transport-Security` headers or enforce HTTPS in links. All `og:url` and canonical references should use `https://`.

3. **APK download integrity not addressed.** The download flow delivers a ~65MB APK with no mention of checksum verification, signature verification instructions, or download integrity hashes. A MITM or compromised CDN could serve a malicious APK.

4. **`for-businesses.html` has "Request Access" forms — no form security specified.** If these are implemented as HTML forms, there's no mention of CSRF protection, input sanitization, rate limiting, or spam prevention (honeypot, reCAPTCHA, etc.).

5. **API page mentions API keys but no security model.** The `api.html` section references authentication but provides no detail on key rotation, scope limitation, or revocation — critical for B2B customers.

### Risks

- **XSS via `unsafe-inline` script-src:** Medium risk. Mitigated by static site (no server-side rendering), but future dynamic features could introduce vectors.
- **APK supply chain attack:** High risk if download URL is hijacked or MITM'd. No integrity verification = trust-on-first-use with no verification.
- **Form spam/abuse:** Medium risk if "Request Access" forms go live without protection.

### Recommendations

1. Replace `'unsafe-inline'` in `script-src` with nonce-based CSP or move all inline scripts to external files (the architecture already plans `js/app.js` — eliminate inline `<style>` blocks for critical CSS by inlining only prehashed content).
2. Add `Strict-Transport-Security: max-age=31536000; includeSubDomains` header (via `<meta>` tag for GitHub Pages or custom headers if using Cloudflare).
3. Add SHA-256 checksum and PGP signature to every APK release. Display checksum on download page with verification instructions.
4. Specify form security: honeypot fields for spam, server-side validation (if using a form service like Formspree/Netlify Forms), and rate limiting.
5. Document API key security model: scopes, rotation policy, revocation process.

---

## 2. UX/UI Reviewer

**Approval Status:** APPROVED WITH CONDITIONS

### Key Findings

1. **The M-KOPA parallel is brilliant but risky.** The comparison to M-KOPA is compelling for investors and partners, but the primary audience (Mama Mboga, boda boda riders) likely doesn't know or care about M-KOPA's business model. The worker-facing pages (`for-workers.html`) should lead with *their* problem, not a corporate case study. The M-KOPA framing works for `for-businesses` and `for-government` but risks confusing workers.

2. **Download flow is excellent — one-tap, zero friction.** The lite-first progressive model (65MB → progressive model loading) is a massive improvement over the current ~500MB monolith. The voice-guided onboarding ("Habari! Mimi ni Msaidizi") is culturally appropriate and delightful.

3. **Mobile-first design is implied but not specified.** The architecture shows desktop-style layouts (side-by-side comparisons, horizontal timelines) but 90%+ of the target audience accesses the web via mobile. There's no responsive breakpoint strategy, no mobile wireframes, and no mention of touch target sizes (minimum 48×48px per WCAG).

4. **Language toggle placement could be better.** The toggle is buried in the navbar menu. For a Kiswahili-first audience that may need to switch to English for technical pages, the toggle should be more prominent — possibly in the hero section or as a floating button.

5. **The "stacking effect" timeline is the strongest conversion element.** The progressive unlock visualization (Day 1 → Year 1) is emotionally compelling and practically clear. This should be front and center, not buried below the fold.

### Risks

- **M-KOPA framing alienates workers:** If workers don't understand the reference, the hero section wastes their attention on an analogy instead of a benefit.
- **No mobile wireframes = design drift:** Without explicit mobile-first specs, engineers will build desktop layouts and retrofit mobile, resulting in poor mobile UX.
- **Progressive model download may confuse:** Users on data may not understand why the app is "downloading more" after install. The opt-in for data downloads needs crystal-clear UX.

### Recommendations

1. Restructure `for-workers.html`: Lead with "You don't know if you're making profit" (pain point), not the M-KOPA analogy. Move M-KOPA framing to `for-businesses` and `for-government` only.
2. Add explicit mobile wireframes for all pages. Specify breakpoints: mobile (<768px), tablet (768-1024px), desktop (>1024px). All hero sections must be single-column on mobile.
3. Make the language toggle a first-class UI element — visible in the hero, not hidden in a hamburger menu.
4. Add a progress indicator for model downloads: "Downloading voice model... 45MB of 45MB ✓" with a clear "Use data or wait for WiFi?" prompt.
5. Add touch target specifications: all interactive elements minimum 48×48px with 8px spacing.

---

## 3. Performance Reviewer

**Approval Status:** APPROVED WITH CONDITIONS

### Key Findings

1. **CSS split is good, but loading strategy is wrong.** The architecture preloads `base.css` and `components.css` but loads three separate CSS files. On slow 2G/3G connections (common in rural Kenya), three sequential CSS requests add 300-900ms of latency. Consider inlining critical CSS and async-loading the rest, or using a single concatenated file with cache-busting hashes.

2. **Google Fonts is a performance liability.** Loading Inter (6 weights!) and Playfair Display (3 weights) from Google Fonts means 9 font files × ~20-40KB each = 180-360KB of font data, plus DNS lookup to `fonts.googleapis.com` and `fonts.gstatic.com`. On African mobile networks (median 5-10 Mbps, often 1-2 Mbps), this adds 1-3 seconds. **Self-host the fonts** with `font-display: swap` and subset to Latin + Latin Extended (skip CJK/Arabic unless needed).

3. **No image optimization strategy.** The architecture references phone mockups, diagrams, OG images, and screenshots but specifies no format (WebP/AVIF?), no responsive `srcset`, no lazy loading, and no compression targets. A single unoptimized OG image at 1200×630 could be 200KB+.

4. **PWA manifest is mentioned but service worker strategy is absent.** The `manifest.json` is listed but there's no service worker for offline caching. For a site marketing an "offline-first" app, the website itself should work offline too. This is a credibility issue.

5. **No performance budget defined.** The document lists no targets for Largest Contentful Paint (LCP), First Input Delay (FID), Cumulative Layout Shift (CLS), or total page weight. Without budgets, performance will degrade during implementation.

### Risks

- **Slow page loads lose visitors:** Google data shows 53% of mobile users abandon sites that take >3 seconds. On 2G/3G in rural Kenya, the current architecture could easily exceed 5 seconds.
- **Font loading creates FOIT/FOUT:** Without `font-display: swap`, text may be invisible during font load. With it, there's a flash of unstyled text. Either way, it's a poor experience.
- **No offline website = hypocrisy:** Promoting an "offline-first" app from a website that doesn't work offline undermines the brand message.

### Recommendations

1. **Self-host fonts.** Download Inter (weights: 400, 500, 600, 700) and Playfair Display (600, 700). Serve as WOFF2 with `font-display: swap`. Estimated savings: 200-400ms on first load.
2. **Set performance budgets:**
   - LCP < 2.5s on 3G (simulated)
   - FID < 100ms
   - CLS < 0.1
   - Total page weight < 500KB (excluding APK download)
   - Time to Interactive < 3s on 3G
3. **Implement a service worker** for offline support. Cache the app shell (HTML, CSS, JS, fonts) for offline access. Use stale-while-revalidate for page content.
4. **Optimize images:**
   - Use WebP with JPEG fallback
   - Implement responsive images with `srcset`
   - Lazy-load all below-fold images
   - Target: hero image < 100KB, thumbnails < 30KB
5. **Concatenate CSS** into a single file with cache-busting hash, or inline critical CSS and async-load the remainder. Three render-blocking CSS files on mobile is too many.

---

## 4. SEO/Marketing Reviewer

**Approval Status:** APPROVED WITH CONDITIONS

### Key Findings

1. **Sitemap structure is strong.** The audience-segmented sitemap (`/for-workers`, `/for-businesses`, `/for-government`) creates natural keyword clusters and landing pages for different search intents. This is significantly better than the current flat structure.

2. **Missing critical SEO elements.** The architecture mentions `<meta description>` and Open Graph tags but omits:
   - `<link rel="canonical">` tags (essential for avoiding duplicate content across language variants)
   - JSON-LD structured data (Organization, SoftwareApplication, FAQPage)
   - Twitter Card meta tags
   - Sitemap.xml generation
   - Robots.txt specification
   - Internal linking strategy between pages

3. **Hreflang implementation is incomplete.** The example shows `hreflang="sw"` and `hreflang="en"` but uses query parameters (`?lang=sw`) for language variants. Google recommends separate URLs (e.g., `/sw/` prefix) or proper `hreflang` with `hreflang="x-default"`. The query parameter approach may not be properly indexed.

4. **Content keyword strategy is thin.** The keyword list has 9 keywords total. For a site targeting 3 audiences across 2+ languages, this needs expansion. No mention of long-tail keywords, competitor analysis, or content clusters.

5. **No content freshness strategy.** The site will be static GitHub Pages with no CMS. How will content be updated? Blog posts? Changelog? Market intelligence updates? Stale content loses rankings.

### Risks

- **Low discoverability:** Without structured data, sitemap.xml, and proper canonical tags, the site may not rank well even for branded queries.
- **Language confusion:** Improper hreflang implementation could cause Google to show the wrong language version to users.
- **No content velocity:** Static sites with no new content lose SEO momentum over time. After launch, rankings will plateau and decline without fresh content.

### Recommendations

1. **Add JSON-LD structured data** to every page:
   - `index.html`: Organization + WebSite schema
   - `download.html`: SoftwareApplication schema (with `applicationCategory`, `operatingSystem`, `offers`)
   - `for-businesses.html`: Service schema
   - `api.html`: APIReference schema
   - All pages: BreadcrumbList schema
2. **Generate `sitemap.xml`** listing all pages with `<lastmod>`, `<changefreq>`, and `<xhtml:link>` for hreflang.
3. **Add `<link rel="canonical">`** to every page. Use self-referencing canonicals.
4. **Expand keyword strategy** to 50+ keywords per audience segment, including long-tail variants. Target: "how to track business sales in kenya", "free business app for mama mboga", "informal economy data africa".
5. **Add a `/blog` or `/updates` section** for content freshness. Even quarterly updates (pilot results, feature announcements, market insights) will maintain SEO momentum.
6. **Add Twitter Card meta tags** (`twitter:card`, `twitter:title`, `twitter:description`, `twitter:image`) for social sharing visibility.

---

## 5. Accessibility Reviewer

**Approval Status:** APPROVED WITH CONDITIONS

### Key Findings

1. **Accessibility foundation is strong.** Skip links, ARIA labels, `focus-visible`, `prefers-reduced-motion`, `aria-live` regions for language changes — this is better than 90% of marketing sites. The current site already has good accessibility practices, and the architecture preserves them.

2. **Color contrast needs verification.** The design tokens specify `--text-primary: #f0ece4` on `--bg-primary: #1B4965`. This gives a contrast ratio of approximately 6.8:1 (passes AA). However, `--text-secondary: #b8ccd8` on `--bg-primary: #1B4965` gives approximately 4.2:1 — barely passes AA for normal text (4.5:1 required) and **fails for small text**. The green download button (`#22c55e` on dark background) needs verification too.

3. **RTL support is planned but not designed.** The architecture mentions Arabic (`ar`) as a future language with `dir: 'rtl'`, but no RTL stylesheet or layout specifications are provided. If RTL is a goal, it needs design treatment (mirrored layouts, RTL-aware margins/padding).

4. **Animated content has no alternatives.** The flywheel animation, stacking effect timeline, and phone mockup demos are all visual/animated. There's no text alternative or `<noscript>` fallback specified. Screen reader users and users with `prefers-reduced-motion` will miss the core content.

5. **Multilingual accessibility gaps.** When language switches via JS, the `lang` attribute on `<html>` updates (good), but inline language switches within content (e.g., Kiswahili text in an English page) aren't marked with `lang="sw"` attributes. Screen readers will pronounce Kiswahili text with English phonetics.

### Risks

- **Excluded users:** If secondary text contrast fails AA, low-vision users (a significant population in Kenya with high UV exposure and limited eye care) cannot read key content.
- **Animated content exclusion:** The flywheel and stacking effect are the site's most compelling content. If they're only available as animations, a significant portion of users (screen readers, reduced-motion, no-JS) will miss the core value proposition.
- **RTL abandonment:** If Arabic is listed as a future language but not properly designed, it creates a false promise.

### Recommendations

1. **Verify all color combinations** against WCAG AA (4.5:1 for normal text, 3:1 for large text). Fix `--text-secondary` — either lighten it to `#c8dce8` or darken the background. Document contrast ratios in the design system.
2. **Provide text alternatives for all animated content.** The flywheel diagram should have a `<details>`/`<summary>` text version. The stacking timeline should be a semantic `<ol>` with `<li>` elements, not just visual animations.
3. **Add `lang` attributes to inline language switches.** When Kiswahili text appears in an English page (or vice versa), wrap it in `<span lang="sw">` so screen readers pronounce it correctly.
4. **Design RTL layouts** if Arabic is a planned language. Specify mirrored component layouts, RTL-aware spacing, and test with actual RTL content.
5. **Add `prefers-color-scheme` support.** The site is dark-only. Users in bright outdoor environments (common in Kenya) may struggle to read dark-background content on phone screens. Consider a light mode option or at minimum ensure sufficient contrast in bright conditions.

---

## Council Verdict

**APPROVED WITH CONDITIONS**

### Conditions (Must Fix Before Engineering)

| # | Condition | Severity | Reviewer |
|---|-----------|----------|----------|
| 1 | **Replace `'unsafe-inline'` in CSP `script-src`** with nonce-based CSP or eliminate all inline scripts | High | Security |
| 2 | **Add APK checksum + signature verification** to download page | High | Security |
| 3 | **Self-host fonts** (Inter 400-700, Playfair Display 600-700 as WOFF2) | High | Performance |
| 4 | **Define performance budgets** (LCP <2.5s on 3G, total page <500KB) | High | Performance |
| 5 | **Add JSON-LD structured data** (SoftwareApplication, Organization, FAQPage) | High | SEO |
| 6 | **Generate `sitemap.xml`** with hreflang annotations | Medium | SEO |
| 7 | **Add `<link rel="canonical">`** to every page | Medium | SEO |
| 8 | **Verify all text/background contrast ratios** meet WCAG AA (4.5:1) | High | Accessibility |
| 9 | **Provide text alternatives** for flywheel animation and stacking timeline | Medium | Accessibility |
| 10 | **Add mobile wireframes** for all pages with responsive breakpoints | High | UX/UI |
| 11 | **Restructure `for-workers.html`** to lead with pain point, not M-KOPA analogy | Medium | UX/UI |
| 12 | **Implement service worker** for offline website support | Medium | Performance |

### Go/No-Go for Engineering

**YES — GO** ✅

The architecture is fundamentally sound. The M-KOPA proof-flywheel model is a strong narrative framework. The progressive download strategy (65MB lite → model loading) is excellent for the target market. The multilingual, voice-first, offline-first positioning is well-aligned with the audience.

The conditions above are **fixable during implementation** — they don't require architectural changes, only additions to the existing plan. The security and performance conditions should be addressed in Phase 1 (Foundation), while SEO and accessibility conditions can be addressed in Phase 4 (Polish).

**Strongest elements:**
- Progressive download model (lite APK → on-demand models)
- Proof flywheel narrative (Speak → Track → Prove → Unlock → Grow)
- Audience-segmented site structure (workers / businesses / government)
- Accessibility foundation (skip links, ARIA, reduced-motion)
- Privacy-first positioning (on-device AI, no accounts, no phone numbers)

**Biggest risk:**
The site must actually be fast on African mobile networks. If it loads in 5+ seconds on 3G, 50%+ of visitors will leave before seeing the flywheel. Performance is not optional — it's a conversion requirement.

---

*Council review complete. Architecture approved for engineering with 12 conditions.*
