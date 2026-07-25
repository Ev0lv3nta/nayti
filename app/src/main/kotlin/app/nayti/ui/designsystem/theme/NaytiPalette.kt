package app.nayti.ui.designsystem.theme

/**
 * Raw colour values of the neutral Nayti palette.
 *
 * Values live as plain hex longs so contrast can be verified by host unit tests without pulling in
 * any Android or Compose type. Roles are assigned in [NaytiColors]; nothing outside this file
 * should hardcode a colour literal.
 */
internal object NaytiPalette {
    // Neutral scale, slightly cool. Photographs supply the colour; the shell does not.
    const val Ink000 = 0xFF0B0C0EL
    const val Ink050 = 0xFF101216L
    const val Ink100 = 0xFF14161AL
    const val Ink150 = 0xFF1C1F25L
    const val Ink200 = 0xFF24272EL
    const val Ink300 = 0xFF3A3E47L
    const val Ink350 = 0xFF6A7180L
    const val Ink400 = 0xFF838B9BL
    const val Ink500 = 0xFFA9AFBCL
    const val Ink900 = 0xFFECEEF2L

    const val Paper000 = 0xFFFFFFFFL
    const val Paper050 = 0xFFFAFAFBL
    const val Paper100 = 0xFFF1F2F5L
    const val Paper200 = 0xFFE4E6EBL
    const val Paper300 = 0xFFC9CDD6L
    const val Paper350 = 0xFF828894L
    const val Paper600 = 0xFF61697BL
    const val Paper700 = 0xFF4C5464L
    const val Paper900 = 0xFF0F1115L

    // Single cool accent: reads as "system", stays legible against arbitrary photographs.
    const val AccentLight = 0xFF3D4FCFL
    const val AccentLightContainer = 0xFFE3E6FFL
    const val AccentLightOnContainer = 0xFF181F5CL
    const val AccentDark = 0xFFAEB8FFL
    const val AccentDarkContainer = 0xFF272D5AL
    const val AccentDarkOnContainer = 0xFFDDE2FFL

    // Semantic roles. Each has exactly one meaning and is never used decoratively.
    const val ReadyLight = 0xFF146B4EL
    const val ReadyDark = 0xFF5BD3A8L
    const val AttentionLight = 0xFF8A4E00L
    const val AttentionDark = 0xFFF0B36BL
    const val ErrorLight = 0xFFA8281FL
    const val ErrorDark = 0xFFFFB4ABL
}
