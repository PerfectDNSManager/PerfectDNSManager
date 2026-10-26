package app.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.service.DnsVpnService
import app.perfectdnsmanager.util.DnsMessages
import app.perfectdnsmanager.util.DnsWire
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.xbill.DNS.Message
import org.xbill.DNS.Section

@RunWith(AndroidJUnit4::class)
class DoqConcurrencyInstrumentedTest {
    @Test fun concurrentAAndAaaaWaitForColdConnection() {
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation(); val ctx = inst.targetContext
        val endpoint = InstrumentationRegistry.getArguments().getString("doqEndpoint") ?: "quic://p0.freedns.controld.com"
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} ACTIVATE_VPN allow")).use { it.readBytes() }
        assertNull(android.net.VpnService.prepare(ctx))
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putBoolean("disable_ipv6", false).commit()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        fun vpnActive() = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        val pool = Executors.newFixedThreadPool(8)
        try {
            for (round in 0..2) {
                androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, DnsVpnService::class.java)
                    .setAction(DnsVpnService.ACTION_START).putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, endpoint))
                val end = SystemClock.elapsedRealtime()+15000
                while ((!DnsVpnService.isVpnRunning || !vpnActive()) && SystemClock.elapsedRealtime()<end) SystemClock.sleep(50)
                assertTrue(DnsVpnService.isVpnRunning && vpnActive())
                val latch=CountDownLatch(1)
                val jobs=(0..7).map { index -> pool.submit(Callable {
                    latch.await()
                    val type=if(index%2==0) 1 else 28
                    val q=DnsWire.buildQuery("example.com", 1200+round*10+index).also { it[it.size-3]=type.toByte() }
                    val r=DnsWire.exchange(InetAddress.getByName("192.0.2.2"),q,18000)
                    assertNotNull("round=$round type=$type",r)
                    assertTrue(DnsMessages.matches(q,r!!))
                    val message=Message(r)
                    assertEquals("round=$round type=$type",0,message.rcode)
                    assertTrue(message.getSectionArray(Section.ANSWER).any { it.type==type })
                    true
                }) }
                latch.countDown(); jobs.forEach { assertTrue(it.get(25,TimeUnit.SECONDS)) }
                ctx.startService(Intent(ctx,DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
                val stopEnd=SystemClock.elapsedRealtime()+10000
                while((DnsVpnService.isVpnRunning || vpnActive()) && SystemClock.elapsedRealtime()<stopEnd) SystemClock.sleep(50)
                assertFalse(DnsVpnService.isVpnRunning || vpnActive())
            }
        } finally { pool.shutdownNow(); ctx.startService(Intent(ctx,DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)) }
    }
}
