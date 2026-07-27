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
    const val Ink000 = 0xFF0E0C0BL
    const val Ink050 = 0xFF12100EL
    const val Ink100 = 0xFF171310L
    const val Ink150 = 0xFF211B18L
    const val Ink200 = 0xFF29211DL
    const val Ink300 = 0xFF453A34L
    const val Ink350 = 0xFF807068L
    const val Ink400 = 0xFFB4A49AL
    const val Ink500 = 0xFFC9BBB2L
    const val Ink900 = 0xFFF5EEE8L

    // Warm paper rather than a neutral white inversion of the dark theme.
    const val Paper000 = 0xFFFFF9F3L
    const val Paper050 = 0xFFF7F0E9L
    const val Paper100 = 0xFFEEE4DBL
    const val Paper150 = 0xFFE5D9CFL
    const val Paper200 = 0xFFD8CBC1L
    const val Paper300 = 0xFFC6B7ACL
    const val Paper350 = 0xFF74665EL
    const val Paper600 = 0xFF6A5B53L
    const val Paper700 = 0xFF5E514AL
    const val Paper900 = 0xFF211A17L

    // Garnet is reserved for primary actions and selected states.
    const val AccentLight = 0xFFB4204AL
    const val AccentLightContainer = 0xFFF7DCE3L
    const val AccentLightOnContainer = 0xFF65102BL
    const val AccentDark = 0xFFF06A86L
    const val AccentDarkContainer = 0xFF501827L
    const val AccentDarkOnContainer = 0xFFFFD9E1L

    // Restrained evidence roles. They explain a match; they never communicate runtime state.
    const val EvidenceTextLight = 0xFF56616BL
    const val EvidenceTextDark = 0xFFC6CED6L
    const val EvidenceMeaningLight = 0xFF755000L
    const val EvidenceMeaningDark = 0xFFE8B861L
    const val EvidencePhotoLight = 0xFF146352L
    const val EvidencePhotoDark = 0xFF69C9AFL

    // Semantic roles. Each has exactly one meaning and is never used decoratively.
    const val ReadyLight = 0xFF1B684FL
    const val ReadyDark = 0xFF70CDA8L
    const val AttentionLight = 0xFF7A5000L
    const val AttentionDark = 0xFFE7B66DL
    const val ErrorLight = 0xFFA82931L
    const val ErrorDark = 0xFFFFAAA7L
}
