package com.decio.skipii

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Requests used to ask YouTube as "US" whoever and wherever the user was, which pulled another
 * country's recommendations into Home (GitHub #52). The country now comes from the page
 * youtube.com serves this connection (captured 2026-11-09: `"GL":"LY"` from Libya).
 */
class AccountConfigTest {
    @Test fun contentCountryFromYtcfg() {
        val html = """var ytcfg = {"GL":"LY","INNERTUBE_CLIENT_VERSION":"2.20261105.05.01","VISITOR_DATA":"Cgto"};"""
        assertEquals("LY", parseContentCountry(html))
    }

    @Test fun contentCountryIsUppercased() {
        assertEquals("GB", parseContentCountry("""{"GL":"gb"}"""))
    }

    @Test fun noContentCountry() {
        assertNull(parseContentCountry("""{"hl":"en","INNERTUBE_CLIENT_VERSION":"2.20261105.05.01"}"""))
    }

    /** Only a two-letter country code counts; a longer or numeric value is left alone. */
    @Test fun ignoresOtherGlValues() {
        assertNull(parseContentCountry("""{"GL":"USA"}"""))
        assertNull(parseContentCountry("""{"GL":12}"""))
    }
}
