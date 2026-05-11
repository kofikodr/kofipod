// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [streamApkInto] against a `MockEngine`-backed [HttpClient] and a temp directory
 * stand-in for `<filesDir>/updates/`. The production wrapper [UpdateInstaller.download]
 * is just a 4-line Context + repo glue around this function — the streaming, resume,
 * and cleanup logic all live here, so this is where the meaningful coverage belongs.
 */
class UpdateInstallerTest {
    private lateinit var dir: File

    private val info =
        UpdateInfo(
            version = "1.4.0",
            releaseUrl = "https://github.com/kofikodr/kofipod/releases/v1.4.0",
            apkUrl = "https://example.com/kofipod-foss-1.4.0.apk",
            apkSizeBytes = APK_SIZE,
            releaseNotes = "",
        )

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("updates-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `fresh download writes complete file when no partial exists`() =
        runTest {
            val body = ByteArray(APK_SIZE.toInt()) { (it and 0xFF).toByte() }
            val client = newClient { _ -> respond(body, HttpStatusCode.OK) }

            val path = streamApkInto(client, dir, info, onProgress = { _, _ -> })

            val final = File(path)
            assertTrue(final.exists(), "final APK should exist after success")
            assertEquals(APK_SIZE, final.length())
            assertContentEquals(body, final.readBytes())
            assertFalse(File(dir, "kofipod-1.4.0.apk.partial").exists(), "partial cleaned up")
        }

    @Test
    fun `resume sends Range header and appends to existing partial when server returns 206`() =
        runTest {
            val firstHalf = ByteArray(HALF.toInt()) { (it and 0xFF).toByte() }
            val secondHalf = ByteArray(HALF.toInt()) { ((it + HALF.toInt()) and 0xFF).toByte() }
            File(dir, "kofipod-1.4.0.apk.partial").writeBytes(firstHalf)

            var observedRangeHeader: String? = null
            val client =
                newClient { request ->
                    observedRangeHeader = request.headers[HttpHeaders.Range]
                    respond(secondHalf, HttpStatusCode.PartialContent)
                }

            val path = streamApkInto(client, dir, info, onProgress = { _, _ -> })

            assertEquals("bytes=$HALF-", observedRangeHeader)
            val final = File(path)
            assertEquals(APK_SIZE, final.length())
            assertContentEquals(firstHalf + secondHalf, final.readBytes())
            assertFalse(File(dir, "kofipod-1.4.0.apk.partial").exists())
        }

    @Test
    fun `server returning 200 to a Range request wipes partial and restarts cleanly`() =
        runTest {
            // Server ignored our Range (some CDNs / proxies do this). Pre-seed a partial
            // with random bytes that DON'T match the real start of the file. We need to be
            // sure those bytes don't leak into the final APK.
            val stalePartial = ByteArray(HALF.toInt()) { 0x55.toByte() }
            File(dir, "kofipod-1.4.0.apk.partial").writeBytes(stalePartial)

            val fullBody = ByteArray(APK_SIZE.toInt()) { (it and 0xFF).toByte() }
            val client = newClient { _ -> respond(fullBody, HttpStatusCode.OK) }

            val path = streamApkInto(client, dir, info, onProgress = { _, _ -> })

            val final = File(path)
            assertEquals(APK_SIZE, final.length())
            assertContentEquals(fullBody, final.readBytes(), "final must reflect the fresh body, not stale partial")
            assertFalse(File(dir, "kofipod-1.4.0.apk.partial").exists(), "partial must be gone after clean restart")
        }

    // NOTE on the partial-preservation-on-exception invariant: streamApkInto's structure
    // guarantees that a thrown mid-stream read does not delete the partial. The proof is
    // local to the function: no `partial.delete()` call exists inside any `catch` or
    // `finally`, and the partial→target rename only runs *after* `.execute { }` returns
    // cleanly. We do not have an automated test for this because reliably simulating a
    // ByteReadChannel close-with-cause through Ktor 3's `toInputStream()` bridge proved
    // brittle in this codebase's test harness (the cause is swallowed and EOF is reported
    // to the InputStream consumer instead of a thrown read). Future refactors must
    // preserve the no-delete-in-catch-or-finally invariant; a code reviewer should reject
    // any change that introduces such a delete. See KDoc on streamApkInto.

    @Test
    fun `old-version partials are deleted at the start of a new download`() =
        runTest {
            File(dir, "kofipod-1.3.0.apk.partial").writeBytes(ByteArray(200))
            File(dir, "kofipod-1.3.0.apk").writeBytes(ByteArray(400))
            File(dir, "stray-file.tmp").writeBytes(ByteArray(50))

            val body = ByteArray(APK_SIZE.toInt())
            val client = newClient { _ -> respond(body, HttpStatusCode.OK) }

            streamApkInto(client, dir, info, onProgress = { _, _ -> })

            val remaining = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
            assertEquals(setOf("kofipod-1.4.0.apk"), remaining)
        }

    @Test
    fun `progress callback fires with starting offset on resume`() =
        runTest {
            File(dir, "kofipod-1.4.0.apk.partial").writeBytes(ByteArray(HALF.toInt()))
            val client = newClient { _ -> respond(ByteArray(HALF.toInt()), HttpStatusCode.PartialContent) }

            val progressEvents = mutableListOf<Long>()
            streamApkInto(client, dir, info, onProgress = { downloaded, _ -> progressEvents += downloaded })

            assertTrue(progressEvents.isNotEmpty(), "expected at least one onProgress callback")
            assertEquals(HALF, progressEvents.first(), "first progress event should seed UI with existing offset")
            assertEquals(APK_SIZE, progressEvents.last(), "last progress event should match completed size")
            // Monotonicity guard: a regression that resets the counter mid-stream would
            // still pass the first/last checks if it climbs back. Catching it here gives
            // the UI's "downloaded MB" indicator a stable contract.
            assertEquals(progressEvents.sorted(), progressEvents, "progress must be monotonically non-decreasing")
        }

    @Test
    fun `no Range header is sent when no partial exists`() =
        runTest {
            var observedRangeHeader: String? = null
            val client =
                newClient { request ->
                    observedRangeHeader = request.headers[HttpHeaders.Range]
                    respond(ByteArray(APK_SIZE.toInt()), HttpStatusCode.OK)
                }

            streamApkInto(client, dir, info, onProgress = { _, _ -> })

            assertNull(observedRangeHeader, "fresh download must not advertise resume")
        }

    private fun newClient(handler: io.ktor.client.engine.mock.MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(HttpTimeout)
        }

    companion object {
        // Small enough to keep tests fast, deliberately NOT a multiple of streamApkInto's
        // 64 KiB chunk size — exercises the partial-final-read branch of the read loop,
        // which would be silently skipped if the body fit a whole number of buffers.
        private const val APK_SIZE: Long = 200_000L
        private const val HALF: Long = APK_SIZE / 2
    }
}
