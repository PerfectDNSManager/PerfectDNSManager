package app.perfectdnsmanager.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** One session per private process: native globals never leak across restarts. */
class AntiDpiWorkerService : Service() {
    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private fun checkCaller() = check(Binder.getCallingUid() == applicationInfo.uid)
    private fun terminate() {
        closing.set(true)
        Process.killProcess(Process.myPid())
    }
    private val binder = object : IAntiDpiWorker.Stub() {
        override fun start(bridge: ParcelFileDescriptor, ipv6: Boolean): Boolean {
            checkCaller()
            if (!started.compareAndSet(false, true)) { bridge.close(); return false }
            try {
                Thread({
                    try { AntiDpiNative.proxy() } finally { if (!closing.get()) terminate() }
                }, "PdmByeDpi").start()
                val deadline = SystemClock.elapsedRealtime() + 5000
                var port = AntiDpiNative.port()
                while (port == 0 && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(20); port = AntiDpiNative.port()
                }
                check(port in 1..65535)
                val config = """
                    tunnel:
                      mtu: 1500
                      ipv4: 192.0.2.1
                      ${if (ipv6) "ipv6: 'fdfe:dcba:9876::1'" else ""}
                    socks5:
                      address: 127.0.0.1
                      port: $port
                      udp: 'udp'
                    misc:
                      task-stack-size: 86016
                      tcp-buffer-size: 16384
                      max-session-count: 64
                      connect-timeout: 10000
                      tcp-read-write-timeout: 120000
                      udp-read-write-timeout: 30000
                      log-level: error
                      log-file: /dev/null
                """.trimIndent()
                val descriptor = bridge.detachFd()
                Thread({
                    try { AntiDpiNative.tunnel(config, descriptor) }
                    finally { if (!closing.get()) terminate() }
                }, "PdmAntiDpiTunnel").start()
                return true
            } catch (_: Throwable) {
                bridge.close()
                Thread { SystemClock.sleep(50); terminate() }.start()
                return false
            }
        }
        override fun stop() { checkCaller(); terminate() }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onUnbind(intent: Intent): Boolean { terminate(); return false }
    override fun onDestroy() { super.onDestroy(); terminate() }
}

internal object AntiDpiNative {
    init { System.loadLibrary("pdm_antidpi") }
    external fun proxy(): Int
    external fun port(): Int
    external fun tunnel(config: String, fd: Int): Int
}
