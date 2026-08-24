package fi.merilainen.treenivalmentaja.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first build of the update check shipped broken: `UpdateInfo` carried
 * `@JsonClass(generateAdapter = true)` while the project runs no Moshi codegen processor, so it
 * compiled, passed every unit test — they injected a fake service — and only failed on the phone
 * with "Failed to find the generated JsonAdapter class". These tests go through the real Moshi
 * configuration so that cannot happen again.
 *
 * They matter more now than they did: the app installs this APK itself, so the digest in this
 * payload is the only thing standing between the release and a file that is not it.
 */
class UpdateInfoParsingTest {

    /** Copied verbatim from the published release asset, not hand-written to fit the parser. */
    private val publishedPayload = """
        {
          "versionName": "1.0-9d9fe8f",
          "commit": "9d9fe8f",
          "builtAtUtc": "2026-08-08T12:32:27Z",
          "apkUrl": "https://github.com/merilainen-star/Treenivalmentaja2000/releases/download/test-build/Treenivalmentaja-test.apk",
          "apkSizeBytes": 19436901,
          "apkSha256": "9f2ec1b0f4a58a1e2a6d4b8c0e5f7a9b1c3d5e7f9a1b3c5d7e9f1a3b5c7d9e1f"
        }
    """.trimIndent()

    @Test
    fun `the payload CI publishes parses`() {
        val info = HttpUpdateService.parseUpdateInfo(publishedPayload)

        assertEquals("1.0-9d9fe8f", info.versionName)
        assertEquals("9d9fe8f", info.commit)
        assertEquals("2026-08-08T12:32:27Z", info.builtAtUtc)
        assertEquals(19_436_901L, info.apkSizeBytes)
        assertEquals(
            "9f2ec1b0f4a58a1e2a6d4b8c0e5f7a9b1c3d5e7f9a1b3c5d7e9f1a3b5c7d9e1f",
            info.apkSha256,
        )
        assertTrue(info.apkUrl.endsWith("Treenivalmentaja-test.apk"))
    }

    /** A truncated or unrelated body must fail loudly rather than yielding a half-built object. */
    @Test
    fun `a missing field is rejected`() {
        val result = runCatching {
            HttpUpdateService.parseUpdateInfo("""{"versionName": "1.0-abc1234"}""")
        }

        assertTrue("expected the parse to fail", result.isFailure)
    }

    /**
     * A release published before the digest existed. It parses to a perfectly usable download URL
     * and would install without a single check, which is exactly why it is refused instead.
     */
    @Test
    fun `a payload without a digest is rejected`() {
        val result = runCatching {
            HttpUpdateService.parseUpdateInfo(
                publishedPayload.lines()
                    .filterNot { it.contains("apkSha256") }
                    .joinToString("\n")
                    .replace("19436901,", "19436901")
            )
        }

        assertTrue("expected the parse to fail", result.isFailure)
    }

    @Test
    fun `a digest that is not a sha-256 is rejected`() {
        val result = runCatching {
            HttpUpdateService.parseUpdateInfo(
                publishedPayload.replace(
                    "9f2ec1b0f4a58a1e2a6d4b8c0e5f7a9b1c3d5e7f9a1b3c5d7e9f1a3b5c7d9e1f",
                    "not-a-digest",
                )
            )
        }

        assertTrue("expected the parse to fail", result.isFailure)
    }

    /** The APK is fetched by the app itself now, so the URL it will fetch has to be HTTPS. */
    @Test
    fun `a plain http download url is rejected`() {
        val result = runCatching {
            HttpUpdateService.parseUpdateInfo(publishedPayload.replace("https://", "http://"))
        }

        assertTrue("expected the parse to fail", result.isFailure)
    }

    @Test
    fun `a zero byte count is rejected`() {
        val result = runCatching {
            HttpUpdateService.parseUpdateInfo(publishedPayload.replace("19436901", "0"))
        }

        assertTrue("expected the parse to fail", result.isFailure)
    }

    @Test
    fun `text that is not json is rejected`() {
        val result = runCatching { HttpUpdateService.parseUpdateInfo("<html>404</html>") }

        assertTrue("expected the parse to fail", result.isFailure)
    }
}
