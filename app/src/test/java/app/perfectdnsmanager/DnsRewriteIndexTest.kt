package app.perfectdnsmanager
import app.perfectdnsmanager.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
class DnsRewriteIndexTest {
    @Test fun preservesPriorityAndSnapshotWithCaseInsensitiveLookup() {
        val rules = mutableListOf(DnsRewriteRule(1,"EXAMPLE.com","disabled",false), DnsRewriteRule(2,"Example.com","first"), DnsRewriteRule(3,"example.com","last"))
        val index = DnsRewriteIndex.from(rules)
        rules.clear()
        assertEquals("first",index.target("EXAMPLE.COM"))
        assertEquals(1,index.size)
        assertNull(index.target("other.example.com"))
    }
    @Test fun lookupIsIndependentOfTurkishLocale() {
        val before=Locale.getDefault()
        try { Locale.setDefault(Locale("tr"));assertEquals("target",DnsRewriteIndex.from(listOf(DnsRewriteRule(1,"I.EXAMPLE","target"))).target("i.example")) }
        finally { Locale.setDefault(before) }
    }
}
