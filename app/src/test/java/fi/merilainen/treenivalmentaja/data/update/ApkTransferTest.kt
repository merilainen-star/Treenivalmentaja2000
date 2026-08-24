package fi.merilainen.treenivalmentaja.data.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The integrity check, exercised without a `PackageInstaller`.
 *
 * This is the one step in the update that can silently do the wrong thing: everything else either
 * works or throws, while a download that is short, padded or simply not the published file looks
 * exactly like a successful one until Android refuses it — or does not.
 */
class ApkTransferTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Larger than the 64 KB copy buffer, so the loop runs more than once. */
    private val apk = ByteArray(200_000) { (it % 251).toByte() }

    @Test
    fun `a file that matches the release is accepted, byte for byte`() {
        val sink = ByteArrayOutputStream()

        val result = ApkTransfer.copyVerifying(
            source = ByteArrayInputStream(apk),
            sink = sink,
            expectedSizeBytes = apk.size.toLong(),
            expectedSha256 = sha256(apk),
        )

        assertEquals(TransferResult.Verified, result)
        assertTrue(apk.contentEquals(sink.toByteArray()))
    }

    /** Upper-case hex is the same digest. CI's `sha256sum` writes lower case; a mirror may not. */
    @Test
    fun `the digest comparison ignores case`() {
        val result = ApkTransfer.copyVerifying(
            source = ByteArrayInputStream(apk),
            sink = ByteArrayOutputStream(),
            expectedSizeBytes = apk.size.toLong(),
            expectedSha256 = sha256(apk).uppercase(),
        )

        assertEquals(TransferResult.Verified, result)
    }

    @Test
    fun `a truncated download is rejected`() {
        val truncated = apk.copyOf(apk.size - 1_000)

        val result = ApkTransfer.copyVerifying(
            source = ByteArrayInputStream(truncated),
            sink = ByteArrayOutputStream(),
            expectedSizeBytes = apk.size.toLong(),
            expectedSha256 = sha256(apk),
        )

        val rejected = result as TransferResult.Rejected
        assertTrue(rejected.reason, rejected.reason.contains("koko"))
    }

    /** The right length, the wrong bytes — the case only the digest can catch. */
    @Test
    fun `a file of the right size with different content is rejected`() {
        val substitute = apk.copyOf().also { it[12_345] = (it[12_345] + 1).toByte() }

        val result = ApkTransfer.copyVerifying(
            source = ByteArrayInputStream(substitute),
            sink = ByteArrayOutputStream(),
            expectedSizeBytes = apk.size.toLong(),
            expectedSha256 = sha256(apk),
        )

        val rejected = result as TransferResult.Rejected
        assertTrue(rejected.reason, rejected.reason.contains("tarkistussumma"))
    }

    /** A stream that never ends must not be allowed to fill the device before being refused. */
    @Test
    fun `a stream longer than the release is stopped rather than written out`() {
        val sink = ByteArrayOutputStream()

        val result = ApkTransfer.copyVerifying(
            source = ByteArrayInputStream(ByteArray(1_000_000)),
            sink = sink,
            expectedSizeBytes = apk.size.toLong(),
            expectedSha256 = sha256(apk),
        )

        assertTrue(result is TransferResult.Rejected)
        // One buffer's worth of overshoot at most, not the whole stream.
        assertTrue(
            "wrote ${sink.size()} bytes",
            sink.size() < apk.size + 100_000,
        )
    }

    /** Nothing to verify against, and the progress arithmetic would divide by it. */
    @Test
    fun `a release that declares no size is rejected before anything is read`() {
        val sink = ByteArrayOutputStream()

        val result = ApkTransfer.copyVerifying(
            source = ByteArrayInputStream(apk),
            sink = sink,
            expectedSizeBytes = 0,
            expectedSha256 = sha256(apk),
        )

        assertTrue(result is TransferResult.Rejected)
        assertEquals(0, sink.size())
    }

    @Test
    fun `progress is reported from the first buffer to a hundred`() {
        val reported = mutableListOf<Int>()

        ApkTransfer.copyVerifying(
            source = ByteArrayInputStream(apk),
            sink = ByteArrayOutputStream(),
            expectedSizeBytes = apk.size.toLong(),
            expectedSha256 = sha256(apk),
            onProgress = { reported += it },
        )

        assertEquals(100, reported.last())
        assertTrue("expected progress before the end: $reported", reported.size > 1)
        assertEquals("progress must not go backwards", reported.sorted(), reported)
    }
}
