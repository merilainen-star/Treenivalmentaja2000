package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.update.UpdateService

/** What Settings should say about the installed build. */
sealed interface UpdateStatus {
  data object Idle : UpdateStatus

  data object Checking : UpdateStatus

  data class UpToDate(val versionName: String) : UpdateStatus

  /**
   * A published build that is not the installed one, and everything needed to install it.
   *
   * [sizeBytes] and [sha256] travel with it rather than being fetched again at the moment of
   * download: they are what the download is checked against, so re-reading them from the network
   * after the user has pressed the button would check the file against whatever is published then,
   * not against the release the user was shown.
   */
  data class Available(
    val versionName: String,
    val apkUrl: String,
    val sizeMb: Int,
    val sizeBytes: Long,
    val sha256: String,
  ) : UpdateStatus

  /** The APK is being streamed into an install session. [progressPercent] is 0..100. */
  data class Downloading(val versionName: String, val progressPercent: Int) : UpdateStatus

  /** Downloaded, verified and committed; Android is asking the user whether to install it. */
  data class AwaitingInstallConfirmation(val versionName: String) : UpdateStatus

  /**
   * The installed build came from a PC, not from a release, so there is nothing meaningful to
   * compare against — a locally built APK carries no commit in its version name.
   */
  data object LocalBuild : UpdateStatus

  /**
   * Something went wrong, and — when the update itself is still there to try again — what to
   * retry with.
   *
   * [retryable] is what puts the "Lataa ja asenna päivitys" button back after a cancelled or
   * refused install. It is null when the *check* failed, because then there is no known update to
   * download and the only honest offer is to look again.
   */
  data class Failed(val reason: String, val retryable: Available? = null) : UpdateStatus
}

/**
 * Compares the installed build against the one published by GitHub Actions.
 *
 * Versions are compared for equality, not order: the published build is a rolling test build
 * identified by a commit hash, and commit hashes cannot be ranked. "Different from what is
 * published" is therefore the only honest signal, and it is the right one — the published build
 * is by definition the current test build.
 */
class CheckForUpdateUseCase(
  private val service: UpdateService,
  private val installedVersionName: String,
) {

  suspend fun execute(): UpdateStatus {
    if (!installedVersionName.contains('-')) return UpdateStatus.LocalBuild

    val info =
      runCatching { service.fetchLatest() }
        .getOrElse { error ->
          return UpdateStatus.Failed(error.message ?: "tuntematon virhe")
        }

    return if (info.versionName == installedVersionName) {
      UpdateStatus.UpToDate(installedVersionName)
    } else {
      UpdateStatus.Available(
        versionName = info.versionName,
        apkUrl = info.apkUrl,
        // Rounded for display only; the exact byte count is not useful to a reader.
        sizeMb = ((info.apkSizeBytes + 524_288) / 1_048_576).toInt(),
        sizeBytes = info.apkSizeBytes,
        sha256 = info.apkSha256,
      )
    }
  }
}
