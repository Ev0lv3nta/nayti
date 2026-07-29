package app.nayti.ui.designsystem.theme

/**
 * Raw colour values of the neutral Nayti palette.
 *
 * Values live as plain hex longs so contrast can be verified by host unit tests without pulling in
 * any Android or Compose type. Roles are assigned in [NaytiColors]; nothing outside this file
 * should hardcode a colour literal.
 */
internal object NaytiPalette {
    // Warm graphite and obsidian. Photographs remain the strongest source of colour.
    const val Ink000 = 0xFF0F0D0CL
    const val Ink050 = 0xFF141110L
    const val Ink100 = 0xFF1A1615L
    const val InkEdgeTop = 0xFF1E1918L
    const val InkEdgeBottom = 0xFF171312L
    const val InkGlassTop = 0xFF221D1BL
    const val Ink150 = 0xFF221D1BL
    const val Ink200 = 0xFF2B2523L
    const val Ink300 = 0xFF332C29L
    const val Ink350 = 0xFF7E736DL
    const val Ink400 = 0xFF7E736DL
    const val Ink500 = 0xFFB5AAA3L
    const val Ink900 = 0xFFF3EEEAL

    // Warm paper rather than a neutral white inversion of the dark theme.
    const val Paper000 = 0xFFFFFFFFL
    const val Paper050 = 0xFFF6F2EEL
    const val Paper075 = 0xFFFCF9F6L
    const val Paper100 = 0xFFFBF7F3L
    const val Paper150 = 0xFFF3EDE7L
    const val Paper200 = 0xFFE5DCD4L
    const val Paper300 = 0xFFD6CCC3L
    const val Paper350 = 0xFF8C8078L
    const val Paper600 = 0xFF5B514AL
    const val Paper700 = 0xFF5B514AL
    const val Paper900 = 0xFF17120FL

    // Garnet is reserved for primary actions and selected states.
    const val AccentLight = 0xFFA81B38L
    const val AccentLightTop = 0xFFC43050L
    const val AccentLightBottom = 0xFF9D1F3BL
    const val AccentLightContainer = 0xFFF6DDE2L
    const val AccentLightOnContainer = 0xFF65102BL
    const val AccentDark = 0xFFE1596FL
    const val AccentDarkTop = 0xFFC43050L
    const val AccentDarkBottom = 0xFF9D1F3BL
    const val AccentDarkContainer = 0xFF4A121FL
    const val AccentDarkOnContainer = 0xFFF3EEEAL

    // Restrained evidence roles. They explain a match; they never communicate runtime state.
    const val EvidenceTextLight = 0xFF4E555EL
    const val EvidenceTextDark = 0xFFD3D8DEL
    const val EvidenceMeaningLight = 0xFF8A5A0BL
    const val EvidenceMeaningDark = 0xFFE8B563L
    const val EvidencePhotoLight = 0xFF12705AL
    const val EvidencePhotoDark = 0xFF6FC49FL

    // Semantic roles. Each has exactly one meaning and is never used decoratively.
    const val ReadyLight = 0xFF4E555EL
    const val ReadyDark = 0xFFD3D8DEL
    const val AttentionLight = 0xFF8A5A0BL
    const val AttentionDark = 0xFFE8B563L
    const val ErrorLight = 0xFFB3261EL
    const val ErrorDark = 0xFFFF6B5CL
}
