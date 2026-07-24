# Msaidizi Superagent Validation: Services & Skilled Trade Workers

> **Validator:** Services & Skilled Trade Validator
> **Date:** 2026-07-24
> **Documents Reviewed:** architecture-msaidizi-superagent.md, gap-alama-score.md, gap-proactive-alerts.md, gap-skill-crystallization.md
> **Workers Validated:** 35 service/skilled trade informal workers

---

## Executive Summary

This validation covers 35 service and skilled trade workers — the **fundi** economy of East Africa. These workers form the backbone of informal construction, repair, beauty, and domestic services across Kenya, Tanzania, and Uganda. They are fundamentally different from traders and food vendors: they sell **labor and skill**, not goods. Their financial patterns are project-based, not daily-sales-based. The Msaidizi superagent architecture must adapt to this reality.

**Key Findings:**
- ✅ **Architecture fits:** The superagent model handles skill-based workers well, with modifications to the Transaction model
- ⚠️ **Alama Score needs adaptation:** Project-based income ≠ daily sales frequency. The 8-pillar model needs a "project completion" pillar or weight adjustment
- ⚠️ **Voice vocabulary is critical:** Each trade has 50-200 specific tool/material terms that must be in the ASR vocabulary
- ✅ **Proactive alerts are highly valuable:** Tool maintenance, material price tracking, and seasonal demand alerts address real pain points
- ✅ **Skill crystallization is natural:** These workers have strong daily/weekly routines
- ⚠️ **CFO features need reimagining:** Job costing (not daily P&L) is the financial model for skilled trades

---

## Worker Classification Framework

### Trade Categories

| Category | Workers | Income Model | Key Distinction |
|---|---|---|---|
| **Mechanical Repair** | Auto mechanic, Motorcycle mechanic | Per-job pricing + parts markup | Diagnostic skill premium |
| **Construction Trades** | Electrician, Plumber, Carpenter, Welder, Roofer, Tiler, Painter, Mason, Plasterer | Per-project or daily rate + materials | Project-based, multi-day jobs |
| **Electronics Repair** | Phone repair, Computer repair, Watch repair | Per-repair pricing + parts | Diagnostic-first, parts-dependent |
| **Textile & Fashion** | Tailor, Dressmaker, Boutique owner, Shoemaker | Per-garment pricing + fabric | Custom work, fitting cycles |
| **Beauty & Grooming** | Barber, Hairdresser, Salon owner, Nail technician | Per-service pricing | Walk-in clients, appointment-based |
| **Creative Services** | Photographer, Videographer, DJ, MC/Emcee | Per-event pricing | Seasonal, booking-based |
| **Domestic Services** | Laundry worker, Ironing service, Cleaning service | Per-task or per-day pricing | Recurring clients, location-mobile |
| **Security & Labor** | Security guard, Watchman, Gardener, Plumber's assistant, Construction helper, Demolition worker | Daily/monthly wage | Fixed schedules, employer-dependent |

---

## Detailed Worker Validation

---

### 1. Fundi wa Magari — Auto Mechanic

**Profile:**
- **Location:** Roadside garages, industrial areas, parking lots
- **Income range:** KES 500-5,000/day (jobs), KES 1,000-3,000/day (routine)
- **Work pattern:** Irregular — feast or famine. Monday/Tuesday slow, Friday/Saturday busy
- **Typical tools:** Spanners, jack, diagnostic scanner, oil drain pan, torque wrench, multimeter
- **Materials:** Engine oil, brake pads, filters, spark plugs, belts, coolant

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimerepair gari ya mteja, brake pads na engine oil, elfu tatu" | SALE: brake pads + engine oil, KES 3,000, customer job | High |
| "Nimenunua filter tano, mia nne kila moja" | PURCHASE: filters ×5 @ KES 400 = KES 2,000 | High |
| "Leo nimefanya service ya gari tatu" | SALE: 3 services, need price follow-up | Medium |
| "Nimeharibu spanner ya 14mm" | EXPENSE: tool replacement, KES follow-up needed | Medium |
| "Mteja anadai atalipa kesho" | CREDIT: customer owes, due tomorrow | High |

**Trade Vocabulary (Kiswahili/Sheng):**

```
Tools: spana (spanner), jack, socket set, torque wrench, multimeter, kibiriti (wrench),
       pump, kombo (combination wrench), adjustable, pliers, screwdriver set
Parts: brake pad, engine oil, filter ya mafuta, spark plug, belt, radiator, clutch plate,
       bearing, gasket, piston, ring, valve, timing belt, fuel pump
Techniques: kuchemsha (to overheat), kuvuta (to tow), kurepair, kufanya service,
            kuchuja (to diagnose), kubadilisha (to change/replace), kupaka grease
Materials: mafuta (oil), grease, coolant, brake fluid, ATF, antifreeze
Sheng: machine (car), kuweka (to install), kuchomoa (to remove), ndogo (minor fix),
       kubwa (major job), service ya haraka (quick service)
```

**CFO Features — Job Costing Model:**

```
JOB: Full service — Toyota Fielder
├── Labor: KES 2,000 (set price)
├── Parts:
│   ├── Engine oil (4L): KES 1,800 (cost) → KES 2,200 (charged)
│   ├── Oil filter: KES 300 → KES 500
│   ├── Air filter: KES 250 → KES 400
│   └── Spark plugs ×4: KES 800 → KES 1,200
├── Total charged: KES 6,300
├── Total cost: KES 4,150
├── Profit: KES 2,150 (34% margin)
└── Time: 2.5 hours
→ Effective hourly rate: KES 860/hr
```

**Alama Score Adaptation:**
- **Income pillar:** Per-job income, not daily sales. Track completed jobs per week
- **Frequency pillar:** Track active work days, not transaction count. 3-5 jobs/day is normal
- **Margin pillar:** Parts markup + labor. Typical margin: 25-40%
- **Diversity pillar:** Service variety (oil change, brake repair, engine work, body work)
- **Challenge:** Long gaps between jobs. Weekend-heavy pattern distorts weekly averages

**Proactive Alerts:**

| Alert Type | Trigger | Value |
|---|---|---|
| **Tool maintenance** | "Spanner yako ya 14mm umetumia siku 45 bila kuiangalia" | Tools degrade, affect work quality |
| **Material price alert** | "Bei ya engine oil imepanda 15% wiki hii" | Adjust pricing before next job |
| **Seasonal demand** | "Msimu wa mvua unakaribia — brake jobs huongezeka 40%" | Stock up on brake parts |
| **Customer follow-up** | "Mteja wa gari ya Fielder alifanya service wiki 8 zilizopita" | Recurring revenue opportunity |
| **Cash flow warning** | "Umenunua parts elfu kumi leo, bado hujalipwa na mteja 3" | Track receivables |

**Skill Tracking:**
- **Skill badges:** Engine diagnosis, electrical systems, body work, AC repair, transmission
- **Experience levels:** Apprentice → Journeyman → Master Fundi
- **Certification tracking:** NTSA inspection license, manufacturer certifications

**Verdict: ✅ STRONG FIT with adaptations**
- Job-based costing replaces daily P&L
- Parts inventory tracking is critical
- Customer relationship management (repeat clients) matters more than walk-in traffic

---

### 2. Fundi wa Pikipiki — Motorcycle Mechanic

**Profile:**
- **Location:** Roadside boda boda repair points, market areas
- **Income range:** KES 300-2,000/day
- **Work pattern:** Quick jobs (15-60 min), high volume
- **Typical tools:** Spanner set, pliers, screwdriver, tire lever, spark plug wrench, pump
- **Materials:** Engine oil, spark plugs, brake shoes, tubes, chains, sprockets

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimefanya service ya pikipiki tano, mia tatu kila moja" | SALE: 5 services × KES 300 = KES 1,500 | High |
| "Nimenunua tube kumi, mia mbili kila moja" | PURCHASE: 10 tubes × KES 200 = KES 2,000 | High |
| "Nimepuncture tairi tatu" | SALE: 3 puncture repairs, need price | Medium |

**Trade Vocabulary:**
```
Parts: tairi (tire), tube, chain, sprocket, brake shoe, spark plug, clutch plate,
       accelerator cable, brake cable, headlight, indicator, side mirror, seat cover
Tools: spana, pliers, screwdriver, pump ya tairi, tire lever, spark plug wrench
Techniques: kupuncture (to patch), kubadilisha mafuta, kurepair brake, kupanga (to tune)
Sheng: pikipiki (motorcycle), boda boda (motorcycle taxi), kuweka tube mpya
```

**CFO — Quick Job Model:**
```
Daily: 8 jobs average
├── Puncture repair ×3 @ KES 100 = KES 300
├── Oil change ×2 @ KES 300 = KES 600
├── Brake adjustment ×2 @ KES 200 = KES 400
├── Chain replacement ×1 @ KES 500 = KES 500
├── Total revenue: KES 1,800
├── Parts cost: KES 600
├── Daily profit: KES 1,200
└── Monthly (25 days): KES 30,000
```

**Alama Score Adaptation:**
- High-frequency, low-value transactions — many proof points per day
- Volume-based income (more jobs = more income, not bigger jobs)
- Parts turnover is fast — inventory risk is low
- Boda boda culture means consistent demand

**Proactive Alerts:**
- **Parts restock:** Tube ya pikipiki inaisha — una 3 tu, wastani ni 5 kwa siku
- **Price alert:** Brake shoes bei mpya kutoka supplier wako
- **Demand spike:** Boda boda riders wanaongezeka area yako — ongeza stock
- **Seasonal:** Msimu wa mvua — tairi na tube zinauzwa zaidi

**Verdict: ✅ STRONG FIT — High volume, consistent demand**

---

### 3. Fundi wa Umeme — Electrician

**Profile:**
- **Location:** Construction sites, residential calls, commercial installations
- **Income range:** KES 1,000-5,000/day (residential), KES 2,000-10,000/day (commercial)
- **Work pattern:** Project-based — 1 day to 3 weeks per job
- **Typical tools:** Multimeter, wire stripper, pliers, screwdriver set, drill, conduit bender, tester
- **Materials:** Cable (various gauges), switches, sockets, circuit breakers, conduit, junction boxes, tape

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimekamilisha wiring ya nyumba — elfu kumi tano labor" | SALE: wiring job, KES 15,000 labor | High |
| "Nimenunua cable roll mbili, elfu tatu kila moja" | PURCHASE: 2 cable rolls × KES 3,000 = KES 6,000 | High |
| "Nimeinstall switch tano na socket tatu" | SALE: 5 switches + 3 sockets installed, need labor price | Medium |
| "Nimenunua circuit breaker mpya ya 30 amp" | PURCHASE: 30A breaker, need price | Medium |

**Trade Vocabulary:**
```
Tools: multimeter, tester, wire stripper, pliers, screwdriver set, drill, conduit bender,
       hammer, spirit level, fish tape, cable puller
Materials: cable (mm² gauges), switch, socket, circuit breaker (CB), MCB, RCD, conduit,
           junction box, distribution board (DB), tape ya umeme, wago connector,
           trunking, PVC pipe, earth rod
Terms: wiring, installation, rewiring, short circuit, overload, earth/ground,
       live wire, neutral, phase, wattage, amperage, voltage
Sheng: stima (electricity), kuchoma (to install electrical), kuwaka (to light up),
       kuzima (to switch off), fuse, breaker
```

**CFO — Project Job Costing:**
```
PROJECT: 3-Bedroom House Wiring
├── Materials:
│   ├── Cable 2.5mm² × 5 rolls: KES 15,000
│   ├── Cable 1.5mm² × 3 rolls: KES 6,000
│   ├── Sockets × 20: KES 6,000
│   ├── Switches × 15: KES 4,500
│   ├── Circuit breakers × 6: KES 9,000
│   ├── Distribution board: KES 5,000
│   ├── Conduit + accessories: KES 8,000
│   └── Other (tape, boxes, screws): KES 3,000
├── Total materials: KES 56,500
├── Labor charged: KES 25,000
├── Total project: KES 81,500
├── Material margin (15%): KES 8,475
├── Net profit: KES 33,475
└── Duration: 7 days
→ Effective daily rate: KES 4,782/day
```

**Alama Score Adaptation:**
- **Irregular income pattern:** Big payments at project milestones, not daily
- **High material costs:** Must track materials purchased vs. charged to client
- **Receivables tracking:** Clients pay in installments (deposit → progress → final)
- **Licensing value:** EETC license = higher rates = better Alama Score

**Proactive Alerts:**
- **Material price tracking:** Cable ya 2.5mm² imepanda bei — re-quote next project
- **Project follow-up:** Client wa nyumba ya 3-bed bado analipa — check payment
- **Tool maintenance:** Drill yako inaonyesha kuchoka — service before next big job
- **Seasonal demand:** December ni msimu wa wiring — watu wanakamilisha nyumba
- **Cash flow:** Umeinvest elfu 56 kwenye project, bado hujalipwa — plan cash flow

**Skill Tracking:**
- **Badges:** Residential wiring, commercial installation, industrial systems, solar installation, generator hookup
- **Levels:** Apprentice → Licensed Electrician → Master Electrician → Electrical Contractor

**Verdict: ✅ STRONG FIT — High-value projects, critical material tracking**

---

### 4. Fundi wa Bomba — Plumber

**Profile:**
- **Location:** Construction sites, residential/commercial calls
- **Income range:** KES 800-4,000/day
- **Work pattern:** Call-based + project-based
- **Typical tools:** Pipe wrench, basin wrench, pipe cutter, soldering torch, plunger, drain snake
- **Materials:** Pipes (PVC, copper, PPR), fittings, taps, valves, cement, sealant

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimerepair bomba ya jikoni, elfu moja labor" | SALE: pipe repair, KES 1,000 labor | High |
| "Nimenunua PVC pipe mizigo miwili, elfu mbili" | PURCHASE: PVC pipes, KES 2,000 | High |
| "Nimeinstall toilet mpya, jumla elfu tano" | SALE: toilet install, KES 5,000 total | High |

**Trade Vocabulary:**
```
Tools: pipe wrench, basin wrench, pipe cutter, soldering torch, plunger, drain snake,
       adjustable wrench, hacksaw, spirit level, tape measure
Materials: PVC pipe, PPR pipe, copper pipe, elbow, tee, coupling, reducer, valve,
           tap (magnetic, mixer), toilet, sink, shower, geyser, water tank,
           sealant, Teflon tape, cement ya bomba
Terms: leakage, blockage, installation, connection, drainage, sewerage,
       water pressure, hot water system
Sheng: bomba (pipe/plumbing), kuvuja (to leak), kuchimba (to dig/install),
       tanki (water tank), shower, flush
```

**CFO — Service + Project Model:**
```
Day 1: Emergency calls
├── Burst pipe repair: KES 1,500 (labor) + KES 500 (parts)
├── Tap replacement: KES 800 (labor) + KES 400 (tap)
├── Total: KES 3,200

Day 2-5: Bathroom installation project
├── Materials: KES 25,000
├── Labor: KES 8,000
├── Total: KES 33,000
├── Material margin: 15% = KES 3,750
└── Net profit: KES 11,750 over 4 days
```

**Proactive Alerts:**
- **Restock:** Cement ya bomba inaisha — 2 bags tu
- **Seasonal:** Msimu wa mvua — drainage jobs zinaongezeka
- **Customer follow-up:** Mteja wa bathroom installation — check if satisfied, offer maintenance
- **Material price:** Copper pipe bei mpya kutoka supplier

**Verdict: ✅ STRONG FIT — Emergency + project dual income model**

---

### 5. Fundi wa Mbao — Carpenter

**Profile:**
- **Location:** Workshops, construction sites, client homes
- **Income range:** KES 800-5,000/day
- **Work pattern:** Custom furniture (days-weeks) + repair jobs (hours)
- **Typical tools:** Saw, hammer, chisel set, plane, drill, measuring tape, square, clamps
- **Materials:** Timber (various types), plywood, nails, screws, varnish, paint, glue

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimekamilisha meza ya dining, elfu nane" | SALE: dining table, KES 8,000 | High |
| "Nimenunua mbao ya mvule mita kumi, elfu tatu" | PURCHASE: mvule timber 10m, KES 3,000 | High |
| "Nimepaka varnish meza" | EXPENSE/SALE: varnishing, need context | Medium |
| "Nimenunua plywood karatasi mbili" | PURCHASE: 2 plywood sheets, need price | Medium |

**Trade Vocabulary:**
```
Tools: jembe (saw), nyundo (hammer), chisel, plane, drill, measuring tape, square,
       clamps, rasp, sandpaper, router, circular saw
Materials: mbao (timber), plywood, MDF, particle board, nails (misumari), screws,
           varnish, paint, wood glue, laminate, edge banding, handles, hinges
Wood types: mvule (teak), muninga (mahogany), cypress, pine, eucalyptus, meranti
Techniques: kupiga (to hammer), kukata (to cut), kupaka (to coat/finish),
            kusandpaper, kujoin, kurepair, kurefinish
Sheng: kiti (chair), meza (table), kabati (cupboard), shelf, stand
```

**CFO — Custom Furniture Model:**
```
PROJECT: Custom Kitchen Cabinets
├── Materials:
│   ├── Plywood × 8 sheets: KES 24,000
│   ├── Hardware (hinges, handles): KES 5,000
│   ├── Edge banding: KES 2,000
│   ├── Varnish + paint: KES 3,000
│   └── Other: KES 1,000
├── Total materials: KES 35,000
├── Labor: KES 20,000
├── Total charged: KES 55,000
├── Material margin: 15% = KES 5,250
├── Net profit: KES 25,250
├── Duration: 10 days
└── Effective daily rate: KES 2,525/day
```

**Alama Score Adaptation:**
- Custom jobs = lumpy income. 2 weeks with no income, then KES 55,000
- Material waste tracking is important (offcuts = lost money)
- Skill level directly affects pricing (master carpenter charges 2-3× apprentice rate)

**Proactive Alerts:**
- **Material price:** Plywood bei mpya — adjust quotes accordingly
- **Seasonal:** December furniture demand spike — stock up on materials
- **Waste tracking:** Umepoteza plywood 2 sheets mwezi huu — review cutting patterns
- **Client follow-up:** Meza ya dining — mteja anahitaji matching chairs?

**Verdict: ✅ STRONG FIT — High-value custom work, critical material tracking**

---

### 6. Fundi wa Vyuma — Welder/Metalworker

**Profile:**
- **Location:** Roadside workshops, industrial areas
- **Income range:** KES 800-5,000/day
- **Work pattern:** Fabrication projects + repair jobs
- **Typical tools:** Welding machine (arc/MIG/TIG), grinder, hacksaw, clamps, measuring tools
- **Materials:** Steel bars, sheets, angle iron, electrodes, gas, paint

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimefanya geti la chuma, elfu kumi" | SALE: metal gate, KES 10,000 | High |
| "Nimenunua steel bar kumi, mia mbili kila moja" | PURCHASE: 10 steel bars × KES 200 = KES 2,000 | High |
| "Nimenunua electrode kibox" | PURCHASE: electrode box, need price | Medium |
| "Nimekata chuma kwa geti" | MATERIAL: steel cutting for gate | Medium |

**Trade Vocabulary:**
```
Tools: welding machine (arc, MIG, TIG), grinder, hacksaw, clamps, measuring tape,
       square, hammer ya chuma, drill, vise, anvil
Materials: steel bar, angle iron, flat bar, sheet metal, electrode, welding wire,
           gas (acetylene, oxygen), paint ya chuma, primer, thinner
Products: geti (gate), fence, security door, window grill, gate, tank stand,
           bed frame, table, shelf, stairs, railing
Techniques: kuweld (to weld), kukata (to cut), kubend (to bend), kupaka paint,
            kuchoma (to burn/cut with torch), kusmooth (to grind smooth)
Sheng: chuma (metal/iron), geti (gate), grill, stand
```

**CFO — Fabrication Model:**
```
PROJECT: Security Gate
├── Materials:
│   ├── Steel bars × 20: KES 4,000
│   ├── Angle iron × 8: KES 2,400
│   ├── Sheet metal: KES 3,000
│   ├── Electrodes: KES 800
│   ├── Paint + primer: KES 1,500
│   └── Hardware (hinges, lock): KES 2,000
├── Total materials: KES 13,700
├── Labor: KES 8,000
├── Total charged: KES 21,700
├── Material margin: 15% = KES 2,055
├── Net profit: KES 10,055
└── Duration: 4 days
```

**Proactive Alerts:**
- **Restock:** Electrode inaisha — 2 packets tu
- **Electricity:** Umeme umekatika mara 3 wiki hii — consider backup generator
- **Seasonal:** Security gate demand huongezeka festive season
- **Material price:** Steel prices fluctuate with global markets — track weekly

**Verdict: ✅ STRONG FIT — High-value fabrication, electricity dependency is unique risk**

---

### 7. Fundi wa Simu — Phone Repair Technician

**Profile:**
- **Location:** Market stalls, kiosks, roadside
- **Income range:** KES 500-3,000/day
- **Work pattern:** Quick repairs (15 min - 2 hours), high volume
- **Typical tools:** Screwdriver set (precision), tweezers, multimeter, heat gun, suction cup, spudger
- **Materials:** Screens, batteries, charging ports, flex cables, speakers, back covers

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimerepair screen ya Samsung, elfu mbili" | SALE: Samsung screen repair, KES 2,000 | High |
| "Nimenunua screen tano za iPhone, elfu moja kila moja" | PURCHASE: 5 iPhone screens × KES 1,000 = KES 5,000 | High |
| "Nimebadilisha battery ya Tecno, mia nne" | SALE: Tecno battery replacement, KES 400 | High |
| "Nimenunua charging port kumi" | PURCHASE: 10 charging ports, need price | Medium |

**Trade Vocabulary:**
```
Tools: screwdriver set (precision), tweezers, multimeter, heat gun, suction cup,
       spudger, soldering iron, microscope/loupe, UV lamp, ultrasonic cleaner
Parts: screen (LCD, OLED), battery, charging port (USB-C, Lightning, Micro-USB),
       flex cable, speaker, earpiece, camera, back cover, SIM tray, power button,
       volume button, vibrator, motherboard
Brands: Samsung, iPhone, Tecno, Infinix, Nokia, Xiaomi, Huawei, Oppo, Vivo
Techniques: kubadilisha screen, kusolder, kurepair motherboard, kuflash software,
            kukataja (to diagnose), kureprogram, kuunlock
Sheng: simu (phone), screen, battery, charger, kuchaji (to charge)
```

**CFO — Quick Repair Model:**
```
Daily: 12 repairs average
├── Screen replacement × 3 @ KES 1,500 avg = KES 4,500
├── Battery replacement × 4 @ KES 500 avg = KES 2,000
├── Charging port × 3 @ KES 600 avg = KES 1,800
├── Software fix × 2 @ KES 300 avg = KES 600
├── Total revenue: KES 8,900
├── Parts cost: KES 4,500
├── Daily profit: KES 4,400
└── Monthly (26 days): KES 114,400
```

**Alama Score Adaptation:**
- Very high transaction frequency (10-15/day) = many proof points
- Parts inventory is critical capital (KES 50,000-100,000 tied up in screens alone)
- Diagnostic skill = premium pricing for motherboard repairs
- Fast-moving inventory — screens for latest models, not old ones

**Proactive Alerts:**
- **Inventory alert:** Screen za Samsung A14 zinaisha — 2 tu zilizobaki
- **Price tracking:** iPhone 15 screen bei mpya kutoka Dubai
- **Demand alert:** Model mpya ya Tecno imeingia — ongeza screens za model hii
- **Seasonal:** Back to school — laptop repairs zinaongezeka

**Skill Tracking:**
- **Badges:** Screen repair, battery replacement, motherboard repair, software flashing, water damage recovery
- **Levels:** Basic repair → Advanced diagnostics → Micro-soldering → Board-level repair

**Verdict: ✅ STRONG FIT — High volume, inventory-critical, fast-moving parts**

---

### 8. Fundi wa Kompyuta — Computer Repair

**Profile:**
- **Location:** Tech markets, kiosks, mobile service
- **Income range:** KES 500-5,000/day
- **Work pattern:** Mix of quick fixes and multi-day repairs
- **Typical tools:** Screwdriver set, multimeter, thermal paste, USB drives, diagnostic software
- **Materials:** RAM, SSD/HDD, keyboards, screens, chargers, batteries, thermal paste

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimerepair laptop ya HP, keyboard mpya, elfu mbili" | SALE: HP keyboard repair, KES 2,000 | High |
| "Nimenunua SSD 256GB, elfu mbili" | PURCHASE: 256GB SSD, KES 2,000 | High |
| "Nimeinstall Windows na Office" | SALE: software installation, need price | Medium |

**Trade Vocabulary:**
```
Parts: RAM, SSD, HDD, keyboard, screen, charger, battery, motherboard, fan,
       hinge, touchpad, webcam, speaker, WiFi card, thermal paste
Software: Windows, Office, antivirus, drivers, BIOS, formatting, data recovery
Brands: HP, Dell, Lenovo, Asus, Acer, MacBook, Toshiba
Techniques: kuinstall, kureformat, kurecover data, kuchange parts, kuclean virus,
            kuupgrade RAM/SSD, kurepair motherboard
Sheng: laptop, desktop, printer, scanner, WiFi, internet
```

**Proactive Alerts:**
- **Inventory:** SSD 256GB inaisha — 3 tu
- **Price tracking:** RAM bei mpya kutoka supplier
- **Demand:** Wanasema watu wanahitaji laptop za school — ongeza stock ya budget laptops
- **Software licensing:** Windows license inakaribia expiry — renew

**Verdict: ✅ STRONG FIT — Growing demand, parts inventory critical**

---

### 9. Fundi wa Saa — Watch/Clock Repair

**Profile:**
- **Location:** Market stalls, kiosks
- **Income range:** KES 200-1,500/day
- **Work pattern:** Quick repairs, declining demand (digital watches)
- **Typical tools:** Loupe, precision screwdrivers, tweezers, spring bar tool, case opener
- **Materials:** Batteries, straps, crystals, movements, crowns

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimebadilisha battery ya saa tano, mia moja kila moja" | SALE: 5 batteries × KES 100 = KES 500 | High |
| "Nimenunua battery mpya tano, hamsini kila moja" | PURCHASE: 5 batteries × KES 50 = KES 250 | High |
| "Nimerepair saa ya mkono ya analog" | SALE: analog watch repair, need price | Medium |

**Trade Vocabulary:**
```
Tools: loupe/magnifying glass, precision screwdriver set, tweezers, spring bar tool,
       case opener, pin remover, crystal press
Parts: battery, strap (leather, metal, rubber), crystal (glass, mineral, sapphire),
       movement (quartz, mechanical), crown, stem, gasket, dial, hands
Techniques: kubadilisha battery, kurepair movement, kupolisha crystal,
            kureplace strap, kuset time
Sheng: saa (watch/clock), battery, strap, crystal
```

**CFO — Micro-Repair Model:**
```
Daily: 8-12 repairs
├── Battery replacement × 6 @ KES 100 = KES 600
├── Strap replacement × 2 @ KES 300 = KES 600
├── Crystal replacement × 1 @ KES 500 = KES 500
├── Movement repair × 1 @ KES 800 = KES 800
├── Total revenue: KES 2,500
├── Parts cost: KES 600
├── Daily profit: KES 1,900
└── Monthly (26 days): KES 49,400
```

**Alama Score Adaptation:**
- Low transaction values but high frequency
- Declining market (smartwatches replacing traditional watches)
- Skill diversification into jewelry repair could be tracked

**Proactive Alerts:**
- **Inventory:** Battery ya CR2032 inaisha — 10 tu
- **Diversification:** Saa za digital zinauzwa zaidi — consider adding digital watch repair
- **Market shift:** Watu wanaanza kutumia smartwatch — jifunze kurepair smartwatch

**Verdict: ⚠️ MODERATE FIT — Declining market, but still viable niche**

---

### 10. Fundi wa Mabati — Roofing Specialist

**Profile:**
- **Location:** Construction sites, residential areas
- **Income range:** KES 1,500-8,000/day
- **Work pattern:** Project-based (2-7 days per house)
- **Typical tools:** Tin snips, hammer, drill, measuring tape, level, chalk line, safety harness
- **Materials:** Mabati (corrugated iron), ridges, valleys, nails, screws, timber (trusses), waterproof membrane

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimekamilisha roofing ya nyumba, elfu ishirini labor" | SALE: roofing job, KES 20,000 labor | High |
| "Nimenunua mabati karatasi hamsini, elfu ishirini" | PURCHASE: 50 mabati sheets, KES 20,000 | High |
| "Nimenunua ridge cap kumi" | PURCHASE: 10 ridge caps, need price | Medium |

**Trade Vocabulary:**
```
Materials: mabati (corrugated iron/galvanized sheets), ridge cap, valley gutter,
           flashing, waterproof membrane (underlay), nails (galvanized), screws,
           timber (trusses, purlins), guttering, fascia board
Types: pre-painted, galvanized, gauge 28, gauge 30, tile-profile
Techniques: kukata mabati, kupiga misumari, kufunga ridge, kutengeneza gutter,
            kupima, kuchora (to mark)
Sheng: mabati (roofing sheets), paa (roof), nyumba (house)
```

**Proactive Alerts:**
- **Material price:** Mabati bei mpya kutoka mabati rolling — imepanda 8%
- **Seasonal:** Msimu wa mvua unakaribia — demand ya roofing inaongezeka
- **Cash flow:** Umebuy mabati elfu 20, client bado hajalipa deposit
- **Follow-up:** Nyumba ya mteja — angalia kama kuna leakage baada ya mvua

**Verdict: ✅ STRONG FIT — High-value projects, critical material cost tracking**

---

### 11. Fundi wa Tiles — Tiling Specialist

**Profile:**
- **Location:** Construction sites, residential renovations
- **Income range:** KES 1,000-4,000/day
- **Work pattern:** Project-based (1-5 days per room)
- **Typical tools:** Tile cutter, trowel, spirit level, spacers, grout float, mixing drill
- **Materials:** Tiles, cement, grout, adhesive, spacers, trim pieces

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimekamilisha tiles ya bathroom, elfu tatu labor" | SALE: bathroom tiling, KES 3,000 labor | High |
| "Nimenunua tiles karatasi kumi, mia mbili kila moja" | PURCHASE: 10 tiles × KES 200 = KES 2,000 | High |
| "Nimenunua grout na adhesive" | PURCHASE: grout + adhesive, need prices | Medium |

**Trade Vocabulary:**
```
Materials: tiles (floor, wall, porcelain, ceramic, vitrified), grout, adhesive (cement-based),
           spacers, trim pieces (edge, corner), waterproof membrane, cement screed
Tools: tile cutter (manual, electric), trowel (notched), spirit level, rubber mallet,
       grout float, mixing drill, sponge, chalk line
Techniques: kupima, kukata tiles, kupaka adhesive, kufunga tiles, kugrout, kusafisha
Sheng: tiles, floor, wall, bathroom, kitchen
```

**Proactive Alerts:**
- **Material price:** Tiles bei mpya — porcelain imepanda 10%
- **Seasonal:** December renovations — ongeza stock ya popular designs
- **Waste tracking:** Umepoteza tiles 5 kwa project — improve cutting technique
- **Customer follow-up:** Bathroom ya mteja — offer kitchen tiling

**Verdict: ✅ STRONG FIT — Project-based, material-intensive**

---

### 12. Painter — House/Wall Painter

**Profile:**
- **Location:** Construction sites, residential/commercial
- **Income range:** KES 800-3,000/day
- **Work pattern:** Project-based (1-10 days)
- **Typical tools:** Roller, brush, spray gun, sandpaper, scraper, masking tape, ladder
- **Materials:** Paint (various types), thinner, primer, putty, sandpaper

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimepaka rangi nyumba nzima, elfu nane labor" | SALE: full house painting, KES 8,000 labor | High |
| "Nimenunua drum la rangi, elfu tatu" | PURCHASE: paint drum, KES 3,000 | High |
| "Nimenunua roller na brush" | PURCHASE: roller + brush, need prices | Medium |

**Trade Vocabulary:**
```
Materials: rangi (paint), primer, putty, thinner, sandpaper, masking tape, roller,
           brush, spray gun, drop cloth, filler, sealant
Paint types: emulsion, gloss, satin, matt, textured, weatherproof, oil-based, water-based
Techniques: kupaka rangi, kuprime, kuputty, kusandpaper, kumask, kuspray, kurun roller
Sheng: rangi (paint), ukuta (wall), nyumba (house), kupaka (to paint/apply)
```

**Proactive Alerts:**
- **Material price:** Drum ya Crown paint imepanda — re-quote jobs
- **Seasonal:** Msimu wa mvua — painting jobs zinapunguka (paint doesn't dry well)
- **Demand:** December renovations — painting demand inaongezeka 30%
- **Waste tracking:** Rangi iliyopoteza 10% ya drum — improve mixing

**Verdict: ✅ STRONG FIT — Project-based, material tracking important**

---

### 13. Mason — Bricklayer/Construction

**Profile:**
- **Location:** Construction sites
- **Income range:** KES 800-2,500/day
- **Work pattern:** Daily rate or project-based
- **Typical tools:** Trowel, spirit level, plumb line, hammer, mortar board, wheelbarrow
- **Materials:** Bricks, cement, sand, ballast, water

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimejenga ukuta mita kumi, elfu mbili" | SALE: 10m wall, KES 2,000 | High |
| "Nimenunua cement mifuko kumi, mia saba kila moja" | PURCHASE: 10 cement bags × KES 700 = KES 7,000 | High |
| "Nimenunua matofali elfu mbili" | PURCHASE: 2,000 bricks, need price | Medium |

**Trade Vocabulary:**
```
Materials: matofali (bricks), cement, mchanga (sand), ballast (kokoto), maji (water),
           mortar, reinforcement bar (rebar), hardcore
Tools: trowel, spirit level, plumb line, hammer, mortar board, wheelbarrow, shovel,
       tape measure, string line
Techniques: kujenga (to build), kupima, kuchimba foundation, kuplaster, kufunga
            reinforcement, kumix mortar
Sheng: ukuta (wall), nyumba (house), foundation, slab, pillar
```

**Proactive Alerts:**
- **Material price:** Cement bei mpya — imepanda 5%
- **Seasonal:** Msimu wa joto — construction huongezeka
- **Cash flow:** Umenunua materials elfu 15, client bado hajalipa
- **Weather:** Mvua inatarajiwa kesho — funga site mapema

**Verdict: ✅ STRONG FIT — Daily rate model, material tracking essential**

---

### 14. Plasterer — Wall Plastering

**Profile:**
- **Location:** Construction sites
- **Income range:** KES 800-2,500/day
- **Work pattern:** Project-based, follows mason
- **Typical tools:** Trowel, float, hawk, spirit level, mixing drill
- **Materials:** Cement, sand, lime, water, waterproofing additive

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimeplaster ukuta wa nyumba, elfu tatu" | SALE: house plastering, KES 3,000 | High |
| "Nimenunua cement mifuko sita" | PURCHASE: 6 cement bags, need price | Medium |

**Trade Vocabulary:**
```
Materials: cement, mchanga (sand), chokaa (lime), maji (water), waterproofing additive,
           plaster mesh, corner bead
Tools: trowel, float, hawk, spirit level, mixing drill, water brush
Techniques: kuplaster, kumix, kupima, kufinishing, kusmooth, kupaka chokaa
Sheng: ukuta (wall), plaster, smooth
```

**Proactive Alerts:**
- **Seasonal:** Msimu wa mvua — plastering inahitaji muda zaidi kukauka
- **Material:** Cement bei — same as mason alerts
- **Coordination:** Mason amekamilisha ukuta — tayari kuplaster?

**Verdict: ✅ STRONG FIT — Project-based, material-intensive**

---

### 15. Fundi wa Kofia — Tailor (Traditional)

**Profile:**
- **Location:** Market stalls, workshops, roadside
- **Income range:** KES 500-3,000/day
- **Work pattern:** Custom orders (1-7 days per garment) + alterations
- **Typical tools:** Sewing machine, scissors, measuring tape, pins, chalk, iron
- **Materials:** Fabric (kitenge, kanga, cotton, silk), thread, zippers, buttons, lining

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimeshona kanzu tatu, elfu mbili kila moja" | SALE: 3 kanzu × KES 2,000 = KES 6,000 | High |
| "Nimenunua kitenge mita tano, elfu moja" | PURCHASE: 5m kitenge, KES 1,000 | High |
| "Nimenunua thread na zip" | PURCHASE: thread + zipper, need prices | Medium |

**Trade Vocabulary:**
```
Materials: kitenge, kanga, kanzu, fabric, thread (uzi), zipper, button, lining,
           elastic, interfacing, bias tape
Tools: sewing machine (manual, electric), scissors, measuring tape, pins, chalk,
       iron, seam ripper, bobbin
Garments: kanzu, kofia, buibui, kanzu ya ndoa (wedding), dashiki, shuka,
          kanzu ya jioni (evening wear)
Techniques: kupima, kukata, kushona, kupiga pasi (to iron), kurepair, kubadilisha
Sheng: kushona (to sew), kanzu, kofia (hat), nguo (clothes)
```

**CFO — Custom Garment Model:**
```
PROJECT: Wedding Kanzu
├── Materials:
│   ├── Premium kitenge: KES 3,000
│   ├── Lining: KES 500
│   ├── Thread, buttons, zipper: KES 300
│   └── Embroidery materials: KES 800
├── Total materials: KES 4,600
├── Labor: KES 5,000
├── Total charged: KES 9,600
├── Material margin: 15% = KES 690
├── Net profit: KES 5,690
└── Duration: 3 days
```

**Proactive Alerts:**
- **Demand:** Harusi msimu — wedding kanzu orders zinaongezeka
- **Material price:** Kitenge bei mpya kutoka market
- **Customer follow-up:** Mteja wa kanzu — anahitaji kofia matching?
- **Machine maintenance:** Sewing machine yako inahitaji oiling — siku 30 bila service

**Verdict: ✅ STRONG FIT — Custom work, material tracking, seasonal demand**

---

### 16. Dressmaker — Women's Clothing

**Profile:**
- **Location:** Market stalls, workshops, home-based
- **Income range:** KES 500-5,000/day
- **Work pattern:** Custom orders + alterations + ready-made
- **Typical tools:** Sewing machine (industrial), serger, scissors, measuring tape, mannequin
- **Materials:** Fabric (cotton, silk, lace, chiffon), thread, zippers, buttons, elastic

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimeshona gauni tano, elfu tatu kila moja" | SALE: 5 dresses × KES 3,000 = KES 15,000 | High |
| "Nimenunua lace mita mbili, elfu moja" | PURCHASE: 2m lace, KES 1,000 | High |
| "Nimenunua zipper kumi" | PURCHASE: 10 zippers, need price | Medium |

**Trade Vocabulary:**
```
Materials: cotton, silk, lace, chiffon, denim, kitenge, thread, zipper, button,
           elastic, interfacing, bias tape, ribbon, sequins, beads
Tools: sewing machine, serger/overlocker, scissors, measuring tape, pins, chalk,
       iron, mannequin/dress form, pattern paper
Garments: gauni (dress), skirt, blouse, blouse ya kitenge, wedding dress, school uniform,
          curtains, bags
Techniques: kupima, kukata, kushona, kuserge, kupattern, kurepair, kupiga pasi
Sheng: gauni (dress), nguo (clothes), kushona (to sew), design
```

**Proactive Alerts:**
- **Seasonal:** Christmas dresses — demand spike November-December
- **School season:** January uniforms — stock up on fabric
- **Material price:** Silk bei mpya — adjust wedding dress quotes
- **Customer follow-up:** Mteja wa gauni — anahitaji matching accessories?

**Verdict: ✅ STRONG FIT — Custom work, high material tracking need**

---

### 17. Boutique Owner — Fashion Shop

**Profile:**
- **Location:** Market stalls, shopping centers
- **Income range:** KES 1,000-10,000/day
- **Work pattern:** Retail hours, seasonal peaks
- **Typical tools:** Display fixtures, mirror, POS (sometimes), hangers
- **Materials:** Clothing stock (ready-made), accessories, bags, shoes

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimeuza gauni tatu, elfu mbili kila moja" | SALE: 3 dresses × KES 2,000 = KES 6,000 | High |
| "Nimenunua stock mpya kutoka Eastleigh, elfu ishirini" | PURCHASE: new stock, KES 20,000 | High |
| "Nimeuza bag mbili na shoes tatu" | SALE: 2 bags + 3 shoes, need prices | Medium |

**Trade Vocabulary:**
```
Products: gauni (dress), blouse, skirt, jeans, t-shirt, hijab, bag, shoes, belt,
          jewelry, watch, sunglasses, perfume, underwear
Sources: Eastleigh, Gikomba, Toi Market, mitumba (second-hand), Dubai imports
Sheng: boutique, shop, duka, stock, sale, discount, bei (price), customer
```

**Proactive Alerts:**
- **Inventory:** Stock ya gauni za kitenge inaisha — order from Eastleigh
- **Price tracking:** Mitumba bale bei mpya
- **Seasonal:** Christmas stock — order early, prices huongezeka December
- **Cash flow:** Umekuja stock elfu 20, bado hujauza — slow week

**Verdict: ✅ STRONG FIT — Retail model, inventory tracking critical**

---

### 18. Shoemaker — Cobbler

**Profile:**
- **Location:** Roadside, market areas
- **Income range:** KES 300-1,500/day
- **Work pattern:** Quick repairs + custom work
- **Typical tools:** Hammer, awl, needle, thread, knife, shoe last, sewing machine
- **Materials:** Leather, rubber sole, thread, glue, polish, buckles, zippers

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimeshoe pair tatu, mia moja kila moja" | SALE: 3 shoe repairs × KES 100 = KES 300 | High |
| "Nimenunua sole mpya kumi, hamsini kila moja" | PURCHASE: 10 soles × KES 50 = KES 500 | High |
| "Nimenunua leather" | PURCHASE: leather, need price | Medium |

**Trade Vocabulary:**
```
Materials: leather, rubber sole, thread (nyuzi), glue, polish, buckle, zipper,
           heel, insole, laces, shoe cream
Tools: hammer, awl, needle, knife, shoe last, sewing machine, pliers, rasp
Techniques: kushoe (to repair shoes), kusole, kushona, kupolisha, kurepair heel,
            kureplace zipper, kumake custom shoes
Sheng: shoe, sandal, boot, sole, heel, polish
```

**Proactive Alerts:**
- **Inventory:** Rubber sole inaisha — 5 tu
- **Diversification:** Watu wanaanza kununua sneakers — jifunze kurepair sneakers
- **Location:** Uko wapi leo? Hali ya hewa ni nzuri — roadside inafaa

**Verdict: ⚠️ MODERATE FIT — Low transaction values, but high frequency**

---

### 19. Barber — Men's Hair Cutting

**Profile:**
- **Location:** Barber shops, roadside
- **Income range:** KES 500-3,000/day
- **Work pattern:** Walk-in clients, peak hours (evening, weekend)
- **Typical tools:** Clippers, scissors, comb, cape, mirror, spray bottle
- **Materials:** Clipper oil, blade coolant, aftershave, hair products

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimekata nywele kumi na tano, hamsini kila moja" | SALE: 15 haircuts × KES 50 = KES 750 | High |
| "Nimenunua blade mpya tatu, mia moja kila moja" | PURCHASE: 3 blades × KES 100 = KES 300 | High |
| "Nimenunua clipper mpya, elfu mbili" | PURCHASE: new clipper, KES 2,000 | High |
| "Nimefanya beard trim tano" | SALE: 5 beard trims, need price | Medium |

**Trade Vocabulary:**
```
Tools: clipper (Wahl, Andis), scissors (regular, thinning), comb, cape, mirror,
       spray bottle, trimmer, razor, blow dryer
Products: aftershave, hair gel, hair oil, beard oil, shaving cream, blade
Styles: fade, undercut, taper, buzz cut, mohawk, dreadlocks, braids, line-up
Techniques: kukata nywele, kupiga fade, kutrim beard, kufanya line-up, kushave
Sheng: kinyozi (barber shop), kukata (to cut), nywele (hair), beard, fade
```

**CFO — Volume Service Model:**
```
Daily: 15-20 clients average
├── Haircut × 15 @ KES 100 = KES 1,500
├── Beard trim × 3 @ KES 50 = KES 150
├── Hot towel shave × 2 @ KES 150 = KES 300
├── Total revenue: KES 1,950
├── Products cost: KES 200
├── Daily profit: KES 1,750
└── Monthly (26 days): KES 45,500
```

**Alama Score Adaptation:**
- Very high frequency (15-25 transactions/day)
- Consistent daily pattern — strong regularity score
- Low transaction values but predictable
- Rent is fixed cost — track against revenue

**Proactive Alerts:**
- **Equipment:** Clipper blade imechoka — replace before weekend rush
- **Products:** Aftershave inaisha — 2 bottles tu
- **Demand:** Weekend inakuja — Saturday ni siku yako bora
- **Cash flow:** Rent ya shop inakaribia — una pesa?

**Skill Tracking:**
- **Badges:** Classic cuts, fades, beard design, dreadlocks, braiding
- **Levels:** Apprentice Barber → Barber → Senior Barber → Master Barber

**Verdict: ✅ STRONG FIT — High frequency, consistent pattern, equipment tracking**

---

### 20. Hairdresser — Women's Hair Styling

**Profile:**
- **Location:** Salons, home-based, mobile
- **Income range:** KES 500-5,000/day
- **Work pattern:** Appointments + walk-ins, weekend peak
- **Typical tools:** Combs, brushes, hair dryer, flat iron, curling iron, clips, pins
- **Materials:** Weave, braiding hair, relaxer, shampoo, conditioner, hair food, gel

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimeweka weave tatu, elfu moja kila moja" | SALE: 3 weave installations × KES 1,000 = KES 3,000 | High |
| "Nimenunua braiding hair pakiti tano, mia mbili kila moja" | PURCHASE: 5 packs braiding hair × KES 200 = KES 1,000 | High |
| "Nimefanya retouch mbili, mia nne kila moja" | SALE: 2 retouches × KES 400 = KES 800 | High |

**Trade Vocabulary:**
```
Products: weave, braiding hair (kanekalon, xpression), relaxer, shampoo, conditioner,
          hair food, gel, edge control, hair spray, treatment
Styles: weave, braids (box braids, cornrows, twists), retouch, wash and set,
        blow dry, flat iron, ponytail, updo
Tools: combs, brushes, hair dryer, flat iron, curling iron, clips, pins, cape,
       hood dryer, rat-tail comb
Techniques: kuweka weave, kubraid, kuretouch, kuwash, kuset, kublow dry,
            kupress, kuprotect (protective styles)
Sheng: nywele (hair), weave, braid, retouch, salon
```

**Proactive Alerts:**
- **Inventory:** Xpression hair inaisha — 3 bundles tu
- **Seasonal:** December/Holiday season — book appointments early
- **Demand:** Harusi msimu — bridal hair demand inaongezeka
- **Products:** Relaxer bei mpya kutoka supplier

**Verdict: ✅ STRONG FIT — Appointment-based, product inventory tracking**

---

### 21. Salon Owner — Beauty Salon

**Profile:**
- **Location:** Shopping centers, residential areas
- **Income range:** KES 2,000-15,000/day
- **Work pattern:** Multiple services, staff management
- **Typical tools:** All hairdresser tools + nail equipment + facial equipment
- **Materials:** All hairdresser materials + nail polish + facial products + wax

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Leo tumekamilisha wateja ishirini, jumla elfu kumi" | SALE: 20 clients, KES 10,000 total | High |
| "Nimenunua products za salon, elfu tano" | PURCHASE: salon products, KES 5,000 | High |
| "Nimelipa staff wangu, elfu tatu" | EXPENSE: staff wages, KES 3,000 | High |

**Trade Vocabulary:**
```
Services: hair styling, braiding, weaving, nail care, facial, waxing, massage,
          makeup, eyebrow threading, pedicure, manicure
Products: professional hair products, nail products, skincare, wax, towels
Staff: stylist, braider, nail technician, receptionist
Sheng: salon, beautician, client, appointment, walk-in
```

**CFO — Multi-Service Business Model:**
```
Daily salon operations:
├── Revenue:
│   ├── Hair services (10 clients): KES 5,000
│   ├── Nail services (5 clients): KES 2,000
│   ├── Braiding (3 clients): KES 3,000
│   └── Other (facial, wax): KES 1,500
├── Total revenue: KES 11,500
├── Costs:
│   ├── Products: KES 2,000
│   ├── Staff wages: KES 3,000
│   ├── Rent: KES 1,500
│   └── Utilities: KES 500
├── Total costs: KES 7,000
├── Daily profit: KES 4,500
└── Monthly: KES 117,000
```

**Proactive Alerts:**
- **Inventory:** Nail polish za gel zinaisha — order before weekend
- **Staff:** Mtu wako wa braids hajaja leo — angalia kama yuko sawa
- **Cash flow:** Rent + staff wages = elfu 4,500 kwa wiki — plan ahead
- **Seasonal:** December bookings — accept deposits to lock in clients

**Verdict: ✅ STRONG FIT — Multi-service business, staff + inventory management**

---

### 22. Nail Technician — Manicure/Pedicure

**Profile:**
- **Location:** Salons, home-based, mobile service
- **Income range:** KES 500-3,000/day
- **Work pattern:** Appointments + walk-ins
- **Typical tools:** Nail files, buffers, cuticle tools, UV lamp, drill
- **Materials:** Nail polish, gel, acrylic, tips, remover, cuticle oil

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimefanya manicure na pedicure, mia tatu" | SALE: mani-pedi, KES 300 | High |
| "Nimenunua gel polish tano, mia mbili kila moja" | PURCHASE: 5 gel polishes × KES 200 = KES 1,000 | High |
| "Nimefanya acrylic set, elfu moja" | SALE: acrylic set, KES 1,000 | High |

**Trade Vocabulary:**
```
Products: nail polish, gel polish, acrylic, tips, nail glue, remover, cuticle oil,
          base coat, top coat, nail art supplies, stamps, stickers
Tools: nail file, buffer, cuticle pusher, cuticle nipper, UV/LED lamp, drill,
       brush set, dotting tool
Services: manicure, pedicure, acrylic, gel, nail art, French tips, chrome, ombre
Sheng: kucha (nails), manicure, pedicure, gel, acrylic, nail art
```

**Proactive Alerts:**
- **Inventory:** Gel polish ya nude inaisha — popular color
- **Trends:** Nail art style mpya inaenda viral — jifunze
- **Seasonal:** December/Valentine — nail bookings zinaongezeka

**Verdict: ✅ STRONG FIT — Growing market, product inventory tracking**

---

### 23. Photographer — Event Photographer

**Profile:**
- **Location:** Mobile (events, studios)
- **Income range:** KES 5,000-50,000/event
- **Work pattern:** Event-based (weekends peak), booking model
- **Typical tools:** Camera body, lenses, flash, tripod, memory cards, lighting kit
- **Materials:** Prints, albums, memory cards, batteries

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimepiga picha za harusi, elfu ishirini" | SALE: wedding photography, KES 20,000 | High |
| "Nimenunua memory card 64GB, mia nne" | PURCHASE: 64GB card, KES 400 | High |
| "Nimenunua lens mpya, elfu kumi" | PURCHASE: new lens, KES 10,000 | High |

**Trade Vocabulary:**
```
Equipment: camera body (Canon, Nikon, Sony), lens (wide, portrait, zoom), flash,
           tripod, memory card, battery, lighting kit, reflector, backdrop
Services: harusi (wedding), birthday, corporate event, passport photo, studio shoot,
          graduation, album design
Technical: aperture, shutter speed, ISO, white balance, composition, editing, RAW, JPEG
Sheng: picha (photo), camera, lens, flash, album, editing
```

**CFO — Event-Based Model:**
```
Monthly breakdown:
├── Weekend 1: Wedding — KES 25,000
├── Weekend 2: Birthday × 2 — KES 8,000 + KES 5,000
├── Weekend 3: Corporate event — KES 15,000
├── Weekend 4: Graduation — KES 10,000
├── Weekday: Passport photos — KES 3,000
├── Total revenue: KES 66,000
├── Costs:
│   ├── Transport: KES 5,000
│   ├── Prints/albums: KES 8,000
│   ├── Equipment depreciation: KES 5,000
│   └── Editing software: KES 2,000
├── Total costs: KES 20,000
└── Monthly profit: KES 46,000
```

**Alama Score Adaptation:**
- Lumpy, event-based income — not daily transactions
- High equipment value — track depreciation
- Booking model — advance deposits are common
- Seasonal peaks (December weddings, graduation season)

**Proactive Alerts:**
- **Booking:** Harusi 3 zimebaki Dec — book mapema
- **Equipment:** Camera shutter count inakaribia limit — plan upgrade
- **Cash flow:** Deposit ya KES 10,000 imeingia — account for it
- **Seasonal:** Graduation season inakuja — market your services

**Verdict: ✅ STRONG FIT — High-value events, booking management, equipment depreciation**

---

### 24. Videographer — Event Videographer

**Profile:**
- **Location:** Mobile (events)
- **Income range:** KES 10,000-100,000/event
- **Work pattern:** Event-based, pre-production + post-production
- **Typical tools:** Video camera, gimbal, drone, audio recorder, lighting, editing computer
- **Materials:** Memory cards, hard drives, batteries, props

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimevideo harusi, elfu hamsini" | SALE: wedding videography, KES 50,000 | High |
| "Nimenunua hard drive mpya, elfu mbili" | PURCHASE: hard drive, KES 2,000 | High |
| "Nimenunua drone, elfu thelathini" | PURCHASE: drone, KES 30,000 | High |

**Trade Vocabulary:**
```
Equipment: video camera, gimbal/stabilizer, drone, audio recorder, microphone,
           lighting kit, editing computer, hard drives, memory cards
Services: wedding video, music video, corporate video, documentary, social media content,
          drone footage, live streaming
Technical: 4K, 1080p, frame rate, bitrate, codec, color grading, editing, rendering
Sheng: video, drone, camera, editing, shoot, production
```

**Proactive Alerts:**
- **Booking:** Harusi msimu — bookings zinaongezeka
- **Equipment:** Drone battery inaisha — 2 tu
- **Storage:** Hard drive imejaa — transfer or backup
- **Cash flow:** Deposit ya KES 25,000 imeingia — track it

**Verdict: ✅ STRONG FIT — High-value events, equipment-intensive**

---

### 25. DJ — Event DJ

**Profile:**
- **Location:** Clubs, events, mobile
- **Income range:** KES 5,000-50,000/event
- **Work pattern:** Weekend-heavy, event-based
- **Typical tools:** DJ controller/mixer, speakers, laptop, headphones, cables
- **Materials:** Music subscriptions, cables, adapters, cases

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "NimeDJ party ya harusi, elfu kumi" | SALE: wedding DJ, KES 10,000 | High |
| "Nimenunua speaker mpya, elfu tano" | PURCHASE: speaker, KES 5,000 | High |
| "Nimelipia subscription ya music, elfu moja" | EXPENSE: music subscription, KES 1,000 | High |

**Trade Vocabulary:**
```
Equipment: DJ controller, mixer, speakers (PA, monitor), headphones, laptop,
           microphone, cables, lighting, fog machine
Software: Serato, Virtual DJ, Rekordbox, Spotify
Services: club night, wedding, birthday, corporate event, festival
Technical: BPM, crossfade, beat matching, mixing, playlist, sound check
Sheng: DJ, mixer, speaker, sound, playlist, set
```

**Proactive Alerts:**
- **Booking:** Weekend ijayo bado hujabook — check bookings
- **Equipment:** Speaker inaonyesha distortion — service before next gig
- **Cash flow:** Harusi ya wiki ijayo — deposit bado haijalipwa

**Verdict: ✅ STRONG FIT — Event-based, weekend-heavy, equipment tracking**

---

### 26. MC/Emcee — Event Host

**Profile:**
- **Location:** Mobile (events)
- **Income range:** KES 5,000-50,000/event
- **Work pattern:** Event-based, weekend-heavy
- **Typical tools:** Microphone (sometimes own), scripts, wardrobe
- **Materials:** Business cards, wardrobe, transport

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "NimeMC harusi, elfu kumi na tano" | SALE: wedding MC, KES 15,000 | High |
| "Nimenunua suti mpya, elfu tano" | EXPENSE: new suit, KES 5,000 | High |
| "Nimelipia transport, mia mbili" | EXPENSE: transport, KES 200 | High |

**Trade Vocabulary:**
```
Services: harusi (wedding), birthday, corporate event, graduation, fundraiser,
          funeral, church event
Skills: public speaking, crowd engagement, protocol, humor, bilingual hosting
Sheng: MC, emcee, host, microphone, program, agenda
```

**Proactive Alerts:**
- **Booking:** Harusi msimu — December bookings zinaanza
- **Cash flow:** Transport costs zinaongezeka — track per event
- **Reputation:** Rating yako ni nzuri — ask for referrals

**Verdict: ✅ STRONG FIT — Event-based, booking management**

---

### 27. Laundry Worker (Mama Fua) — Clothes Washing

**Profile:**
- **Location:** Client homes, laundromats, mobile
- **Income range:** KES 300-1,500/day
- **Work pattern:** Daily recurring clients, weekly cycles
- **Typical tools:** Basin, scrubbing board, clothesline, iron
- **Materials:** Detergent, fabric softener, bleach, starch

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimefua nguo za nyumba tatu, mia tatu kila moja" | SALE: 3 households × KES 300 = KES 900 | High |
| "Nimenunua detergent pakiti mbili, mia mbili" | PURCHASE: 2 detergent packs × KES 200 = KES 400 | High |
| "Nimepiga pasi nguo za mteja" | SALE: ironing service, need price | Medium |

**Trade Vocabulary:**
```
Materials: detergent, fabric softener, bleach, starch, soap, water
Tools: basin, scrubbing board, clothesline, pegs, iron, ironing board
Services: kufua (washing), kupiga pasi (ironing), kusukuma (dry cleaning equivalent),
          stain removal, folding
Sheng: mama fua, nguo (clothes), kufua (to wash), pasi (iron), detergent
```

**CFO — Recurring Client Model:**
```
Daily: 3-5 households
├── Household 1 (large family): KES 500/day × 5 days = KES 2,500/week
├── Household 2 (couple): KES 300/day × 3 days = KES 900/week
├── Household 3 (single): KES 200/day × 2 days = KES 400/week
├── One-off jobs: KES 1,000/week average
├── Total weekly: KES 4,800
├── Materials: KES 800/week
└── Weekly profit: KES 4,000 → Monthly: KES 16,000
```

**Alama Score Adaptation:**
- Recurring clients = predictable income (strong regularity score)
- Low transaction values but consistent
- Client retention is key metric
- Transport costs between clients

**Proactive Alerts:**
- **Client follow-up:** Mteja wa Monday hajakupigia — check if still needs service
- **Materials:** Detergent inaisha — 1 packet tu
- **Cash flow:** Client wa kila wiki bado hajalipa wiki hii
- **Seasonal:** Msimu wa mvua — nguo zinachukua muda zaidi kukauka

**Verdict: ✅ STRONG FIT — Recurring model, client management critical**

---

### 28. Ironing Service — Clothes Pressing

**Profile:**
- **Location:** Client homes, market stalls, roadside
- **Income range:** KES 200-1,000/day
- **Work pattern:** Daily recurring + one-off
- **Typical tools:** Iron, ironing board, spray bottle, hangers
- **Materials:** Charcoal (for charcoal iron), electricity, spray starch

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimepiga pasi nguo hamsini, kumi kila moja" | SALE: 50 items × KES 10 = KES 500 | High |
| "Nimenunua makaa, mia moja" | PURCHASE: charcoal, KES 100 | High |
| "Nimenunua iron mpya, elfu mbili" | PURCHASE: new iron, KES 2,000 | High |

**Trade Vocabulary:**
```
Tools: iron (electric, charcoal), ironing board, spray bottle, hangers, cloth cover
Materials: charcoal (makaa), electricity, spray starch, water
Services: kupiga pasi (ironing), kusukuma (pressing), kurepair (fix creases)
Sheng: pasi (iron), nguo (clothes), makaa (charcoal)
```

**Proactive Alerts:**
- **Equipment:** Iron yako inahitaji cleaning — mineral buildup
- **Materials:** Makaa yanaisha — buy before Monday
- **Cash flow:** Umefanya nguo 50 leo — hujalipwa na 3 clients

**Verdict: ✅ FIT — Simple model, low complexity**

---

### 29. Cleaning Service — House/Office Cleaning

**Profile:**
- **Location:** Client homes, offices, commercial buildings
- **Income range:** KES 500-3,000/day
- **Work pattern:** Recurring clients + one-off deep cleans
- **Typical tools:** Mop, broom, bucket, vacuum (sometimes), cleaning cloths
- **Materials:** Detergent, bleach, floor polish, glass cleaner, toilet cleaner

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimesafisha nyumba mbili, elfu moja kila moja" | SALE: 2 houses × KES 1,000 = KES 2,000 | High |
| "Nimenunua detergent na bleach, mia mbili" | PURCHASE: detergent + bleach, KES 200 | High |
| "Nimesafisha office, elfu mbili" | SALE: office cleaning, KES 2,000 | High |

**Trade Vocabulary:**
```
Materials: detergent, bleach, floor polish, glass cleaner, toilet cleaner, scrubbing brush,
           mop, broom, bucket, cleaning cloths, vacuum bags
Services: nyumba (house cleaning), office, deep clean, post-construction cleaning,
          move-in/move-out cleaning
Sheng: kusafisha (to clean), nyumba (house), office, mop, broom, bucket
```

**Proactive Alerts:**
- **Client management:** Office ya Monday bado haijakupigia — confirm appointment
- **Materials:** Detergent inaisha — buy before next job
- **Cash flow:** Client wa office bado hajalipa mwezi huu
- **Seasonal:** Post-Christmas cleaning — demand spike

**Verdict: ✅ STRONG FIT — Recurring model, material tracking**

---

### 30. Security Guard — Private Security

**Profile:**
- **Location:** Residential, commercial, events
- **Income range:** KES 800-1,500/day or KES 15,000-25,000/month
- **Work pattern:** Shift-based (day/night), regular schedule
- **Typical tools:** Uniform, whistle, flashlight, radio (sometimes)
- **Materials:** Uniform, boots, batteries

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimeguard nyumba tatu wiki hii, elfu mbili" | SALE: 3 guard shifts, KES 2,000 | High |
| "Nimenunua uniform mpya, elfu moja" | EXPENSE: new uniform, KES 1,000 | High |
| "Nimepewa bonus ya Krismasi, elfu tatu" | INCOME: Christmas bonus, KES 3,000 | High |

**Trade Vocabulary:**
```
Terms: kuguard (to guard), shift (day/night), patrol, alarm, CCTV, gate, visitor log,
       emergency, report, uniform, badge
Sheng: security, guard, watchman, patrol, alarm
```

**Proactive Alerts:**
- **Schedule:** Shift yako ya usiku inaanza saa kumi — prepare
- **Cash flow:** Mshahara utaingia tarehe ngapi? Plan expenses
- **Uniform:** Boots zimechoka — invest in good pair

**Verdict: ⚠️ MODERATE FIT — Fixed wage model, simpler financial tracking**

---

### 31. Watchman — Night Guard

**Profile:**
- **Location:** Residential, commercial premises
- **Income range:** KES 600-1,200/day or KES 12,000-18,000/month
- **Work pattern:** Night shifts, regular schedule
- **Typical tools:** Whistle, flashlight, club/rungu
- **Materials:** Batteries, warm clothing, tea/food for night

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimewatch nyumba, elfu moja wiki hii" | INCOME: weekly watchman pay, KES 1,000 | High |
| "Nimenunua batteries ya flashlight, hamsini" | EXPENSE: batteries, KES 50 | High |
| "Nimenunua chai na mandazi usiku, hamsini" | EXPENSE: night food, KES 50 | High |

**Trade Vocabulary:**
```
Terms: kuwatch (to watch/guard), usiku (night), patrol, alarm, flashlight, rungu (club),
       gate, kufungua (to open), kufunga (to close)
Sheng: watchman, guard, night, security
```

**Proactive Alerts:**
- **Schedule:** Usiku unakaribia — sleep during day
- **Cash flow:** Mshahara — plan monthly expenses
- **Safety:** Kaa makini — kuna matukio area yako

**Verdict: ⚠️ MODERATE FIT — Fixed wage, minimal financial complexity**

---

### 32. Gardener — Landscaping

**Profile:**
- **Location:** Client homes, commercial properties
- **Income range:** KES 500-2,000/day
- **Work pattern:** Recurring clients (weekly/bi-weekly) + one-off projects
- **Typical tools:** Slasher, rake, pruning shears, wheelbarrow, hosepipe, lawn mower
- **Materials:** Seeds, fertilizer, mulch, plants, pots

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimekata nyasi nyumba tatu, mia tatu kila moja" | SALE: 3 lawns × KES 300 = KES 900 | High |
| "Nimenunua mbolea, mia mbili" | PURCHASE: fertilizer, KES 200 | High |
| "Nimepanda maua kumi" | SERVICE: planted 10 flowers, need price | Medium |

**Trade Vocabulary:**
```
Tools: slasher, rake, pruning shears, wheelbarrow, hosepipe, lawn mower, spade,
       watering can, secateurs
Materials: seeds (mbegu), fertilizer (mbolea), mulch, plants (maua), pots, soil,
           pesticide, grass sod
Services: kukata nyasi (mowing), kupanda (planting), kuprune, kupalilia (weeding),
          kumwagilia (watering), landscaping, garden design
Sheng: garden, nyasi (grass), maua (flowers), mbolea (fertilizer)
```

**Proactive Alerts:**
- **Client schedule:** Mteja wa Monday hajakupigia — confirm
- **Seasonal:** Msimu wa mvua — grass inakua haraka, ongeza visits
- **Materials:** Seeds za maua mpya — zimeingia sokoni
- **Cash flow:** Client wa kila wiki bado hajalipa

**Verdict: ✅ FIT — Recurring model, seasonal patterns**

---

### 33. Plumber's Assistant — Plumbing Helper

**Profile:**
- **Location:** Construction sites, residential calls (with plumber)
- **Income range:** KES 500-1,200/day
- **Work pattern:** Assists main plumber, paid daily
- **Typical tools:** Basic hand tools, bucket, rag
- **Materials:** None (plumber provides)

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimesaidia fundi wa bomba leo, mia nane" | INCOME: daily helper pay, KES 800 | High |
| "Nimenunua maji ya kunywa, hamsini" | EXPENSE: water/food, KES 50 | High |

**Trade Vocabulary:**
```
Terms: kusaidia (to help), fundi (craftsman), bomba (pipe), kuchimba (to dig),
       kubeba (to carry), kusafisha (to clean)
Sheng: assistant, helper, fundi, job
```

**Proactive Alerts:**
- **Cash flow:** Umefanya kazi siku 5 mfululizo — angalia mshahara
- **Skill building:** Jifunze zaidi ya plumbing — ongeza ujuzi wako

**Verdict: ⚠️ MODERATE FIT — Simple wage model, skill building opportunity**

---

### 34. Construction Helper — General Labor

**Profile:**
- **Location:** Construction sites
- **Income range:** KES 500-1,000/day
- **Work pattern:** Daily casual labor, inconsistent
- **Typical tools:** Wheelbarrow, shovel, pickaxe, bucket
- **Materials:** None (site provides)

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimefanya kazi site ya mjengo, mia saba leo" | INCOME: construction site work, KES 700 | High |
| "Nimenunua gloves, hamsini" | EXPENSE: work gloves, KES 50 | High |
| "Nimenunua maji na chai, hamsini" | EXPENSE: food/water, KES 50 | High |

**Trade Vocabulary:**
```
Terms: mjengo (construction), kuchimba (to dig), kubeba (to carry), kumwagilia (to water),
       kusukuma wheelbarrow, kuchanganya (to mix), cement, mchanga (sand), kokoto (ballast)
Sheng: mjengo (construction site), mjamaa (laborer), kazi (work), job
```

**Proactive Alerts:**
- **Cash flow:** Umefanya kazi siku 3 tu wiki hii — angalia budget
- **Safety:** Helmet yako imechoka — invest in safety gear
- **Skill building:** Jifunze kuweld au kuplumb — ongeza ujuzi

**Verdict: ⚠️ MODERATE FIT — Simple wage model, inconsistent income**

---

### 35. Demolition Worker — Building Demolition

**Profile:**
- **Location:** Construction/renovation sites
- **Income range:** KES 800-2,000/day
- **Work pattern:** Project-based, physical and dangerous
- **Typical tools:** Sledgehammer, pickaxe, crowbar, wheelbarrow, safety gear
- **Materials:** None (site provides)

**Voice Input Validation:**

| Voice Phrase | Extracted Data | Confidence |
|---|---|---|
| "Nimeharibu ukuta wa nyumba, elfu moja leo" | INCOME: demolition work, KES 1,000 | High |
| "Nimenunua gloves na dust mask, mia moja" | EXPENSE: safety gear, KES 100 | High |

**Trade Vocabulary:**
```
Terms: kuharibu (to demolish), kuchimba (to dig), kubomoa (to tear down), sledgehammer,
       crowbar, pickaxe, debris, rubble, dust, safety
Sheng: demolition, kubomoa, kuharibu, mjengo
```

**Proactive Alerts:**
- **Safety:** Dust mask yako imechoka — badilisha
- **Cash flow:** Project ya demolition imeisha — angalia next job
- **Health:** Umefanya demolition siku 7 mfululizo — rest your body

**Verdict: ⚠️ MODERATE FIT — Simple wage model, health/safety concerns**

---

## Cross-Cutting Architecture Validation

### 1. Voice Input — Trade Vocabulary Coverage

**Requirement:** ASR must recognize 50-200 trade-specific terms per worker type.

**Validation Results:**

| Trade Category | Key Vocabulary Challenge | Msaidizi Solution | Status |
|---|---|---|---|
| Mechanical Repair | Technical part names (alternator, catalytic converter) | Sheng/Kiswahili adaptations + context learning | ✅ Viable |
| Construction | Material quantities (rolls, bags, sheets, meters) | Unit extraction from voice patterns | ✅ Viable |
| Electronics | Brand names + model numbers (Samsung A14, iPhone 15) | Brand/model database + fuzzy matching | ✅ Viable |
| Textile | Fabric types (kitenge, kanga, chiffon) | Fabric vocabulary per trade | ✅ Viable |
| Beauty | Style names (box braids, French tips, fade) | Style database + context | ✅ Viable |
| Creative | Equipment names (gimbal, drone, lens types) | Equipment vocabulary per trade | ✅ Viable |
| Domestic | Simple terms, mostly quantities | Basic vocabulary sufficient | ✅ Viable |

**Critical Finding:** The ASR must handle **code-switching** between Kiswahili, Sheng, and English within a single utterance. Example: "Nimenunua brake pad za Toyota, original parts" mixes Swahili, trade term, and English.

**Recommendation:** Build a **Trade Vocabulary Layer** in the dialect engine — a per-trade dictionary that the ASR uses to bias recognition. This is not a new module; it's an extension of the existing `core/dialect/` system with trade-specific word lists.

---

### 2. Financial Recording — Job-Based vs. Daily Sales

**Critical Architecture Gap Identified:**

The current Transaction model assumes **daily sales** (food vendor model). Skilled trades have fundamentally different patterns:

| Pattern | Food Vendor | Skilled Trade |
|---|---|---|
| Income frequency | Multiple times/day | Per job (daily to monthly) |
| Transaction size | Small (KES 20-500) | Medium-Large (KES 500-50,000) |
| Cost basis | Product cost | Materials + labor |
| Margin model | Markup on goods | Labor value + material markup |
| Inventory | Perishable goods | Durable tools + consumable materials |
| Payment timing | Immediate (cash/M-Pesa) | Deposit → progress → final |
| Receivables | Rare | Common (client credit) |

**Recommended Data Model Extension:**

```kotlin
// Extension to Transaction model for skilled trades
data class ServiceTransaction(
    // ... existing Transaction fields ...

    // ═══ JOB TRACKING ═══
    val jobId: String? = null,              // Groups related transactions
    val jobType: String = "",               // "repair", "installation", "fabrication", "service"
    val jobStatus: String = "completed",    // "quoted", "started", "in_progress", "completed", "paid"
    val clientName: String = "",            // Client for this job
    val clientPhone: String = "",           // Client contact

    // ═══ LABOR TRACKING ═══
    val laborHours: Double = 0.0,           // Hours worked
    val laborRate: Double = 0.0,            // Rate per hour
    val laborCharge: Double = 0.0,          // Total labor charged

    // ═══ MATERIAL TRACKING ═══
    val materialsUsed: List<MaterialItem> = emptyList(), // Materials consumed
    val materialCost: Double = 0.0,         // Total material cost
    val materialCharged: Double = 0.0,      // Total material charged to client
    val materialMargin: Double = 0.0,       // materialCharged - materialCost

    // ═══ PAYMENT TRACKING ═══
    val depositPaid: Double = 0.0,          // Deposit received
    val depositDate: Long? = null,          // When deposit was paid
    val balanceDue: Double = 0.0,           // Remaining balance
    val balanceDueDate: Long? = null,       // When balance is due
    val paymentTerms: String = "immediate", // "immediate", "on_completion", "30_days"

    // ═══ TOOL DEPRECIATION ═══
    val toolsUsed: List<String> = emptyList(), // Tools used for this job
    val toolDepreciation: Double = 0.0      // Depreciation cost allocated
)

data class MaterialItem(
    val name: String,                       // "brake pad", "cable 2.5mm²"
    val quantity: Double,                    // 2.0
    val unit: String,                       // "pieces", "meters", "rolls"
    val unitCost: Double,                   // 400.0 (what I paid)
    val unitCharged: Double,                // 600.0 (what I charged)
    val totalCost: Double,                  // 800.0
    val totalCharged: Double                // 1200.0
)
```

**Impact on Alama Score:**
- The **frequency pillar** must count completed jobs, not individual transactions
- The **margin pillar** must separate labor margin from material margin
- The **regularity pillar** must handle project-based gaps (no income for 3 days doesn't mean bad business)
- **New pillar consideration:** "Receivables management" — how well the worker collects payments

---

### 3. CFO Features — Job Costing, Material Tracking, Tool Depreciation

**Validation by Feature:**

#### Job Costing ✅ Critical
Every skilled trade worker needs to track:
- Materials purchased per job
- Labor charged per job
- Margin per job
- Time per job
- Effective hourly rate

**Architecture fit:** The `superagent:financial` module's `TransactionEngine` needs a `recordJob()` method alongside `recordSale()`. The `QueryEngine` needs `getJobProfitability()` and `getEffectiveHourlyRate()` queries.

#### Material Tracking ✅ Critical
Construction trades (electrician, plumber, carpenter, welder, roofer, tiler, painter, mason, plasterer) tie up significant capital in materials:
- Electrician: KES 50,000-200,000 in cable stock
- Plumber: KES 30,000-100,000 in pipe/fitting stock
- Carpenter: KES 40,000-150,000 in timber/plywood
- Welder: KES 30,000-100,000 in steel stock

**Architecture fit:** The `superagent:financial` module needs a `MaterialInventory` system:
- Track current stock levels
- Alert when stock falls below threshold
- Calculate capital tied up in materials
- Track material price changes

#### Tool Depreciation ✅ Important
Skilled trades have significant tool investments:
- Auto mechanic: KES 50,000-200,000 in tools
- Electrician: KES 20,000-80,000 in tools
- Carpenter: KES 30,000-100,000 in tools
- Photographer: KES 100,000-500,000 in equipment
- Videographer: KES 200,000-1,000,000 in equipment

**Architecture fit:** The `superagent:financial` module needs `ToolAsset` tracking:
- Log tool/equipment purchases
- Calculate depreciation (straight-line or usage-based)
- Alert when tools need replacement
- Track tool maintenance schedules

#### Client Management ✅ Critical for Service Trades
Unlike food vendors who serve walk-in customers, skilled trades build client relationships:
- Auto mechanic: 20-50 regular clients
- Electrician: 10-30 clients (mix of residential + commercial)
- Plumber: 15-40 clients
- Barber: 100+ regular clients
- Hairdresser: 50+ regular clients
- Mama Fua: 3-10 recurring clients

**Architecture fit:** The `superagent:context` module's `WorkerProfile` needs a `ClientRegistry`:
- Track client names, contacts, history
- Track payment patterns (pays on time, slow payer)
- Generate follow-up reminders
- Track service intervals (last oil change was 8 weeks ago)

---

### 4. Alama Score — Per-Job Income, Irregular Schedule

**Validation of 8 Pillars for Skilled Trades:**

| Pillar | Weight | Skilled Trade Behavior | Adaptation Needed |
|---|---|---|---|
| **Frequency** | 15% | 1-5 jobs/day (mechanic) to 1 job/week (videographer) | ✅ Count completed jobs, not transactions |
| **Revenue Trend** | 15% | Lumpy — big project payments, not smooth daily | ⚠️ Use 30-day rolling average, not 7-day |
| **Margins** | 15% | Labor (high margin) + materials (lower margin) | ✅ Separate labor and material margins |
| **Diversity** | 10% | Service variety (oil change, brake repair, engine work) | ✅ Track service types, not products |
| **Regularity** | 10% | Some work daily (barber), some work weekends (photographer) | ⚠️ Per-trade baseline, not universal |
| **Growth** | 10% | Skill progression → higher rates → more clients | ✅ Track rate increases over time |
| **Expense Control** | 10% | Material waste, tool maintenance, transport | ✅ Track per-job material waste |
| **Savings** | 15% | Irregular income makes consistent saving hard | ⚠️ Adjust for income volatility |

**Critical Adaptations:**

1. **Frequency pillar:** Must handle wide range — barber (20/day) vs. videographer (1/week). Normalize by trade type, not absolute count.

2. **Revenue trend pillar:** 7-day rolling average is too volatile for project-based work. Use 30-day minimum window.

3. **Regularity pillar:** Each trade has a different "normal" pattern. Barber = 6 days/week. Photographer = weekends only. Mama Fua = 3-5 days/week. Baseline must be per-trade-type.

4. **Savings pillar:** Irregular income (big project payment → weeks of nothing) makes consistent saving difficult. The scoring must account for lumpiness — saving 20% of a KES 50,000 project payment is better than saving nothing for 3 weeks.

**New Pillar Consideration — "Receivables Health":**
For trades with common credit sales (electrician, plumber, carpenter), track:
- Total outstanding receivables
- Average collection time
- Write-off rate
- This directly impacts cash flow and financial health

---

### 5. Proactive Alerts — Trade-Specific Alerts

**Validation of Alert Types for Skilled Trades:**

| Alert Type | Food Vendor Relevance | Skilled Trade Relevance | Priority |
|---|---|---|---|
| **Cash Flow Warning** | High | Very High (project gaps) | P0 |
| **Restock Recommendation** | High | High (materials) | P1 |
| **Market Opportunity** | Medium | Medium (material price dips) | P2 |
| **Savings Milestone** | High | High | P2 |
| **Credit Readiness** | High | High | P2 |
| **Anomaly Detection** | Medium | High (unusual expenses) | P1 |
| **Chama Reminder** | High | High | P1 |

**Trade-Specific Alerts (NEW):**

| Alert Type | Trades | Value |
|---|---|---|
| **Tool Maintenance** | All skilled trades | "Clipper blade imechoka — replace before weekend rush" |
| **Material Price Change** | Construction, repair trades | "Cable ya 2.5mm² imepanda 15% — re-quote jobs" |
| **Client Follow-up** | All service trades | "Mteja wa gari ya Fielder — service ilikuwa wiki 8 zilizopita" |
| **Payment Reminder** | All project trades | "Client wa wiring job — balance ya elfu 10 bado haijalipwa" |
| **Seasonal Demand** | All trades | "December harusi msimu — bookings zinaanza" |
| **Skill Development** | All trades | "Watu wanaanza kutumia smartwatch — jifunze kurepair" |
| **Equipment Depreciation** | All trades | "Camera yako shutter count inakaribia — plan upgrade" |
| **Health/Safety** | Demolition, construction | "Umefanya demolition siku 7 — rest your body" |
| **Waste Tracking** | Construction, textile | "Umepoteza plywood 2 sheets mwezi huu — improve cutting" |

**Proactive Alert Architecture Fit:**

The alert system in `gap-proactive-alerts.md` is well-designed for skilled trades. The OODA loop, adaptive thresholds, and anti-fatigue system all apply. The main additions needed:

1. **Tool maintenance schedule** — per-tool usage tracking and maintenance reminders
2. **Client relationship tracking** — service intervals, follow-up reminders
3. **Receivables tracking** — payment due dates, aging analysis
4. **Material price monitoring** — price change alerts for key materials
5. **Seasonal demand patterns** — per-trade seasonal calendars

These fit naturally into the existing alert framework as new alert types with the same priority/delivery/timing system.

---

### 6. Skill Crystallization — Natural Routines

**Validation of Skill Types for Skilled Trades:**

| Skill Type | Skilled Trade Fit | Example |
|---|---|---|
| **Morning Briefing** | ✅ Strong | "Habari! Leo ni Jumatano — market day. Orders 3 ziko pending. Client wa gari analipa leo." |
| **Restock Alert** | ✅ Strong | "Brake pads zinaisha — 2 tu. Supplier wako anazo bei elfu moja." |
| **Price Check** | ✅ Strong | "Cable ya 2.5mm²: Supplier A = KES 3,200, Supplier B = KES 2,900. Supplier B ni cheaper." |
| **Savings Nudge** | ✅ Strong | "Umepata elfu kumi kwenye project. Weka 20% — elfu mbili — kwenye akiba." |
| **Market Day Preparation** | ⚠️ Limited | Less relevant for trades (no market day concept) — but "project day prep" could work |
| **Weekly Report** | ✅ Strong | "Wiki hii: Jobs 5, Revenue elfu kumi na tano, Profit elfu nane. Best week this month!" |

**Trade-Specific Crystallizable Patterns:**

| Pattern | Detection | Skill Created |
|---|---|---|
| "Check prices every Monday" | 3x time-based | Weekly material price brief |
| "Client X always calls on Friday" | 3x context-based | Friday client reminder |
| "Restock after every big job" | 3x action-based | Post-job restock check |
| "Morning market brief" | 3x time-based | Daily market/price brief |
| "Sunday evening — review week" | 3x time-based | Weekly financial summary |
| "End of month — collect receivables" | 3x time-based | Monthly receivables reminder |

**Architecture Fit:** The skill crystallization system in `gap-skill-crystallization.md` works well for skilled trades. The detection thresholds (3 occurrences in 7-14 days) are appropriate. The proposal/confirmation/activation pipeline is solid. No modifications needed.

---

### 7. Language — Trade-Specific Terms

**Validation of Language Support:**

**Kiswahili Trade Terms (standard across East Africa):**

| Category | Terms |
|---|---|
| **Construction** | matofali (bricks), mchanga (sand), kokoto (ballast), mabati (roofing), ukuta (wall), msingi (foundation), dari (ceiling), sakafu (floor) |
| **Mechanical** | gari (car), injini (engine), breki (brakes), tairi (tire), mafuta (oil/fuel), radiator, clutch, gear |
| **Electrical** | stima (electricity), cable, switch, socket, breaker, transformer, umeme (electricity) |
| **Plumbing** | bomba (pipe), kran (tap/faucet), tanki (tank), maji (water), choo (toilet), bafu (bathroom) |
| **Textile** | nguo (clothes), kitenge, kanga, kanzu, uzi (thread), kitanzi (button), zip |
| **Beauty** | nywele (hair), kucha (nails), rangi (color/dye), weave, braid, relaxer |
| **General** | bei (price), mteja (customer), supplier, receipt, profit, hasara (loss), faida (profit) |

**Sheng Trade Terms (Nairobi-specific, spreading):**

| Category | Terms |
|---|---|
| **General** | machine (car), ndogo (minor), kubwa (major), kuweka (install), kuchomoa (remove), stock |
| **Business** | duka (shop), biashara (business), customer, order, delivery, discount |
| **Quality** | original, fake, local, import, quality, warranty |

**Architecture Fit:** The existing `core/dialect/` system (39 files!) already handles dialect detection and adaptation. The trade-specific vocabulary can be added as a **trade vocabulary extension** to the dialect system — a per-trade word list that the ASR uses for bias and the NLU uses for entity extraction. This is a data addition, not an architecture change.

---

## Summary: Architecture Fitness by Trade Category

### ✅ STRONG FIT (Architecture works well, minor adaptations)

| # | Worker | Key Adaptations Needed |
|---|---|---|
| 1 | Fundi wa Magari (Auto Mechanic) | Job costing, parts inventory, client management |
| 3 | Fundi wa Umeme (Electrician) | Project costing, material inventory, receivables |
| 4 | Fundi wa Bomba (Plumber) | Service + project dual model, material tracking |
| 5 | Fundi wa Mbao (Carpenter) | Custom job costing, material waste tracking |
| 6 | Fundi wa Vyuma (Welder) | Fabrication costing, electricity dependency tracking |
| 7 | Fundi wa Simu (Phone Repair) | High-volume parts inventory, fast-moving stock |
| 8 | Fundi wa Kompyuta (Computer Repair) | Parts inventory, software licensing |
| 10 | Fundi wa Mabati (Roofing) | Project costing, material price tracking |
| 11 | Fundi wa Tiles (Tiling) | Project costing, waste tracking |
| 12 | Painter | Project costing, seasonal demand |
| 13 | Mason | Daily rate + project model, material tracking |
| 14 | Plasterer | Project costing, coordination with mason |
| 15 | Fundi wa Kofia (Tailor) | Custom garment costing, seasonal demand |
| 16 | Dressmaker | Custom costing, school uniform seasonal |
| 17 | Boutique Owner | Retail inventory, seasonal stock |
| 19 | Barber | High-volume service, equipment tracking |
| 20 | Hairdresser | Appointment model, product inventory |
| 21 | Salon Owner | Multi-service business, staff management |
| 22 | Nail Technician | Product inventory, trend tracking |
| 23 | Photographer | Event booking, equipment depreciation |
| 24 | Videographer | Event booking, equipment depreciation |
| 25 | DJ | Event booking, equipment tracking |
| 26 | MC/Emcee | Event booking, reputation management |
| 27 | Mama Fua (Laundry) | Recurring client model, material tracking |
| 29 | Cleaning Service | Recurring client model, material tracking |
| 32 | Gardener | Recurring client model, seasonal patterns |

### ⚠️ MODERATE FIT (Architecture works, but model is simpler)

| # | Worker | Reason |
|---|---|---|
| 2 | Fundi wa Pikipiki (Motorcycle Mechanic) | Quick jobs, high volume — similar to barber model |
| 9 | Fundi wa Saa (Watch Repair) | Declining market, low values |
| 18 | Shoemaker (Cobbler) | Low transaction values |
| 28 | Ironing Service | Simple model, low complexity |
| 30 | Security Guard | Fixed wage model |
| 31 | Watchman | Fixed wage model |
| 33 | Plumber's Assistant | Simple wage model |
| 34 | Construction Helper | Simple wage model, inconsistent |
| 35 | Demolition Worker | Simple wage model, health concerns |

---

## Required Architecture Modifications

### 1. Transaction Model Extension
**Module:** `superagent:financial/TransactionEngine`
**Change:** Add `ServiceTransaction` and `MaterialItem` data classes
**Priority:** HIGH — Without this, skilled trades cannot properly track job costs

### 2. Job Management System
**Module:** `superagent:financial/JobManager` (NEW)
**Features:**
- Create/track jobs (quoted → started → in_progress → completed → paid)
- Group transactions by job
- Track receivables per job
- Calculate job profitability

### 3. Material Inventory System
**Module:** `superagent:financial/MaterialInventory` (NEW)
**Features:**
- Track stock levels per material
- Alert on low stock
- Track material prices over time
- Calculate capital tied up in inventory

### 4. Tool/Equipment Asset Tracking
**Module:** `superagent:financial/ToolTracker` (NEW)
**Features:**
- Log tool/equipment purchases
- Track depreciation
- Maintenance schedule alerts
- Replacement planning

### 5. Client Relationship Manager
**Module:** `superagent:context/ClientRegistry` (NEW)
**Features:**
- Client profiles (name, contact, history)
- Payment pattern tracking
- Service interval reminders
- Follow-up scheduling

### 6. Trade Vocabulary Extension
**Module:** `core/dialect/TradeVocabulary` (NEW)
**Features:**
- Per-trade word lists (50-200 terms)
- ASR bias for trade terms
- NLU entity extraction for trade-specific items
- Language: Kiswahili, Sheng, English code-switching

### 7. Alama Score — Trade-Type Normalization
**Module:** `superagent:flywheel/AlamaScoreEngine`
**Change:** Per-trade-type baselines for frequency, regularity, and savings pillars
**Priority:** MEDIUM — Works with current model, but accuracy improves with normalization

### 8. Proactive Alerts — Trade-Specific Alert Types
**Module:** `superagent:financial/ProactiveAlerts`
**New alerts:**
- Tool maintenance reminders
- Material price change alerts
- Client follow-up reminders
- Payment due date reminders
- Seasonal demand alerts
- Skill development suggestions
- Health/safety reminders (construction trades)

---

## Conclusion

The Msaidizi superagent architecture is **fundamentally sound** for services and skilled trade workers. The voice-first, proof-accumulation, offline-first design philosophy aligns perfectly with how these workers operate. However, the architecture was designed with food vendors and market traders as the primary mental model. Skilled trades require three key adaptations:

1. **Job-based financial model** (not daily sales)
2. **Material/tool inventory management** (not perishable goods)
3. **Client relationship management** (not walk-in customers)

These are **extensions** to the existing architecture, not rewrites. The superagent model, Alama Score engine, proactive alert system, and skill crystallization system all work for skilled trades with these additions.

**The 35 workers validated represent a massive addressable market.** Kenya alone has an estimated 2-3 million jua kali artisans. The skilled trades account for 30-40% of informal employment. Making Msaidizi indispensable for these workers requires the adaptations documented above — but the foundation is solid.

---

*Validation complete. 35/35 workers assessed. Architecture: ✅ viable with documented extensions.*
