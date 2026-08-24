package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.update.ApkInstaller
import fi.merilainen.treenivalmentaja.data.update.InstallProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallUpdateUseCaseTest {

    private val update = UpdateStatus.Available(
        versionName = "1.0-a1b2c3d",
        apkUrl = "https://example.invalid/Treenivalmentaja-test.apk",
        sizeMb = 19,
        sizeBytes = 19_420_493,
        sha256 = "9f2ec1b0f4a58a1e2a6d4b8c0e5f7a9b1c3d5e7f9a1b3c5d7e9f1a3b5c7d9e1f",
    )

    private fun installer(
        vararg progress: InstallProgress,
        canInstall: Boolean = true,
        record: MutableList<Triple<String, Long, String>> = mutableListOf(),
    ) = object : ApkInstaller {
        override fun canRequestInstall() = canInstall

        override fun downloadAndInstall(
            apkUrl: String,
            sizeBytes: Long,
            sha256: String,
        ): Flow<InstallProgress> {
            record += Triple(apkUrl, sizeBytes, sha256)
            return flowOf(*progress)
        }
    }

    /**
     * The installer is told what to verify against by the offer the user was shown, not by a fresh
     * look at the release.
     */
    @Test
    fun `the published size and digest are what the download is checked against`() = runTest {
        val asked = mutableListOf<Triple<String, Long, String>>()

        InstallUpdateUseCase(installer(InstallProgress.AwaitingConfirmation, record = asked))
            .execute(update)
            .toList()

        assertEquals(listOf(Triple(update.apkUrl, update.sizeBytes, update.sha256)), asked)
    }

    @Test
    fun `download progress is reported with the version being installed`() = runTest {
        val statuses = InstallUpdateUseCase(
            installer(
                InstallProgress.Downloading(0),
                InstallProgress.Downloading(64),
                InstallProgress.AwaitingConfirmation,
            )
        ).execute(update).toList()

        assertEquals(
            listOf(
                UpdateStatus.Downloading("1.0-a1b2c3d", 0),
                UpdateStatus.Downloading("1.0-a1b2c3d", 64),
                UpdateStatus.AwaitingInstallConfirmation("1.0-a1b2c3d"),
            ),
            statuses,
        )
    }

    /**
     * A cancelled install must leave the button where it was. Without the update travelling with
     * the failure, the card could only offer "check again" — an extra round trip to arrive back at
     * the release it already had.
     */
    @Test
    fun `a cancelled install offers the same release again`() = runTest {
        val statuses = InstallUpdateUseCase(
            installer(
                InstallProgress.AwaitingConfirmation,
                InstallProgress.Failed("Asennus peruttiin."),
            )
        ).execute(update).toList()

        val failed = statuses.last() as UpdateStatus.Failed
        assertEquals("Asennus peruttiin.", failed.reason)
        assertEquals(update, failed.retryable)
    }

    /** Rarely reached: installing over this app usually kills the process that is watching. */
    @Test
    fun `a completed install reads as up to date`() = runTest {
        val statuses = InstallUpdateUseCase(installer(InstallProgress.Succeeded))
            .execute(update)
            .toList()

        assertEquals(UpdateStatus.UpToDate("1.0-a1b2c3d"), statuses.last())
    }

    @Test
    fun `the permission question is answered by the installer`() {
        assertTrue(InstallUpdateUseCase(installer(canInstall = true)).canInstall())
    }
}
