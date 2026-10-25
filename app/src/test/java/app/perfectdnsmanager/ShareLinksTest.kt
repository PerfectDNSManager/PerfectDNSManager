package app.perfectdnsmanager

import app.perfectdnsmanager.util.ShareLinks
import org.junit.Assert.*
import org.junit.Test

class ShareLinksTest {
    @Test fun acceptsWebsiteAndAppShareLinks() {
        for (url in listOf("abcd12", "ABCD12", "https://perfectdnsmanager.app/d/abcd12", "https://beta.perfectdnsmanager.app/decrypt.html?s=abcd12", "https://pdm.appstorefr.net/r/abcd12/"))
            assertEquals(url, "abcd12", ShareLinks.parse(url)?.slug)
    }
    @Test fun rejectsUntrustedOrAmbiguousDestinations() {
        for (url in listOf("https://evil.test/d/abcd12", "http://perfectdnsmanager.app/d/abcd12", "https://user@perfectdnsmanager.app/d/abcd12", "https://perfectdnsmanager.app:444/d/abcd12", "https://perfectdnsmanager.app/other/abcd12", "https://perfectdnsmanager.app/decrypt.html?s=../oops", "x".repeat(2049)))
            assertNull(url, ShareLinks.parse(url))
    }
}
