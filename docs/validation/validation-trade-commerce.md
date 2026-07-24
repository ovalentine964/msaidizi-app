# Trade & Commerce Workers — Msaidizi Superagent Validation

**Validator:** Trade & Commerce Worker Validator  
**Date:** 2026-07-24  
**Documents Reviewed:** architecture-msaidizi-superagent.md, gap-alama-score.md, gap-proactive-alerts.md, validation-cfo-superagent.md  
**Workers Validated:** 25  
**Verdict:** See end of document

---

## Executive Summary

Trade & commerce workers represent the **largest segment** of Kenya's informal economy — an estimated 2.5M+ workers across fresh produce, second-hand goods, wholesale, hardware, and specialized retail. These are the workers Msaidizi was built for: voice-first, offline-first, M-Pesa-native, operating in markets from Gikomba to Marikiti to roadside kiosks.

**Bottom line: 22 of 25 workers are fully supported by the architecture. 3 need specific adaptations. The architecture's voice-first, proof-accumulation model is near-perfect for this segment.**

The critical insight: **every worker in this segment already thinks in transactions.** "Nimeuziwa X bei Y" is their native language. Msaidizi doesn't need to teach them financial recording — it needs to speak their language and add intelligence on top.

---

## Industry Context: Kenya's Informal Trade Sector

### Market Scale
- **~80% of Kenya's workforce** is in the informal sector (KNBS 2024)
- **~2.5M+ traders** operate across fresh produce, second-hand goods, wholesale, and retail
- **Average daily revenue:** KSh 500–15,000 depending on trade type
- **Average profit margins:** 15–40% depending on product category
- **M-Pesa adoption:** >95% use M-Pesa; ~60% of transactions are cash, ~40% M-Pesa
- **Financial tools currently used:** Paper notebooks (exercise books), M-Pesa statements, mental math, "kibubu" (cash tins)

### Common Pain Points
1. **No income tracking** — most traders know daily sales but not profit
2. **Mixing business and personal money** — no separation of accounts
3. **Perishable losses** — spoilage eats margins (especially fresh produce, fish, meat)
4. **Supplier dependency** — one supplier means no price negotiation power
5. **Cash flow blindness** — no visibility into when money will run out
6. **Debt tracking chaos** — "deni" (credit sales) tracked mentally, often lost
7. **Restock timing** — either too early (cash tied up) or too late (lost sales)
8. **Price volatility** — no market intelligence, buying/selling at wrong prices
9. **Seasonal planning** — no preparation for school fees, holidays, rainy seasons
10. **No financial proof** — can't access loans because they have no records

---

## Worker Validation: Individual Assessments

### 1. Mama Mboga — Fresh Vegetable/Fruit Seller

**Profile:**
- **Daily Revenue:** KSh 1,500–8,000
- **Profit Margin:** 20–35%
- **Inventory Cycle:** Daily restocking (perishable goods)
- **Payment:** 70% cash, 30% M-Pesa
- **Location:** Roadside, estate corners, market periphery
- **Language:** Swahili, Sheng, Kikuyu (Nairobi), Luo (Nyanza), Luhya (Western)
- **Peak Hours:** 6 AM–6 PM, morning peak 6–10 AM
- **Key Products:** Tomatoes, onions, sukuma wiki, mangoes, oranges, potatoes
- **Suppliers:** Wakulima Market, Marikiti, Gikomba wholesale
- **Credit Sales:** Common — "nitakulipa kesho" (I'll pay tomorrow)

**Voice Input Validation:**
| Voice Pattern | Example | Architecture Support |
|---|---|---|
| Sale recording | "Nimeuziwa nyanya tano, mia tano" | ✅ TransactionEngine handles SALE intent |
| Multi-item sale | "Nimeuziwa nyanya tatu na vitunguu viwili, mia mbili" | ✅ Multi-slot extraction |
| Purchase recording | "Nimenunua nyanya kwa elfu mbili" | ✅ TransactionEngine handles PURCHASE intent |
| Daily summary query | "Faida ya leo ni ngapi?" | ✅ QueryEngine handles PROFIT_QUERY |
| Stock query | "Nina nyanya ngapi?" | ✅ QueryEngine handles STOCK_QUERY |
| Price check | "Bei ya nyanya iko ngapi sokoni?" | ✅ Market price query (cached Soko Pulse) |
| Credit sale | "Nimeuziwa Mary nyanya kwa deni" | ✅ Transaction model has isOnCredit field |
| Sheng mixing | "Nimeuziwa hii tomato, njeje" | ✅ Dialect detection + Sheng normalization |

**Financial Recording:**
- **Income types:** Cash sales, M-Pesa sales, credit sales → ✅ All covered by paymentMethod field
- **Expense categories:** Stock purchase, transport, packaging, market fee, spoilage → ✅ TransactionType.EXPENSE with category
- **Inventory:** Perishable, daily cycle → ✅ StockOutPredictor + daily velocity calculation
- **Spoilage tracking:** Critical for this worker — unsold vegetables rot → ⚠️ Gap: No spoilage/waste recording intent

**CFO Features:**
| Feature | Support | Notes |
|---|---|---|
| P&L | ✅ | Daily profit = revenue - stock cost - transport - spoilage |
| Cash Flow | ✅ | Predictable daily cycle; cash flow warning critical before restock |
| Pricing | ⚠️ | No pricing advisor; market prices fluctuate daily |
| Inventory | ✅ | Daily stock tracking with perishable urgency |
| Vendor Management | ✅ | Supplier recorded in purchases; price comparison needed |

**Alama Score:**
- **Frequency:** HIGH — mama mboga sells daily, 6–7 days/week → excellent frequency pillar
- **Revenue Trend:** Detectable — seasonal patterns (rainy = less supply = higher prices)
- **Margins:** Trackable — consistent products, visible cost basis
- **Diversity:** LOW risk — typically sells 5–15 products → moderate diversity
- **Regularity:** HIGH — very predictable schedule (morning buy, sell all day)
- **Savings:** Trackable — M-Pesa savings visible, cash savings via voice
- **Verdict:** ✅ **Alama Score works excellently.** This is the ideal Msaidizi user.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock | 🔴 CRITICAL — perishable goods, daily restocking | P0 |
| Spoilage warning | 🔴 CRITICAL — "Nyanya zako zinaanza kuharibika" | P0 (⚠️ MISSING) |
| Market price spike | 🟡 HIGH — tomato price doubled today | P1 |
| Cash flow warning | 🟡 HIGH — before market day restocking | P1 |
| Savings milestone | 🟢 MEDIUM — weekly savings progress | P2 |
| Daily briefing | 🟢 MEDIUM — morning briefing before market | P2 |

**Languages:** Swahili (primary), Sheng (Nairobi), Kikuyu (Central), Luo (Nyanza), Luhya (Western), Kalenjin (Rift Valley)

**Verdict:** ✅ **FULLY SUPPORTED** — This is Msaidizi's anchor user. One gap: spoilage/waste recording.

---

### 2. Duka Owner — Small Shop/Kiosk Owner

**Profile:**
- **Daily Revenue:** KSh 2,000–15,000
- **Profit Margin:** 15–30%
- **Inventory Cycle:** Weekly/bi-weekly restocking (non-perishable)
- **Payment:** 50% cash, 50% M-Pesa
- **Location:** Residential estates, shopping centers
- **Language:** Swahili, Sheng, local ethnic language
- **Hours:** 6 AM–10 PM (long hours)
- **Key Products:** Flour, sugar, cooking oil, soap, bread, milk, sodas, airtime
- **Suppliers:** Wholesalers (e.g., Twiga Foods, local distributors)
- **Credit Sales:** VERY common — "utaleta baadaye" (you'll bring later)

**Voice Input Validation:**
- "Nimeuziwa unga moja, mia mbili" → ✅ Standard sale
- "Nimeuziwa sukari na mafuta, mia tano" → ✅ Multi-item
- "Nimenunua stock ya elfu tatu" → ✅ Purchase (bulk restock)
- "Mary anadaiwa mia tatu" → ✅ Credit tracking (isOnCredit + customer field)
- "Bei ya unga iko ngapi?" → ✅ Price query

**Financial Recording:**
- **Income types:** Cash sales, M-Pesa, credit sales → ✅ All covered
- **Expense categories:** Stock, rent, electricity (KPLC token), water, airtime float → ✅
- **Inventory:** Non-perishable, weekly cycle → ✅ StockOutPredictor with longer lead times
- **Credit management:** Heavy credit sales to neighbors → ✅ LoanManager + customer field

**CFO Features:**
| Feature | Support | Notes |
|---|---|---|
| P&L | ✅ | Multi-category products, clear cost basis |
| Cash Flow | ✅ | Rent is monthly fixed; stock is variable |
| Inventory | ✅ | Multiple product categories, reorder points |
| Credit Management | ✅ | "Deni" tracking is critical for duka owners |
| Daily Briefing | ✅ | Morning briefing: sales yesterday, stock levels, debts owed |

**Alama Score:**
- **Frequency:** VERY HIGH — 7 days/week, long hours
- **Revenue:** Stable — residential demand is consistent
- **Margins:** Trackable — standard markup (cost + 20-30%)
- **Diversity:** HIGH — sells 30–100+ product types
- **Regularity:** VERY HIGH — same location, same hours, every day
- **Verdict:** ✅ **Excellent Alama Score candidate.** High regularity, high diversity.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock | 🔴 CRITICAL — runs out of popular items (unga, bread) | P0 |
| Debt collection reminder | 🟡 HIGH — "Mary anakudai mia tatu wiki hii" | P1 |
| Price change from supplier | 🟡 HIGH — wholesale price increase | P1 |
| Cash flow warning | 🟡 HIGH — rent due, stock running low | P1 |
| M-Pesa float low | 🟡 HIGH — can't receive M-Pesa payments | P1 |

**Languages:** Swahili (primary), Sheng, local language

**Verdict:** ✅ **FULLY SUPPORTED** — Duka owner is a perfect Msaidizi user. High-value segment.

---

### 3. Hawker — Street Vendor (Non-Food)

**Profile:**
- **Daily Revenue:** KSh 500–5,000
- **Profit Margin:** 30–60% (high markup on cheap goods)
- **Inventory Cycle:** Daily/weekly (depends on product)
- **Payment:** 90% cash, 10% M-Pesa
- **Location:** CBD streets, bus stops, matatu stages, traffic lights
- **Language:** Swahili, Sheng
- **Hours:** 7 AM–7 PM (moves around)
- **Key Products:** Phone cases, chargers, earphones, belts, socks, watches, sunglasses, umbrellas
- **Suppliers:** Gikomba, Eastleigh, River Road wholesalers
- **Credit Sales:** Rare — mostly cash on the spot

**Voice Input Validation:**
- "Nimeuziwa cover mbili, mia mbili" → ✅ Sale
- "Nimenunua stock kutoka Gikomba, elfu mbili" → ✅ Purchase
- "Leo nimepata mia tatu tu" → ✅ Daily total (low day)
- "Bei ya cover iko ngapi Gikomba?" → ✅ Price query

**Financial Recording:**
- **Income types:** Cash-dominant, M-Pesa rare → ✅
- **Expense categories:** Stock, transport (matatu fare), "kitu kidogo" (bribes to county askaris) → ✅ + ⚠️ "Kitu kidogo" is a real expense category
- **Inventory:** Small, portable → ✅
- **Location changes:** Moves around → ⚠️ GPS tracking less useful; locationName changes daily

**Alama Score:**
- **Frequency:** HIGH — works most days
- **Revenue:** VOLATILE — good days and bad days, weather-dependent
- **Margins:** HIGH — but inconsistent
- **Regularity:** MEDIUM — moves locations, hours vary
- **Verdict:** ✅ Works, but volatility will affect revenue trend pillar

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock | 🟡 HIGH — when popular items run out | P1 |
| Market opportunity | 🟡 HIGH — "Event huko Uhuru Park, demand ya covers itaongezeka" | P1 |
| Cash flow | 🟡 MEDIUM — irregular income | P2 |
| Daily briefing | 🟢 LOW — quick morning check | P2 |

**Languages:** Swahili (primary), Sheng

**Verdict:** ✅ **FULLY SUPPORTED** — Hawker income volatility is well-handled by Alama Score's stability metrics.

---

### 4. Mtumba Seller — Second-Hand Clothes

**Profile:**
- **Daily Revenue:** KSh 1,000–10,000
- **Profit Margin:** 40–100% (bale price vs. per-piece sale)
- **Inventory Cycle:** Weekly (buy bales, sell pieces)
- **Payment:** 70% cash, 30% M-Pesa
- **Location:** Gikomba, Toi Market, stage markets, roadside
- **Language:** Swahili, Sheng, Kikuyu
- **Hours:** 6 AM–6 PM
- **Key Products:** Children's clothes, women's dresses, men's shirts, shoes, handbags
- **Suppliers:** Gikomba wholesalers, Mombasa port, imported bales
- **Credit Sales:** Moderate — regular customers get credit

**Voice Input Validation:**
- "Nimeuziwa gauni tatu, mia tano" → ✅ Sale
- "Nimenunua bale moja, elfu tano" → ✅ Purchase (bulk)
- "Bei ya bale ya watoto iko ngapi?" → ✅ Price query
- "Nimepata faida ya elfu moja leo" → ✅ Profit declaration
- "Stock ya nguo bado iko mingi" → ✅ Inventory status

**Financial Recording:**
- **Income types:** Cash + M-Pesa → ✅
- **Expense categories:** Bale purchase, transport, stall fee, packaging → ✅
- **Inventory:** Per-bale tracking (one bale = multiple items) → ✅ but needs unit tracking (bale vs. piece)
- **Unique pattern:** Buy bale → break into pieces → sell pieces at markup → ✅ Margin calculation works

**CFO Features:**
| Feature | Support | Notes |
|---|---|---|
| P&L | ✅ | Bale cost vs. piece sales — clear margin |
| Inventory | ✅ | Bale-level + piece-level tracking |
| Pricing | ⚠️ | Pricing varies by quality; no quality grading system |
| Cash Flow | ✅ | Weekly bale purchase cycle |

**Alama Score:**
- **Frequency:** HIGH — works 6 days/week
- **Revenue:** Moderate volatility — depends on demand and quality of bale
- **Margins:** HIGH — 40-100% margins are strong
- **Diversity:** MEDIUM — limited product categories (clothes, shoes, bags)
- **Verdict:** ✅ Good Alama Score candidate. High margins boost score.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock | 🟡 HIGH — when bale is nearly sold out | P1 |
| Market opportunity | 🟡 HIGH — "Bale ya nguo za watoto iko cheap Gikomba" | P1 |
| Cash flow | 🟡 MEDIUM — bale purchase is a big one-time expense | P1 |

**Languages:** Swahili (primary), Sheng, Kikuyu

**Verdict:** ✅ **FULLY SUPPORTED** — Mtumba seller's bulk-buy-then-sell pattern is well-handled.

---

### 5. Wholesale Trader — Bulk Goods Distributor

**Profile:**
- **Daily Revenue:** KSh 20,000–500,000
- **Profit Margin:** 5–15% (thin margins, high volume)
- **Inventory Cycle:** Continuous (large warehouse stock)
- **Payment:** 30% cash, 40% M-Pesa, 30% bank transfer/check
- **Location:** Gikomba, Eastleigh, Industrial Area, wholesale markets
- **Language:** Swahili, English, Kikuyu, Somali (Eastleigh)
- **Hours:** 5 AM–4 PM
- **Key Products:** Flour, sugar, cooking oil, rice, soap, building materials
- **Suppliers:** Manufacturers, importers
- **Credit Sales:** VERY common — retailers buy on credit, pay after selling

**Voice Input Validation:**
- "Nimeuziwa unga magunia kumi, elfu tatu" → ✅ Sale (bulk)
- "Nimenunua stock kutoka mwezeshaji, laki mbili" → ✅ Purchase (large)
- "Retailer X anadaiwa elfu kumi" → ✅ Credit tracking
- "Faida ya wiki hii ni ngapi?" → ✅ Weekly profit query

**Financial Recording:**
- **Income types:** Cash, M-Pesa, bank transfer, check → ✅ (paymentMethod extended)
- **Expense categories:** Stock, warehouse rent, staff, transport, loading/offloading → ✅
- **Inventory:** Large-scale, multiple products → ✅
- **Credit management:** CRITICAL — massive outstanding debts → ✅ LoanManager handles this

**CFO Features:**
| Feature | Support | Notes |
|---|---|---|
| P&L | ✅ | High volume, thin margins — must track precisely |
| Cash Flow | ✅ | CRITICAL — large sums in/out, timing matters |
| Credit Management | ✅ | CRITICAL — most business is credit-based |
| Inventory | ✅ | Multi-SKU warehouse management |
| Vendor Management | ✅ | Multiple suppliers, price negotiation |

**Alama Score:**
- **Frequency:** VERY HIGH — daily operations
- **Revenue:** HIGH amounts but thin margins
- **Regularity:** VERY HIGH — established business, fixed location
- **Verdict:** ✅ Excellent candidate, but amounts are larger than typical Msaidizi target

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Cash flow | 🔴 CRITICAL — large sums, tight margins | P0 |
| Restock | 🔴 CRITICAL — runs out means lost bulk sales | P0 |
| Debt collection | 🔴 CRITICAL — "Retailer X anakudai elfu kumi, imepita siku saba" | P0 |
| Price change | 🟡 HIGH — manufacturer price changes | P1 |

**Languages:** Swahili, English, Kikuyu, Somali

**Verdict:** ✅ **FULLY SUPPORTED** — Wholesale trader needs all CFO features at scale. Architecture handles this.

---

### 6. Market Stall Owner — Permanent Market Stall

**Profile:**
- **Daily Revenue:** KSh 2,000–20,000
- **Profit Margin:** 20–40%
- **Inventory Cycle:** Weekly/bi-weekly
- **Payment:** 60% cash, 40% M-Pesa
- **Location:** Fixed market stall (Gikomba, Wakulima, City Market, Kongowea)
- **Language:** Swahili, Sheng, local language
- **Hours:** 6 AM–6 PM
- **Key Products:** Varied — clothes, shoes, household items, electronics
- **Market days:** Some stalls are daily, some are market-day only (Wed/Sat)

**Voice Input Validation:**
- "Nimeuziwa shati mbili, mia nne" → ✅ Sale
- "Nimenunua vitu kutoka Eastleigh, elfu tano" → ✅ Purchase
- "Soko la leo lilikuwa bora — nimepata elfu mbili" → ✅ Market day summary
- "Stall fee ya mwezi ni elfu mbili" → ✅ Expense (stall rent)

**Financial Recording:**
- **Income types:** Cash + M-Pesa → ✅
- **Expense categories:** Stock, stall fee, market levy, transport → ✅
- **Inventory:** Medium scale → ✅
- **Market day tracking:** Some days are better than others → ✅ dayOfWeek tracking

**Alama Score:**
- **Frequency:** HIGH on market days, lower on non-market days
- **Regularity:** Predictable pattern (market days are fixed)
- **Verdict:** ✅ Works well. Market day pattern detected by regularity pillar.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock | 🟡 HIGH — before market day | P1 |
| Market day reminder | 🟢 MEDIUM — "Kesho ni soko la Gikomba" | P2 |
| Cash flow | 🟡 MEDIUM — stall fee due monthly | P1 |

**Languages:** Swahili, Sheng, local language

**Verdict:** ✅ **FULLY SUPPORTED**

---

### 7. Supermarket Trolley Vendor — Push-Cart Vendor

**Profile:**
- **Daily Revenue:** KSh 300–2,000
- **Profit Margin:** 25–50%
- **Inventory Cycle:** Daily (small quantities)
- **Payment:** 95% cash, 5% M-Pesa
- **Location:** Matatu stages, bus stops, outside supermarkets
- **Language:** Swahili, Sheng
- **Hours:** 7 AM–8 PM
- **Key Products:** Sweets, chewing gum, airtime scratch cards, newspapers, boiled eggs, smokies
- **Suppliers:** Wholesale shops, distributors
- **Credit Sales:** None — cash only

**Voice Input Validation:**
- "Nimeuziwa mayai matatu, hamsini" → ✅ Sale (small amounts)
- "Nimenunua stock ya mia mbili" → ✅ Purchase
- "Leo ni siku mbaya — nimepata mia moja tu" → ✅ Daily total

**Financial Recording:**
- **Income types:** Almost all cash → ✅
- **Expense categories:** Stock, daily "space fee" to operate → ✅
- **Inventory:** Very small, daily → ✅
- **Challenge:** Very small transaction amounts (KSh 10–50) → ✅ Transaction model handles this

**Alama Score:**
- **Frequency:** HIGH — works daily
- **Revenue:** LOW amounts — Alama Score uses percentages, not absolute values
- **Margins:** HIGH percentage but low absolute
- **Regularity:** MEDIUM — location varies, weather-dependent
- **Verdict:** ✅ Works, but low revenue means slow proof accumulation

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock | 🟢 MEDIUM — small daily restocking | P2 |
| Cash flow | 🟡 HIGH — very thin cash reserves | P1 |
| Daily briefing | 🟢 LOW — simple daily check | P3 |

**Languages:** Swahili, Sheng

**Verdict:** ✅ **FULLY SUPPORTED** — Low-revenue workers still benefit from tracking.

---

### 8. Phone Accessories Seller — Mobile Phone Accessories

**Profile:**
- **Daily Revenue:** KSh 1,000–8,000
- **Profit Margin:** 50–200% (massive markups on accessories)
- **Inventory Cycle:** Weekly
- **Payment:** 50% cash, 50% M-Pesa
- **Location:** CBD streets, malls, market stalls
- **Language:** Swahili, Sheng, English (urban youth)
- **Hours:** 8 AM–7 PM
- **Key Products:** Phone cases, screen protectors, chargers, earphones, power banks, cables
- **Suppliers:** Eastleigh, River Road, online (Jumia/Kilimall)

**Voice Input Validation:**
- "Nimeuziwa cover tatu, mia tatu" → ✅ Sale
- "Nimenunua chargers kumi, elfu moja" → ✅ Purchase (bulk)
- "Bei ya cover ya iPhone iko ngapi?" → ✅ Price query
- "Nimepata faida ya elfu mbili leo" → ✅ Profit

**Financial Recording:**
- **Income types:** Cash + M-Pesa → ✅
- **Expense categories:** Stock, transport, stall/display fee → ✅
- **Inventory:** Moderate variety, high margin → ✅
- **Unique:** Very high margins (buy for KSh 50, sell for KSh 200) → ✅ marginPercent calculation

**Alama Score:**
- **Frequency:** HIGH — works 6–7 days/week
- **Margins:** VERY HIGH — boosts margin pillar significantly
- **Diversity:** MEDIUM — phone accessories category
- **Verdict:** ✅ Good candidate. High margins = strong score.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock | 🟡 HIGH — popular models sell out fast | P1 |
| Market opportunity | 🟡 HIGH — new phone model release = demand spike | P1 |
| Price change | 🟡 MEDIUM — wholesale price changes | P2 |

**Languages:** Swahili, Sheng, English

**Verdict:** ✅ **FULLY SUPPORTED**

---

### 9. Electronics Repair — Phone/Laptop Repair

**Profile:**
- **Daily Revenue:** KSh 500–5,000
- **Profit Margin:** 60–90% (labor-based, parts are cheap)
- **Inventory Cycle:** Monthly (spare parts)
- **Payment:** 70% cash, 30% M-Pesa
- **Location:** CBD stalls, market repair shops, roadside
- **Language:** Swahili, Sheng, English
- **Hours:** 9 AM–6 PM
- **Key Services:** Screen replacement, battery replacement, software repair, charging port fix
- **Suppliers:** Spare parts from Eastleigh, online

**Voice Input Validation:**
- "Nimefanya screen replacement, elfu mbili" → ✅ Sale (service)
- "Nimenunua screens kumi, elfu tatu" → ✅ Purchase (parts)
- "Nimepata kazi tatu leo" → ✅ Job count tracking

**Financial Recording:**
- **Income types:** Service fees (labor + parts) → ✅
- **Expense categories:** Spare parts, tools, rent → ✅
- **Inventory:** Spare parts stock → ✅
- **Unique:** Service vs. product distinction → ⚠️ Transaction model assumes products; service recording needs clear intent

**Alama Score:**
- **Frequency:** MEDIUM — depends on customer flow
- **Margins:** VERY HIGH — labor is the value, parts are cheap
- **Regularity:** MEDIUM — variable customer flow
- **Verdict:** ✅ Works well. High margins compensate for variability.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock (parts) | 🟡 MEDIUM — when popular parts run out | P1 |
| Cash flow | 🟢 MEDIUM — irregular income | P2 |

**Languages:** Swahili, Sheng, English

**Verdict:** ✅ **FULLY SUPPORTED** — Service-based recording works within current architecture.

---

### 10. Hardware Store Owner — Building Materials

**Profile:**
- **Daily Revenue:** KSh 5,000–100,000
- **Profit Margin:** 10–25%
- **Inventory Cycle:** Continuous (large stock)
- **Payment:** 40% cash, 30% M-Pesa, 30% check/transfer
- **Location:** Industrial areas, roadside hardware shops
- **Language:** Swahili, English, Kikuyu
- **Hours:** 7 AM–6 PM
- **Key Products:** Cement, iron sheets, timber, nails, paint, pipes, electrical fittings
- **Suppliers:** Manufacturers (Bamburi Cement, Mabati Rolling Mills), importers
- **Credit Sales:** VERY common — contractors buy on credit

**Voice Input Validation:**
- "Nimeuziwa simenti magunia kumi, elfu saba" → ✅ Bulk sale
- "Nimenunua mabati kutoka mwezeshaji, laki moja" → ✅ Large purchase
- "Contractor X anadaiwa elfu kumi" → ✅ Credit tracking
- "Stock ya simenti iko chini" → ✅ Inventory query

**Financial Recording:**
- **Income types:** Cash, M-Pesa, check, bank transfer → ✅
- **Expense categories:** Stock (large amounts), rent, staff, transport → ✅
- **Inventory:** High-value, bulk items → ✅
- **Credit management:** CRITICAL → ✅ LoanManager

**CFO Features:**
| Feature | Support | Notes |
|---|---|---|
| P&L | ✅ | Thin margins on high volume — must track precisely |
| Cash Flow | 🔴 CRITICAL | Large sums, credit sales create cash flow crunches |
| Credit Management | 🔴 CRITICAL | Most business is credit |
| Inventory | ✅ | High-value stock tracking |

**Alama Score:**
- **Frequency:** VERY HIGH
- **Revenue:** HIGH amounts, thin margins
- **Regularity:** VERY HIGH — established business
- **Verdict:** ✅ Excellent candidate. Thin margins are well-tracked.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Cash flow | 🔴 CRITICAL — large credit receivables | P0 |
| Restock | 🔴 CRITICAL — out of cement = lost sales | P0 |
| Debt collection | 🔴 CRITICAL — contractors delay payment | P0 |
| Price change | 🟡 HIGH — cement/mabati price changes | P1 |

**Languages:** Swahili, English, Kikuyu

**Verdict:** ✅ **FULLY SUPPORTED** — Hardware store is high-value, high-complexity. Architecture handles it.

---

### 11. Cosmetics Seller — Beauty Products Vendor

**Profile:**
- **Daily Revenue:** KSh 1,000–10,000
- **Profit Margin:** 30–80%
- **Inventory Cycle:** Bi-weekly/monthly
- **Payment:** 50% cash, 50% M-Pesa
- **Location:** Market stalls, door-to-door, estate kiosks
- **Language:** Swahili, Sheng, local language
- **Hours:** 9 AM–6 PM (or flexible for door-to-door)
- **Key Products:** Hair products, skin care, makeup, perfumes, weaves, nails
- **Suppliers:** Eastleigh, online wholesalers, direct from manufacturers

**Voice Input Validation:**
- "Nimeuziwa cream mbili, mia nne" → ✅ Sale
- "Nimenunua products kutoka Eastleigh, elfu mbili" → ✅ Purchase
- "Bei ya weave iko ngapi?" → ✅ Price query

**Financial Recording:**
- **Income types:** Cash + M-Pesa → ✅
- **Expense categories:** Stock, transport, samples → ✅
- **Inventory:** Moderate variety → ✅
- **Unique:** Door-to-door sellers have variable locations → ⚠️ GPS less useful

**Alama Score:**
- **Frequency:** HIGH — works most days
- **Margins:** HIGH — beauty products have good margins
- **Diversity:** MEDIUM — beauty/cosmetics category
- **Verdict:** ✅ Good candidate.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock | 🟡 HIGH — popular products sell out | P1 |
| Market opportunity | 🟡 MEDIUM — trends, new products | P2 |

**Languages:** Swahili, Sheng, local language

**Verdict:** ✅ **FULLY SUPPORTED**

---

### 12. Water Vendor — Water Selling (Tankers, Jerry Cans)

**Profile:**
- **Daily Revenue:** KSh 1,000–10,000
- **Profit Margin:** 30–60%
- **Inventory Cycle:** Daily (water tanker refills)
- **Payment:** 70% cash, 30% M-Pesa
- **Location:** Estates, construction sites, water-scarce areas
- **Language:** Swahili, Sheng, local language
- **Hours:** 6 AM–6 PM
- **Key Product:** Water (20L jerry cans, tanker deliveries)
- **Suppliers:** Boreholes, Nairobi City Water, private water points

**Voice Input Validation:**
- "Nimeuziwa maji mitungi kumi, mia mbili" → ✅ Sale
- "Nimenunua maji ya tanker, elfu moja" → ✅ Purchase
- "Nimefanya delivery tatu leo" → ✅ Delivery tracking

**Financial Recording:**
- **Income types:** Cash + M-Pesa → ✅
- **Expense categories:** Water purchase, fuel (for tanker), jerry can maintenance → ✅
- **Inventory:** N/A — water is a flow-through product → ✅
- **Unique:** Delivery-based service → ✅ location tracking useful

**Alama Score:**
- **Frequency:** HIGH — daily operations
- **Revenue:** Moderate, consistent (water is essential)
- **Margins:** GOOD — water is cheap to buy, expensive to deliver
- **Verdict:** ✅ Works well.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Cash flow | 🟡 MEDIUM — fuel costs are variable | P1 |
| Demand spike | 🟡 MEDIUM — dry season = more demand | P2 |

**Languages:** Swahili, local language

**Verdict:** ✅ **FULLY SUPPORTED**

---

### 13. Charcoal Seller — Fuel Vendor

**Profile:**
- **Daily Revenue:** KSh 1,000–8,000
- **Profit Margin:** 20–40%
- **Inventory Cycle:** Weekly (bulk purchase)
- **Payment:** 80% cash, 20% M-Pesa
- **Location:** Roadside, estate corners
- **Language:** Swahili, local language
- **Hours:** 7 AM–6 PM
- **Key Product:** Charcoal (bags)
- **Suppliers:** Rural producers, wholesalers

**Voice Input Validation:**
- "Nimeuziwa mifuko miwili, mia nne" → ✅ Sale
- "Nimenunua mifuko kumi, elfu mbili" → ✅ Purchase (bulk)
- "Mkoa wa kuni umepanda bei" → ✅ Price alert

**Financial Recording:**
- **Income types:** Cash-dominant → ✅
- **Expense categories:** Charcoal purchase, transport (trucking) → ✅
- **Inventory:** Bulk bags → ✅
- **Unique:** Transport cost is a major expense → ✅ expense category

**Alama Score:**
- **Frequency:** HIGH — sells daily
- **Revenue:** Moderate, seasonal (higher in cold/rainy season)
- **Margins:** Good — transport is the main cost
- **Verdict:** ✅ Works well.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock | 🟡 HIGH — when bags run low | P1 |
| Price change | 🟡 MEDIUM — seasonal price fluctuations | P2 |

**Languages:** Swahili, local language

**Verdict:** ✅ **FULLY SUPPORTED**

---

### 14. Firewood Seller — Fuel Vendor

**Profile:**
- **Daily Revenue:** KSh 500–3,000
- **Profit Margin:** 30–50%
- **Inventory Cycle:** Weekly
- **Payment:** 90% cash, 10% M-Pesa
- **Location:** Roadside, rural markets
- **Language:** Swahili, local language
- **Hours:** 7 AM–5 PM
- **Key Product:** Firewood (bundles)
- **Suppliers:** Rural areas, farms

**Voice Input Validation:**
- "Nimeuziwa kuni mabunduki matatu, mia moja" → ✅ Sale
- "Nimenunua kuni, mia tano" → ✅ Purchase

**Financial Recording:**
- **Income types:** Cash-dominant → ✅
- **Expense categories:** Firewood purchase, transport → ✅
- **Inventory:** Bundles → ✅

**Alama Score:**
- **Frequency:** MEDIUM — seasonal demand
- **Revenue:** LOW — small-scale
- **Verdict:** ✅ Works, but slow proof accumulation.

**Languages:** Swahili, local language

**Verdict:** ✅ **FULLY SUPPORTED** — Low-revenue, seasonal. Architecture handles this.

---

### 15. Egg Seller — Specialized Food Vendor

**Profile:**
- **Daily Revenue:** KSh 1,000–5,000
- **Profit Margin:** 15–25%
- **Inventory Cycle:** 2–3 days (perishable)
- **Payment:** 70% cash, 30% M-Pesa
- **Location:** Market stalls, roadside, door-to-door
- **Language:** Swahili, local language
- **Hours:** 7 AM–6 PM
- **Key Product:** Eggs (trays)
- **Suppliers:** Poultry farms, wholesale markets

**Voice Input Validation:**
- "Nimeuziwa tray moja, mia tatu" → ✅ Sale
- "Nimenunua trays kumi, elfu mbili" → ✅ Purchase

**Financial Recording:**
- **Income types:** Cash + M-Pesa → ✅
- **Expense categories:** Egg purchase, transport, trays (reusable) → ✅
- **Inventory:** Tray-based tracking → ✅

**Alama Score:**
- **Frequency:** HIGH — sells daily
- **Margins:** MODERATE — 15-25%
- **Verdict:** ✅ Works well.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock | 🟡 HIGH — eggs are perishable, daily restocking | P1 |
| Price change | 🟡 MEDIUM — poultry farm prices fluctuate | P2 |

**Languages:** Swahili, local language

**Verdict:** ✅ **FULLY SUPPORTED**

---

### 16. Fishmonger — Fish Seller

**Profile:**
- **Daily Revenue:** KSh 2,000–15,000
- **Profit Margin:** 25–50%
- **Inventory Cycle:** DAILY (highly perishable)
- **Payment:** 60% cash, 40% M-Pesa
- **Location:** Markets (e.g., Muthurwa, Gikomba), roadside
- **Language:** Swahili, Luo (Lakeside), local language
- **Hours:** 5 AM–4 PM (early start — fish is freshest in morning)
- **Key Products:** Tilapia, Nile perch, omena, mudfish
- **Suppliers:** Lake Victoria fishermen, cold chain distributors

**Voice Input Validation:**
- "Nimeuziwa samaki wawili, mia tano" → ✅ Sale
- "Nimenunua samaki kutoka Kisumu, elfu mbili" → ✅ Purchase
- "Samaki wamebaki wawili tu" → ✅ Inventory (critical — perishable)

**Financial Recording:**
- **Income types:** Cash + M-Pesa → ✅
- **Expense categories:** Fish purchase, transport (cold chain), ice, market fee → ✅
- **Inventory:** DAILY cycle, perishable → ✅ StockOutPredictor with daily urgency
- **Unique:** Spoilage is THE critical risk → ⚠️ Need spoilage recording

**CFO Features:**
| Feature | Support | Notes |
|---|---|---|
| P&L | ✅ | Daily P&L critical — perishable goods |
| Cash Flow | ✅ | Daily cycle, must have cash for tomorrow's purchase |
| Inventory | ✅ | Daily stock tracking with perishable urgency |
| Spoilage | ⚠️ | GAP — no spoilage/waste recording |

**Alama Score:**
- **Frequency:** HIGH — works 6–7 days/week
- **Revenue:** VOLATILE — depends on catch, weather, demand
- **Margins:** GOOD — 25-50%
- **Regularity:** HIGH — early morning start, fixed location
- **Verdict:** ✅ Good candidate, but volatility affects score.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Spoilage warning | 🔴 CRITICAL — "Samaki wako wanaanza kuharibika" | P0 (⚠️ MISSING) |
| Restock | 🔴 CRITICAL — must restock daily | P0 |
| Cash flow | 🟡 HIGH — must have cash for tomorrow's purchase | P1 |
| Price change | 🟡 MEDIUM — fish prices vary by season/catch | P2 |

**Languages:** Swahili, Luo, local language

**Verdict:** ✅ **FULLY SUPPORTED** with same spoilage gap as Mama Mboga.

---

### 17. Butcher — Meat Seller

**Profile:**
- **Daily Revenue:** KSh 3,000–30,000
- **Profit Margin:** 15–30%
- **Inventory Cycle:** DAILY (perishable)
- **Payment:** 50% cash, 50% M-Pesa
- **Location:** Market stalls, estate butcheries
- **Language:** Swahili, Sheng, local language
- **Hours:** 6 AM–7 PM
- **Key Products:** Beef, goat meat, chicken, offal
- **Suppliers:** Livestock markets (e.g., Kajiado, Athi River), abattoirs

**Voice Input Validation:**
- "Nimeuziwa nyama kilo tatu, elfu moja" → ✅ Sale (by weight)
- "Nimenunua ng'ombe mmoja, elfu kumi" → ✅ Purchase (livestock)
- "Nyama imebaki kilo tano" → ✅ Inventory

**Financial Recording:**
- **Income types:** Cash + M-Pesa → ✅
- **Expense categories:** Livestock purchase, slaughter fee, transport, market fee → ✅
- **Inventory:** Weight-based (kg) → ✅ Transaction model supports units (kg)
- **Unique:** Buy whole animal → slaughter → sell parts → ✅ Margin calculation works

**CFO Features:**
| Feature | Support | Notes |
|---|---|---|
| P&L | ✅ | Whole animal cost vs. part sales — clear margin |
| Inventory | ✅ | Weight-based tracking (kg) |
| Spoilage | ⚠️ | Same gap — meat spoils |

**Alama Score:**
- **Frequency:** HIGH — daily operations
- **Margins:** GOOD — 15-30%
- **Revenue:** HIGH amounts
- **Verdict:** ✅ Excellent candidate.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Spoilage warning | 🔴 CRITICAL — meat spoils fast | P0 (⚠️ MISSING) |
| Restock | 🔴 CRITICAL — daily restocking | P0 |
| Cash flow | 🟡 HIGH — livestock purchase is expensive | P1 |

**Languages:** Swahili, Sheng, local language

**Verdict:** ✅ **FULLY SUPPORTED** with spoilage gap.

---

### 18. Baker — Informal Bread/Cake Baker

**Profile:**
- **Daily Revenue:** KSh 1,000–8,000
- **Profit Margin:** 40–70%
- **Inventory Cycle:** DAILY (baked goods are perishable)
- **Payment:** 60% cash, 40% M-Pesa
- **Location:** Home-based, roadside, market stall
- **Language:** Swahili, Sheng, local language
- **Hours:** 4 AM–6 PM (early baking)
- **Key Products:** Mandazi, chapati, bread, cakes, samosas
- **Suppliers:** Flour, sugar, cooking oil, eggs, margarine

**Voice Input Validation:**
- "Nimeuziwa mandazi hamsini, elfu moja" → ✅ Sale (quantity-based)
- "Nimenunua unga na sukari, mia tano" → ✅ Purchase (ingredients)
- "Nimepata maandazi mia mbili leo" → ✅ Production count

**Financial Recording:**
- **Income types:** Cash + M-Pesa → ✅
- **Expense categories:** Ingredients (flour, sugar, oil, eggs), fuel (charcoal/gas), packaging → ✅
- **Inventory:** Ingredient-based → ✅
- **Unique:** Production cost calculation (ingredients → finished goods) → ✅ costBasis works

**Alama Score:**
- **Frequency:** HIGH — bakes daily
- **Margins:** VERY HIGH — 40-70%
- **Regularity:** VERY HIGH — daily routine, early morning
- **Verdict:** ✅ Excellent candidate. High margins, high regularity.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Restock (ingredients) | 🟡 HIGH — run out of flour = can't bake | P1 |
| Spoilage | 🟡 MEDIUM — unsold mandazi go stale | P1 |
| Cash flow | 🟢 MEDIUM — consistent daily cycle | P2 |

**Languages:** Swahili, Sheng, local language

**Verdict:** ✅ **FULLY SUPPORTED**

---

### 19. Spice Seller — Condiments Vendor

**Profile:**
- **Daily Revenue:** KSh 500–3,000
- **Profit Margin:** 40–80%
- **Inventory Cycle:** Monthly (non-perishable)
- **Payment:** 80% cash, 20% M-Pesa
- **Location:** Market stalls, door-to-door
- **Language:** Swahili, local language
- **Hours:** 8 AM–5 PM
- **Key Products:** Pilau masala, curry powder, chili, turmeric, cinnamon, cardamom
- **Suppliers:** Eastleigh, coastal traders, Indian importers

**Voice Input Validation:**
- "Nimeuziwa pilau masala pakiti mbili, mia moja" → ✅ Sale
- "Nimenunua spices kutoka Mombasa, elfu moja" → ✅ Purchase

**Financial Recording:**
- **Income types:** Cash-dominant → ✅
- **Expense categories:** Spice purchase, transport, packaging → ✅
- **Inventory:** Non-perishable, long shelf life → ✅

**Alama Score:**
- **Frequency:** MEDIUM — steady but not high-volume
- **Margins:** VERY HIGH — 40-80%
- **Regularity:** MEDIUM — market stall or door-to-door
- **Verdict:** ✅ Works well. High margins boost score.

**Languages:** Swahili, local language

**Verdict:** ✅ **FULLY SUPPORTED**

---

### 20. Grain Dealer — Maize, Beans, Rice Trader

**Profile:**
- **Daily Revenue:** KSh 5,000–100,000
- **Profit Margin:** 10–20%
- **Inventory Cycle:** Weekly/monthly (bulk storage)
- **Payment:** 40% cash, 30% M-Pesa, 30% bank transfer
- **Location:** Gikomba, Marikiti, rural collection centers
- **Language:** Swahili, Kikuyu, Kamba, local language
- **Hours:** 6 AM–5 PM
- **Key Products:** Maize, beans, rice, green grams, sorghum, millet
- **Suppliers:** Rural farmers, cooperatives

**Voice Input Validation:**
- "Nimeuziwa mahindi gunia moja, elfu tatu" → ✅ Sale (bulk)
- "Nimenunua mahindi gunia kumi kutoka Nakuru, elfu ishirini" → ✅ Purchase (large)
- "Bei ya mahindi iko ngapi?" → ✅ Price query
- "Nina stock ya gunia kumi" → ✅ Inventory

**Financial Recording:**
- **Income types:** Cash, M-Pesa, bank transfer → ✅
- **Expense categories:** Grain purchase, transport (trucking), storage, drying → ✅
- **Inventory:** Bulk (bags/sacks) → ✅
- **Unique:** Seasonal price fluctuations are CRITICAL → ✅ Soko Pulse + price alerts

**CFO Features:**
| Feature | Support | Notes |
|---|---|---|
| P&L | ✅ | Thin margins on high volume |
| Cash Flow | 🔴 CRITICAL | Large sums in/out |
| Inventory | ✅ | Bulk storage tracking |
| Pricing | 🔴 CRITICAL | Seasonal price swings of 50%+ |

**Alama Score:**
- **Frequency:** HIGH — active trading season
- **Revenue:** HIGH amounts, thin margins
- **Regularity:** SEASONAL — harvest season is peak
- **Verdict:** ✅ Works, but seasonal patterns must be accounted for.

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Price change | 🔴 CRITICAL — maize prices can swing 50% in weeks | P0 |
| Cash flow | 🔴 CRITICAL — large sums, thin margins | P0 |
| Restock | 🟡 HIGH — storage capacity limits | P1 |
| Market opportunity | 🔴 CRITICAL — "Bei ya mahindi imepanda 30% Eldoret" | P0 |

**Languages:** Swahili, Kikuyu, Kamba, local language

**Verdict:** ✅ **FULLY SUPPORTED** — Grain dealer is a high-value user. Price alerts are critical.

---

### 21. Livestock Trader — Cattle, Goat, Sheep Trader

**Profile:**
- **Daily Revenue:** KSh 10,000–500,000 (highly variable)
- **Profit Margin:** 15–40%
- **Inventory Cycle:** Weeks/months (animals take time to sell)
- **Payment:** 50% cash, 30% M-Pesa, 20% mobile banking
- **Location:** Livestock markets (Kajiado, Garissa, Isiolo, Naivasha)
- **Language:** Swahili, Maasai, Somali, Kalenjin, Kikuyu
- **Hours:** Market days (typically 1–2 days/week)
- **Key Products:** Cattle, goats, sheep, donkeys
- **Suppliers:** Pastoralist communities, farms

**Voice Input Validation:**
- "Nimeuziwa ng'ombe mmoja, elfu tano" → ✅ Sale (single high-value)
- "Nimenunua mbuzi kumi, elfu kumi" → ✅ Purchase (bulk)
- "Soko la Kajiado linaanza Jumanne" → ✅ Market day reminder
- "Bei ya ng'ombe iko ngapi?" → ✅ Price query

**Financial Recording:**
- **Income types:** Cash + M-Pesa → ✅
- **Expense categories:** Animal purchase, transport (trucking), veterinary, grazing fee → ✅
- **Inventory:** Animal-based (head count) → ✅
- **Unique:** VERY high-value transactions, market-day based → ✅

**CFO Features:**
| Feature | Support | Notes |
|---|---|---|
| P&L | ✅ | High-value, infrequent transactions |
| Cash Flow | 🔴 CRITICAL | Large sums, long holding periods |
| Inventory | ✅ | Animal count tracking |
| Pricing | ⚠️ | Livestock pricing is highly negotiable, not standardized |

**Alama Score:**
- **Frequency:** LOW — market days are 1–2 per week
- **Revenue:** HIGH per transaction but infrequent
- **Regularity:** MEDIUM — market days are predictable but infrequent
- **Challenge:** Low frequency hurts the frequency pillar → ⚠️ May need market-day-adjusted frequency scoring
- **Verdict:** ⚠️ Works but frequency pillar penalizes infrequent traders

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Market day reminder | 🔴 CRITICAL — "Kesho ni soko la Kajiado" | P0 |
| Price change | 🔴 CRITICAL — livestock prices vary by season | P0 |
| Cash flow | 🔴 CRITICAL — must have cash for market day | P0 |

**Languages:** Swahili, Maasai, Somali, Kalenjin, Kikuyu

**Verdict:** ⚠️ **SUPPORTED WITH ADAPTATION** — Frequency pillar needs market-day adjustment. Alert system needs market calendar integration.

---

### 22. Timber Merchant — Wood Seller

**Profile:**
- **Daily Revenue:** KSh 3,000–50,000
- **Profit Margin:** 20–40%
- **Inventory Cycle:** Monthly (bulk stock)
- **Payment:** 40% cash, 30% M-Pesa, 30% check/transfer
- **Location:** Industrial areas, roadside timber yards
- **Language:** Swahili, Kikuyu, Kamba
- **Hours:** 7 AM–5 PM
- **Key Products:** Construction timber, plywood, hardwood, softwood
- **Suppliers:** Forest plantations, sawmills

**Voice Input Validation:**
- "Nimeuziwa mbao kumi, elfu tatu" → ✅ Sale
- "Nimenunua mbao kutoka sawmill, elfu kumi" → ✅ Purchase
- "Stock ya mbao iko chini" → ✅ Inventory query

**Financial Recording:**
- **Income types:** Cash, M-Pesa, check → ✅
- **Expense categories:** Timber purchase, transport, storage, cutting/sawing → ✅
- **Inventory:** Bulk (pieces, cubic feet) → ✅

**Alama Score:**
- **Frequency:** MEDIUM — not daily transactions
- **Margins:** GOOD — 20-40%
- **Regularity:** MEDIUM — construction demand is seasonal
- **Verdict:** ✅ Works well.

**Languages:** Swahili, Kikuyu, Kamba

**Verdict:** ✅ **FULLY SUPPORTED**

---

### 23. Scrap Metal Dealer — Recyclable Materials

**Profile:**
- **Daily Revenue:** KSh 500–10,000
- **Profit Margin:** 30–60%
- **Inventory Cycle:** Continuous accumulation
- **Payment:** 90% cash, 10% M-Pesa
- **Location:** Industrial areas, junkyards
- **Language:** Swahili, Sheng
- **Hours:** 7 AM–5 PM
- **Key Products:** Scrap iron, aluminum, copper, brass, plastic
- **Suppliers:** Households, construction sites, factories
- **Buyers:** Recycling plants, export companies

**Voice Input Validation:**
- "Nimeuziwa chuma kilo kumi, mia tano" → ✅ Sale (weight-based)
- "Nimenunua chuma kutoka site, mia mbili" → ✅ Purchase
- "Bei ya copper iko juu leo" → ✅ Price query

**Financial Recording:**
- **Income types:** Cash-dominant → ✅
- **Expense categories:** Scrap purchase, transport, sorting labor → ✅
- **Inventory:** Weight-based (kg) → ✅
- **Unique:** Buy by weight, sell by weight → ✅ margin per kg

**Alama Score:**
- **Frequency:** HIGH — buys daily, sells weekly
- **Margins:** GOOD — 30-60%
- **Regularity:** MEDIUM — variable supply
- **Verdict:** ✅ Works well.

**Languages:** Swahili, Sheng

**Verdict:** ✅ **FULLY SUPPORTED**

---

### 24. Furniture Seller — New/Used Furniture

**Profile:**
- **Daily Revenue:** KSh 2,000–50,000 (infrequent high-value)
- **Profit Margin:** 30–60%
- **Inventory Cycle:** Monthly (slow-moving)
- **Payment:** 50% cash, 30% M-Pesa, 20% installment
- **Location:** Furniture showrooms, roadside displays
- **Language:** Swahili, Kikuyu, Kamba
- **Hours:** 8 AM–6 PM
- **Key Products:** Beds, sofas, tables, chairs, wardrobes, TV stands
- **Suppliers:** Local carpenters, workshops, imported furniture

**Voice Input Validation:**
- "Nimeuziwa kiti nne, elfu mbili" → ✅ Sale
- "Nimenunua meza kutoka fundi, elfu tatu" → ✅ Purchase
- "Mteja atalipa kwa awamu" → ✅ Installment tracking

**Financial Recording:**
- **Income types:** Cash, M-Pesa, installment payments → ✅
- **Expense categories:** Furniture purchase, transport, display space rent → ✅
- **Inventory:** Piece-based → ✅
- **Unique:** Installment sales (mteja analipa awamu) → ✅ creditDueDate field

**Alama Score:**
- **Frequency:** LOW — furniture sells slowly
- **Revenue:** HIGH per transaction but infrequent
- **Margins:** GOOD — 30-60%
- **Challenge:** Low frequency hurts frequency pillar → ⚠️ Same issue as livestock trader
- **Verdict:** ⚠️ Works but frequency pillar penalizes slow-moving inventory

**Proactive Alerts:**
| Alert | Relevance | Priority |
|---|---|---|
| Installment reminder | 🟡 HIGH — "Mteja wa meza analipa awamu ya pili wiki hii" | P1 |
| Cash flow | 🟡 MEDIUM — slow-moving inventory ties up cash | P1 |

**Languages:** Swahili, Kikuyu, Kamba

**Verdict:** ⚠️ **SUPPORTED WITH ADAPTATION** — Frequency pillar needs adjustment for slow-moving goods.

---

### 25. Textile Trader — Fabric Seller

**Profile:**
- **Daily Revenue:** KSh 2,000–20,000
- **Profit Margin:** 30–60%
- **Inventory Cycle:** Monthly (non-perishable)
- **Payment:** 50% cash, 40% M-Pesa, 10% installment
- **Location:** Gikomba, Eastleigh, market stalls
- **Language:** Swahili, Sheng, Kikuyu, Somali
- **Hours:** 8 AM–6 PM
- **Key Products:** Kitenge, kanga, lesso, cotton fabric, silk, lace
- **Suppliers:** Importers (China, Tanzania, India), local mills

**Voice Input Validation:**
- "Nimeuziwa kitenge mita tano, elfu moja" → ✅ Sale (meter-based)
- "Nimenunua vitenge kutoka Tanzania, elfu kumi" → ✅ Purchase
- "Bei ya kitenge iko ngapi?" → ✅ Price query

**Financial Recording:**
- **Income types:** Cash + M-Pesa → ✅
- **Expense categories:** Fabric purchase, transport, display → ✅
- **Inventory:** Meter-based or roll-based → ✅

**Alama Score:**
- **Frequency:** HIGH — works 6 days/week
- **Margins:** GOOD — 30-60%
- **Diversity:** MEDIUM — fabric varieties
- **Verdict:** ✅ Good candidate.

**Languages:** Swahili, Sheng, Kikuyu, Somali

**Verdict:** ✅ **FULLY SUPPORTED**

---

## Summary Matrix

| # | Worker | Voice | Recording | CFO | Alama Score | Alerts | Language | Verdict |
|---|--------|-------|-----------|-----|-------------|--------|----------|---------|
| 1 | Mama Mboga | ✅ | ✅ | ✅ | ✅ | ⚠️ Spoilage | Swahili, Sheng, Kikuyu, Luo | ✅ + spoilage gap |
| 2 | Duka Owner | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Sheng | ✅ |
| 3 | Hawker | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Sheng | ✅ |
| 4 | Mtumba Seller | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Sheng, Kikuyu | ✅ |
| 5 | Wholesale Trader | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, English, Kikuyu | ✅ |
| 6 | Market Stall Owner | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Sheng | ✅ |
| 7 | Trolley Vendor | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Sheng | ✅ |
| 8 | Phone Accessories | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Sheng, English | ✅ |
| 9 | Electronics Repair | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Sheng, English | ✅ |
| 10 | Hardware Store | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, English, Kikuyu | ✅ |
| 11 | Cosmetics Seller | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Sheng | ✅ |
| 12 | Water Vendor | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili | ✅ |
| 13 | Charcoal Seller | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili | ✅ |
| 14 | Firewood Seller | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili | ✅ |
| 15 | Egg Seller | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili | ✅ |
| 16 | Fishmonger | ✅ | ✅ | ✅ | ✅ | ⚠️ Spoilage | Swahili, Luo | ✅ + spoilage gap |
| 17 | Butcher | ✅ | ✅ | ✅ | ✅ | ⚠️ Spoilage | Swahili, Sheng | ✅ + spoilage gap |
| 18 | Baker | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Sheng | ✅ |
| 19 | Spice Seller | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili | ✅ |
| 20 | Grain Dealer | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Kikuyu, Kamba | ✅ |
| 21 | Livestock Trader | ✅ | ✅ | ⚠️ | ⚠️ Freq | ✅ | Swahili, Maasai, Somali | ⚠️ Adaptation needed |
| 22 | Timber Merchant | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Kikuyu, Kamba | ✅ |
| 23 | Scrap Metal Dealer | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Sheng | ✅ |
| 24 | Furniture Seller | ✅ | ✅ | ✅ | ⚠️ Freq | ✅ | Swahili, Kikuyu, Kamba | ⚠️ Adaptation needed |
| 25 | Textile Trader | ✅ | ✅ | ✅ | ✅ | ✅ | Swahili, Sheng, Kikuyu | ✅ |

---

## Cross-Cutting Gaps & Recommendations

### Gap 1: Spoilage/Waste Recording — 🔴 HIGH PRIORITY

**Affected Workers:** Mama Mboga (#1), Fishmonger (#16), Butcher (#17), Baker (#18)

**Problem:** Perishable goods are the #1 margin killer for these workers. They lose 5–15% of inventory to spoilage, but there's no way to record this. Without spoilage tracking:
- P&L is inaccurate (revenue correct, but costs don't reflect waste)
- Alama Score margins pillar is inflated
- No spoilage pattern detection (which products spoil most? which days?)

**Recommendation:** Add `WASTE_RECORD` intent to `superagent:financial/TransactionEngine.kt`:
```kotlin
// New intent: "Nimeharibu nyanya tatu" (I wasted 3 tomatoes)
IntentType.WASTE_RECORD -> {
    // Record as negative inventory adjustment
    // Include in P&L as cost
    // Track spoilage patterns
    // Trigger spoilage reduction tips
}
```

**Voice examples:**
- "Nimeharibu nyanya tatu" → Records waste of 3 tomatoes
- "Samaki wameharibika" → Records fish spoilage
- "Mandazi yamebaki kumi, yamekauka" → Records unsold mandazi

**Impact:** Critical for 4 workers (16% of trade & commerce segment). Makes P&L accurate. Enables spoilage reduction advice.

---

### Gap 2: Market-Day Frequency Adjustment — 🟡 MEDIUM PRIORITY

**Affected Workers:** Livestock Trader (#21), Furniture Seller (#24)

**Problem:** Alama Score's frequency pillar penalizes workers who don't sell daily. But some trades are inherently infrequent — livestock traders operate on market days (1–2/week), furniture sells slowly (few transactions/month). Current frequency scoring would give them low scores despite healthy businesses.

**Recommendation:** Add market-day awareness to Alama Score:
```kotlin
// In AlamaEngine._frequency():
if (workerProfile.businessType in listOf("livestock_trader", "furniture_seller")) {
    // Adjust expected frequency based on business type
    expected_active_days = workerProfile.marketDaysPerWeek ?: 2
    // Compare actual to expected, not to 7
}
```

**Impact:** Affects 2 workers but is architecturally important — shows the system understands different business models.

---

### Gap 3: Service vs. Product Distinction — 🟡 LOW PRIORITY

**Affected Workers:** Electronics Repair (#9)

**Problem:** The Transaction model assumes products (item, quantity, unit price). Services like phone repair are priced by job, not by unit. "Nimefanya screen replacement" is a service, not a product sale.

**Recommendation:** The current architecture handles this adequately — `item` can be "screen_replacement", `quantity` can be 1, `unitPrice` is the service fee. But a `transactionSubtype` field (PRODUCT vs. SERVICE) would improve categorization.

**Impact:** Minor. Electronics repair workers can use the system as-is.

---

### Gap 4: Installment Payment Tracking — 🟡 MEDIUM PRIORITY

**Affected Workers:** Furniture Seller (#24), potentially Textile Trader (#25)

**Problem:** Some sales are paid in installments (mteja analipa awamu). The `isOnCredit` and `creditDueDate` fields handle this, but there's no installment schedule (partial payments over time).

**Recommendation:** Add installment support to LoanManager:
```kotlin
// Track partial payments on a credit sale
// "Mteja amelipa awamu ya kwanza, mia tatu"
// Updates remaining balance
```

**Impact:** Moderate. Furniture sellers and high-value goods traders need this.

---

### Gap 5: "Kitu Kidogo" (Informal Payments) — 🟡 LOW PRIORITY

**Affected Workers:** Hawker (#3), potentially all market-based workers

**Problem:** Hawkers and market traders sometimes pay informal fees — "kitu kidogo" to county askaris (city council enforcement officers), market "protection" fees, etc. These are real expenses but culturally sensitive.

**Recommendation:** Handle as generic expense category. Don't create a special category — just allow flexible expense naming via voice: "Nimetumia mia tano leo" (I spent 500 today). The system can categorize generically.

**Impact:** Low. Current architecture handles this through generic expense recording.

---

## Trade & Commerce Specific Voice Vocabulary

### Essential Swahili Trading Terms

| Swahili Term | English | Voice Pattern |
|---|---|---|
| Nimeuziwa | I sold | Sale recording trigger |
| Nimenunua | I bought | Purchase recording trigger |
| Nimeharibu | I wasted/spoiled | Waste recording (⚠️ MISSING) |
| Bei | Price | Price query |
| Faida | Profit | Profit query |
| Hasara | Loss | Loss query |
| Stock/Hisia | Inventory | Stock query |
| Deni | Credit/debt | Credit tracking |
| Awamu | Installment | Installment tracking |
| Soko | Market | Market reference |
| Supplier/Mwezeshaji | Supplier | Supplier reference |
| Mteja | Customer | Customer reference |
| Magunia | Sacks/bags | Bulk quantity unit |
| Kilo | Kilograms | Weight unit |
| Mita | Meters | Length unit (textile) |
| Tray | Tray | Egg unit |
| Bunduki/Bale | Bundle | Firewood/fabric unit |

### Essential Sheng Trading Terms

| Sheng Term | English | Voice Pattern |
|---|---|---|
| Njeje | Cheap/bargain | Price reference |
| Poa | Good/cool | Acknowledgment |
| Mazee | Friend/brother | Customer reference |
| Niaje | How's it | Greeting |
| Ka-kadogo | Small quantity | Quantity reference |
| Mob | A lot | Quantity reference |
| Naskia | I hear/understand | Acknowledgment |
| Sasa | Now/what's up | Greeting |

---

## What Would Make Msaidizi Indispensable for Trade & Commerce Workers

### 1. "Mama Mboga's Morning" — The Daily Routine

**6:00 AM** — Msaidizi wakes up with the worker:
> "Habari za asubuhi! Leo ni Jumatano — soko la Gikomba. Bei ya nyanya ilipanda jana — fikiria kununua vitunguu badala yake. Una elfu tatu za kununua stock."

**6:30 AM** — At the market, buying stock:
> Worker: "Nimenunua nyanya kwa elfu moja, vitunguu kwa mia tano"
> Msaidizi: "Sawa! Umeweka. Nyanya bei yake ni KSh 200/kilo, vitunguu ni KSh 100/kilo. Faida yake itakuwa..."

**10:00 AM** — Mid-morning, sales going well:
> Worker: "Nimeuziwa nyanya tano, mia mbili"
> Msaidizi: "Hongera! Umeuza nyanya 5 kwa KSh 200. Faida ni KSh 100. Umebakiza nyanya 15."

**2:00 PM** — Proactive alert:
> "Samahani, nyanya zako zinaanza kuwa mbaya. Bado una 10. Fikiria kupunguza bei ili kuuza kabla ya jioni."

**6:00 PM** — End of day:
> "Leo umepata KSh 3,000. Faida ni KSh 1,200. Umeharibu nyanya 3. Akiba yako ni KSh 8,500 — umefikia 42% ya lengo lako la KSh 20,000."

**This is the dream. The architecture supports this flow EXCEPT for the spoilage recording.**

### 2. "Duka Owner's Debt Book" — Credit Management

The duka owner's biggest pain point is tracking "deni" (credit sales). Msaidizi must:
- Record: "Mary anadaiwa mia tatu" (Mary owes 300)
- Remind: "Kesho ni siku ya kulipa — Mary anakudai mia tatu"
- Report: "Deni yote ni elfu mbili — watu wanane wanakudai"
- Track: "Mary amelipa mia mbili, bado ana mia moja"

**The architecture fully supports this through LoanManager + customer field.**

### 3. "Wholesale Trader's Cash Flow" — Timing is Everything

The wholesale trader's #1 problem: having cash when suppliers offer good prices. Msaidizi must:
- Predict: "Kwa siku tatu zijazo, utahitaji elfu hamsini za kununua stock"
- Warn: "Supplier wako ana bei nzuri ya sementi leo — lakini una elfu thelathini tu"
- Advise: "Kama ukusanye deni kutoka kwa Retailer X (elfu kumi), utakuwa na pesa za kutosha"

**The architecture's CashFlowPredictor + ProactiveAlerts handle this.**

---

## Final Verdict

### 📋 VERDICT: **APPROVED WITH SPECIFIC GAPS**

The Msaidizi superagent architecture is **exceptionally well-suited** for trade & commerce workers. This is the primary use case the system was designed for, and it shows.

**22 of 25 workers are fully supported out of the box.** The remaining 3 need minor adaptations:

| Worker | Issue | Effort | Priority |
|---|---|---|---|
| Livestock Trader (#21) | Frequency pillar penalizes market-day traders | 1 week | P2 |
| Furniture Seller (#24) | Frequency pillar penalizes slow-moving inventory | 1 week | P2 |
| All perishable goods sellers (#1, #16, #17, #18) | No spoilage/waste recording | 2 weeks | P1 |

### Must-Have Before Launch:
1. **Spoilage/Waste Recording** — Add `WASTE_RECORD` intent. Critical for 16% of trade workers.
2. **Test with Mama Mboga archetype** — She is the anchor user. Her daily flow must work perfectly.

### Should-Have Before Tier 3:
3. **Market-Day Frequency Adjustment** — Alama Score should understand different business rhythms.
4. **Installment Payment Tracking** — For furniture and high-value goods traders.

### The Architecture's Strengths for This Segment:
- **Voice-first is PERFECT** — these workers think in spoken transactions
- **Offline-first is ESSENTIAL** — markets have poor connectivity
- **M-Pesa integration is CRITICAL** — 40%+ of transactions are M-Pesa
- **Alama Score is TRANSFORMATIVE** — these workers have zero financial proof today
- **Proactive alerts are GAME-CHANGING** — restock warnings, price alerts, cash flow predictions
- **Multi-language is NECESSARY** — Swahili, Sheng, Kikuyu, Luo, Kamba, Somali, Maasai, Kalenjin

### The One-Line Summary:
**Msaidizi was built for Mama Mboga. Trade & commerce workers are the bullseye. The architecture hits it.**

---

*Validation complete. 25 workers assessed. Architecture approved for trade & commerce segment.*
