package com.example.screenshotbookkeeping

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class ValidationTest {
    @Test fun generatedTimeUsesChinaOffset() {
        assertEquals(8 * 60 * 60, OffsetDateTime.parse(MainViewModel.now()).offset.totalSeconds)
    }
}
