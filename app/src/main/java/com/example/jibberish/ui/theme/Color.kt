package com.example.jibberish.ui.theme

import androidx.compose.ui.graphics.Color

// Brand identity — deep violet + soft teal
val JibberishViolet = Color(0xFF7B61FF)
val JibberishVioletLight = Color(0xFFB39DFF)
val JibberishVioletDark = Color(0xFF4D2FD8)
val JibberishRose = Color(0xFF00BCD4)
val JibberishRoseDark = Color(0xFF00BCD4)

// Dark theme surfaces
val JibberishBlack = Color(0xFF0A0A0F)
val JibberishDarkGray = Color(0xFF12121A)
val JibberishSurfaceHigh = Color(0xFF1E1E2E)

// Light theme surfaces
val JibberishLightBg = Color(0xFFF5F5FF)
val JibberishLightSurface = Color(0xFFFFFFFF)

// Status colors — colorblind-safe (Wong palette; avoids red/green-only distinctions)
val StatusListening = Color(0xFF7B61FF)   // violet  — safe for all CVD types
val StatusProcessing = Color(0xFF0077B6)  // strong blue  — distinct from violet
val StatusReady = JibberishViolet          // alias: violet = good
val StatusDownloading = Color(0xFFF0A500) // amber   — universally distinguishable
val StatusError = Color(0xFFD55E00)       // orange-red (Wong palette)  — safe for deuteranopia/protanopia
val StatusIdle = Color(0xFF9E9E9E)        // neutral grey — never ambiguous

// On-color variants
val OnVioletDark = Color(0xFF1A0080)
val OnRoseDark = Color(0xFF5C0025)
