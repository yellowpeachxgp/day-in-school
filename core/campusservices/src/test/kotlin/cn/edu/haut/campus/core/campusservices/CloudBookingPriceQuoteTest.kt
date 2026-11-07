package cn.edu.haut.campus.core.campusservices

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudBookingPriceQuoteTest {
    @Test fun preservesExactAcceptedVersionAndAmount() {
        val quote = CloudBookingPriceQuote("price-v2", 12_345L)
        assertEquals("price-v2", quote.version)
        assertEquals(12_345L, quote.micropeach)
        assertEquals("1.2345", quote.displayPeach)
        assertEquals("0", CloudBookingPriceQuote("free", 0L).displayPeach)
        assertEquals("1", CloudBookingPriceQuote("whole", 10_000L).displayPeach)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingVersion() {
        CloudBookingPriceQuote(" ", 10_000L)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNegativeAmount() {
        CloudBookingPriceQuote("price-v1", -1L)
    }
}
