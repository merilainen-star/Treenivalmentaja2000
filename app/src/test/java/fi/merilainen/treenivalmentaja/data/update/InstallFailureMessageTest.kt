package fi.merilainen.treenivalmentaja.data.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every `PackageInstaller` outcome the version card can be left showing.
 *
 * A test rather than a glance at the `when`, because the failure the user actually meets — pressing
 * "Peruuta" in Android's dialog — arrives as a status code and nothing else. If it fell through to
 * the generic branch, the card would say "Asennus epäonnistui" about a perfectly deliberate choice.
 */
class InstallFailureMessageTest {

    private fun message(status: Int, text: String? = null) =
        PackageInstallerApkInstaller.failureMessage(status, text)

    @Test
    fun `cancelling the install is reported as a cancellation`() {
        assertEquals("Asennus peruttiin.", message(PackageInstaller.STATUS_FAILURE_ABORTED))
    }

    @Test
    fun `every documented failure has an answer of its own`() {
        val statuses = listOf(
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_STORAGE,
            PackageInstaller.STATUS_FAILURE_INVALID,
        )

        val answers = statuses.map { message(it) }

        assertEquals("each status needs its own sentence", answers.size, answers.toSet().size)
        assertTrue(
            "none of them may fall through to the generic answer: $answers",
            answers.none { it == "Asennus epäonnistui." },
        )
    }

    /** A signing-key mismatch is the one failure with a fix, so the sentence has to name it. */
    @Test
    fun `a conflict explains the signing key`() {
        val text = message(PackageInstaller.STATUS_FAILURE_CONFLICT)

        assertTrue(text, text.contains("allekirjoitettu eri avaimella"))
    }

    @Test
    fun `an unknown status still says something`() {
        assertEquals("Asennus epäonnistui.", message(PackageInstaller.STATUS_FAILURE))
    }

    /** Android's own text names the verifier that refused; a generic sentence cannot. */
    @Test
    fun `the platform's own explanation is kept when there is one`() {
        val text = message(PackageInstaller.STATUS_FAILURE_BLOCKED, "Play Protect")

        assertTrue(text, text.endsWith("(Play Protect)"))
    }

    @Test
    fun `a blank explanation adds no empty brackets`() {
        assertTrue(message(PackageInstaller.STATUS_FAILURE_STORAGE, "  ").endsWith("päivitykselle."))
    }
}
