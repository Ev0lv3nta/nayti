package app.nayti.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.Dp

fun SemanticsNodeInteraction.assertTouchHeightIsAtLeast(
    expected: Dp,
): SemanticsNodeInteraction {
    val node = fetchSemanticsNode()
    val actual = with(node.layoutInfo.density) {
        node.touchBoundsInRoot.height.toDp()
    }
    if (actual < expected) {
        throw AssertionError("Actual touch height is $actual, expected at least $expected")
    }
    return this
}
