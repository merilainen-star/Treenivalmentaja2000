package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.update.UpdateInfo
import fi.merilainen.treenivalmentaja.data.update.UpdateService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckForUpdateUseCaseTest {

    private fun published(versionName: String, sizeBytes: Long = 19_420_493) =
        UpdateInfo(
            versionName = versionName,
            commit = versionName.substringAfter('-'),
            builtAtUtc = "2026-08-08T09:00:00Z",
            apkUrl = "https://example.invalid/Treenivalmentaja-test.apk",
            apkSizeBytes = sizeBytes,
        )

    private fun service(info: UpdateInfo) = object : UpdateService {
        override suspend fun fetchLatest() = info
    }

    private fun failing(message: String) = object : UpdateService {
        override suspend fun fetchLatest(): UpdateInfo = error(message)
    }

    /** A build the network never has to be consulted about. */
    @Test
    fun `a version name without a commit is a local build`() = runTest {
        val status = CheckForUpdateUseCase(
            service = failing("should not be called"),
            installedVersionName = "1.0",
        ).execute()

        assertEquals(UpdateStatus.LocalBuild, status)
    }

    @Test
    fun `the same version name is up to date`() = runTest {
        val status = CheckForUpdateUseCase(
            service = service(published("1.0-c07cfac")),
            installedVersionName = "1.0-c07cfac",
        ).execute()

        assertEquals(UpdateStatus.UpToDate("1.0-c07cfac"), status)
    }

    /**
     * Commit hashes cannot be ordered, so "different from what is published" is the only signal
     * available — and the right one, since the published build is the current test build.
     */
    @Test
    fun `a different version name offers the published build`() = runTest {
        val status = CheckForUpdateUseCase(
            service = service(published("1.0-a1b2c3d")),
            installedVersionName = "1.0-c07cfac",
        ).execute()

        val available = status as UpdateStatus.Available
        assertEquals("1.0-a1b2c3d", available.versionName)
        assertEquals("https://example.invalid/Treenivalmentaja-test.apk", available.apkUrl)
    }

    @Test
    fun `the size is rounded to the nearest megabyte`() = runTest {
        val status = CheckForUpdateUseCase(
            service = service(published("1.0-a1b2c3d", sizeBytes = 19_420_493)),
            installedVersionName = "1.0-c07cfac",
        ).execute()

        assertEquals(19, (status as UpdateStatus.Available).sizeMb)
    }

    /** A failed check must never be reported as "up to date". */
    @Test
    fun `a network failure is reported with its reason`() = runTest {
        val status = CheckForUpdateUseCase(
            service = failing("GitHub vastasi HTTP 503"),
            installedVersionName = "1.0-c07cfac",
        ).execute()

        val failed = status as UpdateStatus.Failed
        assertTrue(failed.reason.contains("503"))
    }
}
