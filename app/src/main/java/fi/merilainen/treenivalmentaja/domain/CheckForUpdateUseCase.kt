package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.update.UpdateService

/** What Settings should say about the installed build. */
sealed interface UpdateStatus {
  data object Idle : UpdateStatus

  data object Checking : UpdateStatus

  data class UpToDate(val versionName: String) : UpdateStatus

  data class Available(val versionName: String, val apkUrl: String, val sizeMb: Int) : UpdateStatus

  /**
   * The installed build came from a PC, not from a release, so there is nothing meaningful to
   * compare against — a locally built APK carries no commit in its version name.
   */
  data object LocalBuild : UpdateStatus

  data class Failed(val reason: String) : UpdateStatus
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
      )
    }
  }
}
