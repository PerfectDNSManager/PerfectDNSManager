package app.perfectdnsmanager.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Packet bridge: DNS stays in DnsVpnService; only other traffic enters HEV. */
internal class AntiDpiSession(
    private val context: Context,
    private val onPacket: (ByteArray) -> Unit,
    private val onFailure: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val connected = CountDownLatch(1)
    @Volatile private var worker: IAntiDpiWorker? = null
    private var endpoint: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var bound = false
    private fun failed() { if (!closed.get()) onFailure() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed.get()) return
            worker = IAntiDpiWorker.Stub.asInterface(binder)
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { worker = null; connected.countDown(); failed() }
        override fun onBindingDied(name: ComponentName) { connected.countDown(); failed() }
        override fun onNullBinding(name: ComponentName) { connected.countDown(); failed() }
    }
    fun start(ipv6: Boolean) {
        try {
            bound = context.bindService(Intent(context, AntiDpiWorkerService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound && connected.await(30, TimeUnit.SECONDS)) { "Anti-DPI worker unavailable" }
            val a=FileDescriptor(); val b=FileDescriptor()
            // Android's native sys/socket.h defines SOCK_NONBLOCK as O_NONBLOCK.
            // Use the API-21 constant: Java SOCK_NONBLOCK arrived in 29 and fcntlInt in 30.
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM or OsConstants.O_NONBLOCK, 0, a, b)
            val remote: ParcelFileDescriptor
            try { endpoint=ParcelFileDescriptor.dup(a); remote=ParcelFileDescriptor.dup(b) }
            finally { Os.close(a); Os.close(b) }
            val fd=endpoint!!.fileDescriptor
            remote.use { check(worker?.start(it, ipv6) == true) { "Anti-DPI engine refused to start" } }
            reader=Thread({
                val data=ByteArray(65535)
                val poll=StructPollfd().apply { this.fd=fd; events=OsConstants.POLLIN.toShort() }
                try {
                    while (!closed.get()) {
                        if (Os.poll(arrayOf(poll),250)>0) {
                            if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
                            val size=Os.read(fd,data,0,data.size)
                            if (size<=0) break
                            onPacket(data.copyOf(size))
                        }
                    }
                } catch (_: Exception) { /* close wakes the reader */ }
                finally { failed() }
            },"PdmAntiDpiReplies").apply { start() }
        } catch (e: Exception) { close(); throw e }
    }
    fun send(packet: ByteArray) {
        if (closed.get()) return
        try { endpoint?.let { Os.write(it.fileDescriptor,packet,0,packet.size) } }
        catch (e: android.system.ErrnoException) {
            // Bounded socket buffer: drop on backpressure, never block the DNS reader.
            if (e.errno != OsConstants.EAGAIN) failed()
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        try { endpoint?.let { Os.shutdown(it.fileDescriptor,OsConstants.SHUT_RDWR) } } catch (_: Exception) {}
        try { endpoint?.close() } catch (_: Exception) {}; endpoint=null
        // Wait for the old private process to die before a restart binds again.
        // Otherwise Android may attach the new session to the terminating worker.
        val binder = worker?.asBinder()
        val died = CountDownLatch(1)
        val death = IBinder.DeathRecipient { died.countDown() }
        if (binder != null) {
            try { binder.linkToDeath(death, 0) } catch (_: Exception) { died.countDown() }
            try { worker?.stop() } catch (_: Exception) { died.countDown() }
            try { died.await(3, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            try { binder.unlinkToDeath(death, 0) } catch (_: Exception) {}
        }
        worker=null
        if (bound) { try { context.unbindService(connection) } catch (_: Exception) {}; bound=false }
        if (reader !== Thread.currentThread()) try { reader?.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        reader=null
    }
}
