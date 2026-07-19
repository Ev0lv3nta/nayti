package app.nayti.ui

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexingPeriodTest {
    @Test
    fun presetUsesCalendarMonthsAndLocalStartOfDay() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2024, 5, 31, 18, 45, 0, 0, zone).toInstant().toEpochMilli()

        val cutoff = indexingCutoffMillis(now, months = 3, zoneId = zone)

        assertEquals(
            ZonedDateTime.of(2024, 2, 29, 0, 0, 0, 0, zone).toInstant(),
            Instant.ofEpochMilli(cutoff),
        )
    }
}
