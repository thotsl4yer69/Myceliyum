package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// === Myceliyums brand v3 — "Field Signal Terminal" (dark) ===
// Tokens mirror the site/brand plate: bg/chassis/rule neutrals, signal-green
// primary, amber secondary, ink text ramp.
val NeonMint = Color(0xFF54E0A0)            // primary accent (sig — signal green)
val MossSpruce = Color(0xFF8E9583)          // ink-dim (muted olive; tertiary)
val ChanterelleGold = Color(0xFFE6B24C)     // secondary accent (amber)
val DimSage = Color(0xFF5B6353)             // ink-faint

val DeepForestVoid = Color(0xFF0B0D0B)      // bg
val CharcoalSpruce = Color(0xFF14160F)      // chassis (surface)
val MediumSpruce = Color(0xFF1C2017)        // raised surface (chassis→rule midpoint)
val SageOutline = Color(0xFF2B3022)         // rule (outline)

// === Quiet modernist (light) — accents aligned to brand deep tones ===
val DeepForestGreen = Color(0xFF1F9E6C)     // primary accent (sig-deep)
val WarmHoney = Color(0xFF8A6A26)           // secondary accent (amber-deep)
val SoftMoss = Color(0xFF4C7D63)            // tertiary accent

val WarmOffWhite = Color(0xFFFAFAF7)
val PaperWhite = Color(0xFFFFFFFF)
val SoftStone = Color(0xFFE9E6DE)
val WarmStoneOutline = Color(0xFFCFCCC2)

// === Forageability tier ramp (canonical, 5-tier) ==========================
// THE single source of truth for tier colour — read by the heatmap legend,
// the ranked map pins, the tier chips and the hotspot card, so the surface,
// pins and labels can never tell different colour stories (they used to:
// MapScreen hard-coded its own divergent set). The ramp is MONOTONIC and
// green-anchored: signal-green = best ("good fungal habitat", the brand
// signal), stepping down through lime and gold to desaturated sage/forest for
// the weakest tiers. Deliberately NO alarming red — red reads as
// "danger / avoid", the opposite of a spot you'd want to forage.
val TierExcellent = Color(0xFF54E0A0)       // signal green — best
val TierVeryGood = Color(0xFF9BD96B)        // lime — strong
val TierPromising = Color(0xFFE6B24C)       // chanterelle gold — promising
val TierPossible = Color(0xFF8B9D93)        // muted sage — possible
val TierUnlikely = Color(0xFF5B6353)        // dim forest — unlikely

// === Map overlay palette — WARM, for legibility over terrain basemaps ========
// The on-map heat surface and the ranked pins use a WARM ramp (amber → red),
// NOT the green tier ramp. Reason: the default basemap is OpenTopoMap (green
// woodland / tan terrain), where a green surface and green pins are camouflaged
// — users reported "never see any shading". Warm hues pop against green/topo and
// satellite tiles. "Hotter = more likely" is the universal heatmap convention.
// (The dark-UI hotspot CARD keeps the green tier ramp above — on the near-black
// card, green reads clearly as "good habitat" and needs no warm treatment.)
//
// Heatmap intensity ramp anchors (low → high). The surface is RELATIVE (scaled
// to the grid's own best score): pale amber for the relatively-weak end, red for
// the relatively-strong end, with opacity also rising with score. Matches the
// site's `--heat-hi` (#FF4D4D).
val HeatLow = Color(0xFFFFD27A)             // pale amber (relatively low)
val HeatHigh = Color(0xFFFF4D4D)            // red (relatively high)

// Ranked map-pin discs (① = best) — a warm, high-contrast 5-tier ramp mirroring
// the heat surface so the numbered "best spots" stand out on green/topo tiles.
val MapPinExcellent = Color(0xFFFF4D4D)     // red — hottest
val MapPinVeryGood = Color(0xFFFF8A3D)      // orange
val MapPinPromising = Color(0xFFFFC24D)     // amber
val MapPinPossible = Color(0xFFE0B070)      // tan
val MapPinUnlikely = Color(0xFFB0A99A)      // muted stone
