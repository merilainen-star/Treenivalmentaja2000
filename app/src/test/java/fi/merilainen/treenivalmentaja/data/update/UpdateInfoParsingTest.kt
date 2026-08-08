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
 */
class UpdateInfoParsingTest {

    /** Copied verbatim from the published release asset, not hand-written to fit the parser. */
    private val publishedPayload = """
        {
          "versionName": "1.0-9d9fe8f",
          "commit": "9d9fe8f",
          "builtAtUtc": "2026-08-08T12:32:27Z",
          "apkUrl": "https://github.com/merilainen-star/Treenivalmentaja2000/releases/download/test-build/Treenivalmentaja-test.apk",
          "apkSizeBytes": 19436901
        }
    """.trimIndent()

    @Test
    fun `the payload CI publishes parses`() {
        val info = HttpUpdateService.parseUpdateInfo(publishedPayload)

        assertEquals("1.0-9d9fe8f", info.versionName)
        assertEquals("9d9fe8f", info.commit)
        assertEquals("2026-08-08T12:32:27Z", info.builtAtUtc)
        assertEquals(19_436_901L, info.apkSizeBytes)
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

    @Test
    fun `text that is not json is rejected`() {
        val result = runCatching { HttpUpdateService.parseUpdateInfo("<html>404</html>") }

        assertTrue("expected the parse to fail", result.isFailure)
    }
}
