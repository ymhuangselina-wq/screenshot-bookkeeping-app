package com.example.screenshotbookkeeping

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceUrlTest {
    @Test fun keepsServiceRoot() {
        assertEquals("https://example.fcapp.run", normalizeServiceUrl(" https://example.fcapp.run/ "))
    }

    @Test fun stripsHealthEndpointCopiedFromBrowser() {
        assertEquals("https://example.fcapp.run", normalizeServiceUrl("https://example.fcapp.run/health"))
        assertEquals("https://example.fcapp.run", normalizeServiceUrl("https://example.fcapp.run/health/"))
    }
}
