package net.appstorefr.perfectdnsmanager.util

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object SpeedTester {

    private const val TAG = "SpeedTester"

    data class SpeedResult(
        val downloadMbps: Double,
        val uploadMbps: Double,
        val pingMs: Long
    )

    private fun createClient(timeoutSec: Long): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Run full internet speed test (download + upload + ping).
     * Uses Cloudflare speed test endpoints. Fallback to tele2 for download.
     * Must be called from a background thread.
     */
    fun runFullTest(onProgress: ((String) -> Unit)? = null): SpeedResult {
        var downloadMbps = 0.0
        var uploadMbps = 0.0
        var pingMs = 0L

        // 1. Ping test (3x HTTP HEAD, take median)
        onProgress?.invoke("Ping...")
        pingMs = measurePing()

        // 2. Download test
        onProgress?.invoke("Download...")
        downloadMbps = measureDownload()

        // 3. Upload test
        onProgress?.invoke("Upload...")
        uploadMbps = measureUpload()

        return SpeedResult(downloadMbps, uploadMbps, pingMs)
    }

    private fun measurePing(): Long {
        val client = createClient(5)
        val pings = mutableListOf<Long>()

        for (i in 0 until 3) {
            try {
                val request = Request.Builder()
                    .url("https://speed.cloudflare.com/__down?bytes=0")
                    .head()
                    .build()
                val start = System.nanoTime()
                val response = client.newCall(request).execute()
                val elapsed = (System.nanoTime() - start) / 1_000_000
                response.close()
                if (response.isSuccessful) pings.add(elapsed)
            } catch (e: Exception) {
                Log.w(TAG, "Ping attempt $i failed", e)
            }
        }

        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()

        return if (pings.isNotEmpty()) pings.sorted()[pings.size / 2] else -1
    }

    private fun measureDownload(): Double {
        val client = createClient(30)
        val downloadBytes = 5_000_000L // 5 MB

        try {
            // Try Cloudflare first
            val request = Request.Builder()
                .url("https://speed.cloudflare.com/__down?bytes=$downloadBytes")
                .build()
            val start = System.nanoTime()
            val response = client.newCall(request).execute()
            val body = response.body
            if (response.isSuccessful && body != null) {
                // Read all bytes to measure actual throughput
                val buffer = ByteArray(8192)
                val stream = body.byteStream()
                var totalRead = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    totalRead += read
                }
                stream.close()
                val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
                response.close()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                if (elapsed > 0 && totalRead > 0) {
                    return (totalRead * 8.0) / (elapsed * 1_000_000)
                }
            }
            response.close()
        } catch (e: Exception) {
            Log.w(TAG, "Cloudflare download failed, trying fallback", e)
        }

        // Fallback: tele2
        try {
            val fallbackClient = createClient(30)
            val request = Request.Builder()
                .url("http://speedtest.tele2.net/1MB.zip")
                .build()
            val start = System.nanoTime()
            val response = fallbackClient.newCall(request).execute()
            val body = response.body
            if (response.isSuccessful && body != null) {
                val buffer = ByteArray(8192)
                val stream = body.byteStream()
                var totalRead = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    totalRead += read
                }
                stream.close()
                val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
                response.close()
                fallbackClient.dispatcher.executorService.shutdown()
                fallbackClient.connectionPool.evictAll()
                if (elapsed > 0 && totalRead > 0) {
                    return (totalRead * 8.0) / (elapsed * 1_000_000)
                }
            }
            response.close()
            fallbackClient.dispatcher.executorService.shutdown()
            fallbackClient.connectionPool.evictAll()
        } catch (e: Exception) {
            Log.e(TAG, "Fallback download also failed", e)
        }

        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        return 0.0
    }

    private fun measureUpload(): Double {
        val client = createClient(30)
        val uploadSize = 2_000_000 // 2 MB

        try {
            val data = ByteArray(uploadSize) { (it % 256).toByte() }
            val body = data.toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url("https://speed.cloudflare.com/__up")
                .post(body)
                .build()
            val start = System.nanoTime()
            val response = client.newCall(request).execute()
            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
            response.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            if (elapsed > 0 && response.isSuccessful) {
                return (uploadSize * 8.0) / (elapsed * 1_000_000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload test failed", e)
        }

        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        return 0.0
    }
}
