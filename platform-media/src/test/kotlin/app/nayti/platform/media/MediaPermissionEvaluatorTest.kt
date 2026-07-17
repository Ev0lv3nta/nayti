package app.nayti.platform.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPermissionEvaluatorTest {
    @Test
    fun api30To32UseLegacyReadPermission() {
        assertEquals(
            MediaAccessScope.Full,
            MediaPermissionEvaluator.evaluate(30, true, false, false),
        )
        assertEquals(
            MediaAccessScope.None,
            MediaPermissionEvaluator.evaluate(32, false, false, false),
        )
        assertArrayEquals(
            arrayOf(MediaPermissions.ReadExternalStorage),
            MediaPermissionEvaluator.requestPermissions(32),
        )
    }

    @Test
    fun api33HasOnlyFullOrNone() {
        assertEquals(
            MediaAccessScope.Full,
            MediaPermissionEvaluator.evaluate(33, false, true, false),
        )
        assertEquals(
            MediaAccessScope.None,
            MediaPermissionEvaluator.evaluate(33, false, false, true),
        )
    }

    @Test
    fun api34DistinguishesSelectedAndFull() {
        assertEquals(
            MediaAccessScope.Selected,
            MediaPermissionEvaluator.evaluate(34, false, false, true),
        )
        assertEquals(
            MediaAccessScope.Full,
            MediaPermissionEvaluator.evaluate(34, false, true, true),
        )
        assertArrayEquals(
            arrayOf(
                MediaPermissions.ReadImages,
                MediaPermissions.ReadVisualUserSelected,
            ),
            MediaPermissionEvaluator.requestPermissions(34),
        )
    }
}
