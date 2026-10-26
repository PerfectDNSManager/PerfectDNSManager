package app.perfectdnsmanager

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.util.StableApkStorage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StableApkStorageInstrumentedTest {
    @Test fun savedDownloadIsIndependentOfCacheAndCanBeReadBack() {
        assumeTrue(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in listOf("ranchu", "goldfish"))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("stable-storage-test", ".apk", ctx.cacheDir)
        val bytes = ByteArray(65537) { (it % 251).toByte() }
        file.writeBytes(bytes)
        val uri = try { StableApkStorage.save(ctx, file) } finally { file.delete() }
        try {
            assertEquals("content", uri.scheme)
            assertFalse(file.exists())
            ctx.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
            ctx.contentResolver.query(uri, arrayOf("relative_path", "is_pending"), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Download/PerfectDNSManager/", it.getString(0))
                assertEquals(0, it.getInt(1))
            }
        } finally { ctx.contentResolver.delete(uri, null, null) }
    }
}
