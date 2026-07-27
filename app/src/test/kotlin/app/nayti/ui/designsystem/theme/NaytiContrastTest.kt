package app.nayti.ui.designsystem.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrast gate of the visual system.
 *
 * Colour roles are verified as plain numbers so the check runs on the host without a device. Text
 * roles clear 4.5:1 on every real surface they are allowed to sit on. Evidence roles are included:
 * even though their primary use is a thin edge plus a label, the label must never rely on colour
 * alone. Optional blurred chrome is also tested over the brightest and darkest possible frame.
 */
class NaytiContrastTest {
    @Test
    fun lightTextRolesAreLegibleOnEverySurface() {
        val surfaces = listOf(
            "background" to NaytiPalette.Paper050,
            "surface" to NaytiPalette.Paper000,
            "surfaceHigh" to NaytiPalette.Paper100,
            "surfaceLow" to NaytiPalette.Paper150,
        )
        val textRoles = listOf(
            "ink" to NaytiPalette.Paper900,
            "inkMuted" to NaytiPalette.Paper700,
            "inkFaint" to NaytiPalette.Paper600,
            "accent" to NaytiPalette.AccentLight,
            "evidenceText" to NaytiPalette.EvidenceTextLight,
            "evidenceMeaning" to NaytiPalette.EvidenceMeaningLight,
            "evidencePhoto" to NaytiPalette.EvidencePhotoLight,
            "ready" to NaytiPalette.ReadyLight,
            "attention" to NaytiPalette.AttentionLight,
            "error" to NaytiPalette.ErrorLight,
        )
        assertAllPairs(textRoles, surfaces, TextMinimum)
    }

    @Test
    fun darkTextRolesAreLegibleOnEverySurface() {
        val surfaces = listOf(
            "background" to NaytiPalette.Ink000,
            "surface" to NaytiPalette.Ink100,
            "surfaceHigh" to NaytiPalette.Ink150,
            "surfaceLow" to NaytiPalette.Ink200,
        )
        val textRoles = listOf(
            "ink" to NaytiPalette.Ink900,
            "inkMuted" to NaytiPalette.Ink500,
            "evidenceText" to NaytiPalette.EvidenceTextDark,
            "evidenceMeaning" to NaytiPalette.EvidenceMeaningDark,
            "evidencePhoto" to NaytiPalette.EvidencePhotoDark,
            "ready" to NaytiPalette.ReadyDark,
            "attention" to NaytiPalette.AttentionDark,
            "error" to NaytiPalette.ErrorDark,
        )
        assertAllPairs(textRoles, surfaces, TextMinimum)
        assertAllPairs(
            textRoles = listOf("accent" to NaytiPalette.AccentDark),
            surfaces = surfaces.filterNot { (name, _) -> name == "surfaceLow" },
            minimum = TextMinimum,
        )
    }

    @Test
    fun faintDarkDecorationRemainsVisibleOnEverySurface() {
        val surfaces = listOf(
            "background" to NaytiPalette.Ink000,
            "surface" to NaytiPalette.Ink100,
            "surfaceHigh" to NaytiPalette.Ink150,
            "surfaceLow" to NaytiPalette.Ink200,
        )
        assertAllPairs(
            textRoles = listOf("inkFaint" to NaytiPalette.Ink400),
            surfaces = surfaces,
            minimum = NonTextMinimum,
        )
    }

    @Test
    fun accentContainersCarryTheirOwnText() {
        assertContrast("onAccent", NaytiPalette.Paper000, "accent", NaytiPalette.AccentLight, TextMinimum)
        assertContrast(
            "onAccentContainer",
            NaytiPalette.AccentLightOnContainer,
            "accentContainer",
            NaytiPalette.AccentLightContainer,
            TextMinimum,
        )
        assertContrast(
            "onAccent",
            NaytiPalette.Paper900,
            "accent",
            NaytiPalette.AccentDark,
            TextMinimum,
        )
        assertContrast(
            "onAccentContainer",
            NaytiPalette.AccentDarkOnContainer,
            "accentContainer",
            NaytiPalette.AccentDarkContainer,
            TextMinimum,
        )
    }

    @Test
    fun errorColorsCarryTheirOwnText() {
        assertContrast(
            "onError",
            NaytiPalette.Paper000,
            "error",
            NaytiPalette.ErrorLight,
            TextMinimum,
        )
        assertContrast(
            "onError",
            NaytiPalette.Paper900,
            "error",
            NaytiPalette.ErrorDark,
            TextMinimum,
        )
    }

    /**
     * Only [NaytiColors.outlineStrong] is gated.
     *
     * Controls in this system are identified by their fill, so the plain outline is decorative and
     * deliberately quiet. The strong outline is the one used where a border is the only affordance,
     * and it has to stay distinguishable on every surface it may sit on.
     */
    @Test
    fun outlinesThatIdentifyAControlAreDistinguishable() {
        assertContrast("outlineStrong", NaytiPalette.Paper350, "surface", NaytiPalette.Paper000, NonTextMinimum)
        assertContrast("outlineStrong", NaytiPalette.Paper350, "surfaceHigh", NaytiPalette.Paper100, NonTextMinimum)
        assertContrast("outlineStrong", NaytiPalette.Paper350, "surfaceLow", NaytiPalette.Paper150, NonTextMinimum)
        assertContrast("outlineStrong", NaytiPalette.Ink350, "surface", NaytiPalette.Ink100, NonTextMinimum)
        assertContrast("outlineStrong", NaytiPalette.Ink350, "surfaceHigh", NaytiPalette.Ink150, NonTextMinimum)
        assertContrast("outlineStrong", NaytiPalette.Ink350, "surfaceLow", NaytiPalette.Ink200, NonTextMinimum)
    }

    /**
     * Glass carries primary text only.
     *
     * A blurred panel over an unknown photograph cannot be made safe for secondary, accent or status
     * colours at any opacity that still reads as glass, so the design forbids them there instead of
     * hoping the photograph is friendly. The solid variant has no such limit.
     */
    @Test
    fun glassChromeCarriesPrimaryTextAboveTheBrightestAndDarkestPhoto() {
        assertChromeLegible(
            name = "dark glass",
            tint = NaytiPalette.Ink100,
            alpha = NaytiChromeAlpha.GlassDark,
            textRoles = listOf("ink" to NaytiPalette.Ink900),
        )
        assertChromeLegible(
            name = "light glass",
            tint = NaytiPalette.Paper000,
            alpha = NaytiChromeAlpha.GlassLight,
            textRoles = listOf("ink" to NaytiPalette.Paper900),
        )
    }

    @Test
    fun solidChromeCarriesEveryTextRoleAboveTheBrightestAndDarkestPhoto() {
        assertChromeLegible(
            name = "dark solid",
            tint = NaytiPalette.Ink100,
            alpha = NaytiChromeAlpha.Solid,
            textRoles = listOf(
                "ink" to NaytiPalette.Ink900,
                "inkMuted" to NaytiPalette.Ink500,
                "accent" to NaytiPalette.AccentDark,
                "evidenceText" to NaytiPalette.EvidenceTextDark,
                "evidenceMeaning" to NaytiPalette.EvidenceMeaningDark,
                "evidencePhoto" to NaytiPalette.EvidencePhotoDark,
                "ready" to NaytiPalette.ReadyDark,
                "attention" to NaytiPalette.AttentionDark,
                "error" to NaytiPalette.ErrorDark,
            ),
        )
        assertChromeLegible(
            name = "light solid",
            tint = NaytiPalette.Paper000,
            alpha = NaytiChromeAlpha.Solid,
            textRoles = listOf(
                "ink" to NaytiPalette.Paper900,
                "inkMuted" to NaytiPalette.Paper700,
                "inkFaint" to NaytiPalette.Paper600,
                "accent" to NaytiPalette.AccentLight,
                "evidenceText" to NaytiPalette.EvidenceTextLight,
                "evidenceMeaning" to NaytiPalette.EvidenceMeaningLight,
                "evidencePhoto" to NaytiPalette.EvidencePhotoLight,
                "ready" to NaytiPalette.ReadyLight,
                "attention" to NaytiPalette.AttentionLight,
                "error" to NaytiPalette.ErrorLight,
            ),
        )
    }

    /** A focus ring on glass is a boundary, not text, so it is held to the 3:1 non-text minimum. */
    @Test
    fun focusRingOnGlassStaysVisible() {
        assertChromeLegible(
            name = "dark glass",
            tint = NaytiPalette.Ink100,
            alpha = NaytiChromeAlpha.GlassDark,
            textRoles = listOf("accent ring" to NaytiPalette.AccentDark),
            minimum = NonTextMinimum,
        )
        assertChromeLegible(
            name = "light glass",
            tint = NaytiPalette.Paper000,
            alpha = NaytiChromeAlpha.GlassLight,
            textRoles = listOf("accent ring" to NaytiPalette.AccentLight),
            minimum = NonTextMinimum,
        )
    }

    private fun assertChromeLegible(
        name: String,
        tint: Long,
        alpha: Double,
        textRoles: List<Pair<String, Long>>,
        minimum: Double = TextMinimum,
    ) {
        for ((backdropName, backdrop) in listOf("white photo" to White, "black photo" to Black)) {
            val composite = composite(tint, alpha, backdrop)
            for ((roleName, role) in textRoles) {
                assertContrast(roleName, role, "$name over $backdropName", composite, minimum)
            }
        }
    }

    private fun assertAllPairs(
        textRoles: List<Pair<String, Long>>,
        surfaces: List<Pair<String, Long>>,
        minimum: Double,
    ) {
        for ((textName, text) in textRoles) {
            for ((surfaceName, surface) in surfaces) {
                assertContrast(textName, text, surfaceName, surface, minimum)
            }
        }
    }

    private fun assertContrast(
        foregroundName: String,
        foreground: Long,
        backgroundName: String,
        background: Long,
        minimum: Double,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$foregroundName on $backgroundName is ${"%.2f".format(ratio)}:1, expected at least $minimum:1",
            ratio >= minimum,
        )
    }

    private object NaytiChromeAlpha {
        val GlassDark = NaytiChrome.GlassTintAlphaDark.toDouble()
        val GlassLight = NaytiChrome.GlassTintAlphaLight.toDouble()
        const val Solid = 1.0
    }

    private companion object {
        const val TextMinimum = 4.5
        const val NonTextMinimum = 3.0
        const val White = 0xFFFFFFFFL
        const val Black = 0xFF000000L
    }
}

private fun composite(tint: Long, alpha: Double, backdrop: Long): Long {
    fun blend(shift: Int): Long {
        val top = (tint shr shift) and 0xFF
        val bottom = (backdrop shr shift) and 0xFF
        return (top * alpha + bottom * (1 - alpha)).toLong().coerceIn(0, 255)
    }
    return (0xFFL shl 24) or (blend(16) shl 16) or (blend(8) shl 8) or blend(0)
}

private fun contrastRatio(foreground: Long, background: Long): Double {
    val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
    val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(argb: Long): Double {
    fun channel(shift: Int): Double {
        val value = ((argb shr shift) and 0xFF) / 255.0
        return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}
