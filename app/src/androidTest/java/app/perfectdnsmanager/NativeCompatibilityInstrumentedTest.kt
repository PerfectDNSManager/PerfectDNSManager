package app.perfectdnsmanager

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.util.EncryptedSharer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

@RunWith(AndroidJUnit4::class)
class NativeCompatibilityInstrumentedTest {
    @Test fun actualPageSizeAndProductionArgon2MatchIndependentVector() {
        val expected = InstrumentationRegistry.getArguments().getString("expectedPageSize")?.toLong()
        val size = Os.sysconf(OsConstants._SC_PAGESIZE)
        if (expected != null) assertEquals(expected.toLong(), size)
        val companion = EncryptedSharer.Companion
        val method = companion.javaClass.getDeclaredMethod("argon2id", ByteArray::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        val result = method.invoke(companion, "pdm-test-password".toByteArray(), "pdm-test-salt-16!".toByteArray(), 16) as ByteArray
        // Independent libargon2 / Python argon2-cffi: Argon2id v19, t=3,m=32768,p=1.
        assertEquals("10c4a1edca412df1d3d067b556e2c6c1", result.joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    @Test fun conscryptTls13WorksAndExporterOnAdbSupportedAndroid() {
        assertTrue(Conscrypt.isAvailable())
        val provider = Conscrypt.newProvider()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=localhost")
        val certBytes = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(System.currentTimeMillis()-60000),
            Date(System.currentTimeMillis()+3600000), name, pair.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)).encoded
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certBytes.inputStream())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setKeyEntry("test", pair.private, "test".toCharArray(), arrayOf(cert))
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,"test".toCharArray()) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val context = SSLContext.getInstance("TLSv1.3",provider).apply { init(km.keyManagers,tm.trustManagers,null) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            (context.serverSocketFactory.createServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()) as SSLServerSocket).use { server ->
                server.soTimeout=15000
                val peer = executor.submit<ByteArray> {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout=15000; socket.enabledProtocols=arrayOf("TLSv1.3");socket.startHandshake()
                        val material=if(Build.VERSION.SDK_INT>=30) Conscrypt.exportKeyingMaterial(socket,"adb-label-test",null,32)!! else byteArrayOf()
                        socket.outputStream.write(7);socket.inputStream.read();material
                    }
                }
                (context.socketFactory.createSocket(java.net.InetAddress.getLoopbackAddress(),server.localPort) as SSLSocket).use { socket ->
                    socket.soTimeout=15000;socket.enabledProtocols=arrayOf("TLSv1.3");socket.startHandshake()
                    assertEquals("TLSv1.3",socket.session.protocol)
                    val material=if(Build.VERSION.SDK_INT>=30) Conscrypt.exportKeyingMaterial(socket,"adb-label-test",null,32)!! else byteArrayOf()
                    assertEquals(7,socket.inputStream.read());socket.outputStream.write(9)
                    assertArrayEquals(material,peer.get(20,TimeUnit.SECONDS))
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun adbSpakeNativeContextLoadsAndReleasesOnSupportedAndroid() {
        assumeTrue(Build.VERSION.SDK_INT >= 30) // Wireless ADB pairing is an Android 11+ feature.
        System.loadLibrary("adb")
        val type=Class.forName("moe.shizuku.manager.adb.PairingContext")
        val create=type.getDeclaredMethod("nativeConstructor",Boolean::class.javaPrimitiveType,ByteArray::class.java).apply{isAccessible=true}
        val constructor=type.getDeclaredConstructor(Long::class.javaPrimitiveType).apply{isAccessible=true}
        val client=constructor.newInstance(create.invoke(null,true,"test pairing secret".toByteArray()))
        val server=constructor.newInstance(create.invoke(null,false,"test pairing secret".toByteArray()))
        fun invoke(target:Any,method:String,vararg args:Any?):Any? = type.declaredMethods.first{it.name==method}.apply{isAccessible=true}.invoke(target,*args)
        try {
            assertTrue(invoke(client,"initCipher",invoke(server,"getMsg")) as Boolean)
            assertTrue(invoke(server,"initCipher",invoke(client,"getMsg")) as Boolean)
            val plain="synthetic pairing data".toByteArray()
            assertArrayEquals(plain,invoke(server,"decrypt",invoke(client,"encrypt",plain)) as ByteArray)
        } finally { invoke(client,"destroy");invoke(server,"destroy") }
    }
}
