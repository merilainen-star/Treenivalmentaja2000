package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.update.ApkInstaller
import fi.merilainen.treenivalmentaja.data.update.InstallProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Turns the installer's progress into what the version card says.
 *
 * The mapping is the whole of it, and it is here rather than in the ViewModel so that "a cancelled
 * install offers the download again" is a rule with a test rather than a branch in a collector.
 */
class InstallUpdateUseCase(private val installer: ApkInstaller) {

  /** Whether Android will let this app ask to install a package at all. */
  fun canInstall(): Boolean = installer.canRequestInstall()

  fun execute(update: UpdateStatus.Available): Flow<UpdateStatus> =
    installer
      .downloadAndInstall(
        apkUrl = update.apkUrl,
        sizeBytes = update.sizeBytes,
        sha256 = update.sha256,
      )
      .map { progress ->
        when (progress) {
          is InstallProgress.Downloading ->
            UpdateStatus.Downloading(update.versionName, progress.percent)
          InstallProgress.AwaitingConfirmation ->
            UpdateStatus.AwaitingInstallConfirmation(update.versionName)
          // Seen only when the process survives its own replacement, which it usually does not.
          InstallProgress.Succeeded -> UpdateStatus.UpToDate(update.versionName)
          // The update is still published and still verified metadata, so the button comes back
          // pointing at the same release rather than making the user check again first.
          is InstallProgress.Failed -> UpdateStatus.Failed(progress.reason, retryable = update)
        }
      }
}
