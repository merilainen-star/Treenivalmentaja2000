package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.UpdateStatus

/**
 * Says whether the installed build is the one GitHub Actions last published, and installs it when
 * it is not.
 *
 * **The APK is never handed to a browser and never lands in the Downloads folder.** It is streamed
 * into an Android install session, checked against the size and SHA-256 the release published, and
 * committed — see `data/update/PackageInstallerApkInstaller.kt`. This card used to open the
 * download URL with `ACTION_VIEW`, which left an installable file among the user's documents and
 * made the browser part of the update path; the digest check is the thing that route could not
 * have at all.
 *
 * Android still asks. `USER_ACTION_REQUIRED` is set, so the ordinary "Päivitetäänkö tämä
 * sovellus?" dialog appears exactly as before, and the signing certificate still decides what may
 * replace this app: a package signed by a different key cannot be installed over it.
 *
 * The permission and the settings trip live in `SettingsScreen`, which is the one place with an
 * activity to launch them from. This card only reports and asks.
 */
@Composable
fun UpdateCard(
    status: UpdateStatus,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
    onDownload: () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status is UpdateStatus.Available) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Sovelluksen versio",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            when (status) {
                UpdateStatus.Idle, UpdateStatus.Checking -> {
                    Text(
                        text = "Tarkistetaan…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }

                is UpdateStatus.UpToDate -> Text(
                    text = "Ajan tasalla (${status.versionName}).",
                    style = MaterialTheme.typography.bodyMedium
                )

                is UpdateStatus.Available -> {
                    Text(
                        text = "Päivitys saatavilla: ${status.versionName} (${status.sizeMb} MB).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Päivitys ladataan sovelluksen sisällä ja tarkistetaan ennen " +
                            "asennusta — Android kysyy vielä luvan. Treenit ja asetukset säilyvät.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Lataa ja asenna päivitys")
                    }
                }

                is UpdateStatus.Downloading -> {
                    Text(
                        text = "Ladataan päivitystä ${status.versionName}… " +
                            "${status.progressPercent} %",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { status.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                is UpdateStatus.AwaitingInstallConfirmation -> {
                    Text(
                        text = "Päivitys on ladattu ja tarkistettu. Odotetaan Androidin " +
                            "asennusvahvistusta…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }

                UpdateStatus.LocalBuild -> Text(
                    text = "Paikallinen build (${BuildConfig.VERSION_NAME}). " +
                        "Päivityksiä verrataan vain GitHubista asennettuihin versioihin.",
                    style = MaterialTheme.typography.bodyMedium
                )

                is UpdateStatus.Failed -> {
                    Text(
                        // A failed install already says what happened in its own words; a failed
                        // check needs the sentence that says what was being attempted.
                        text = if (status.retryable != null) status.reason
                            else "Version tarkistus ei onnistunut: ${status.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    // A refused or cancelled install still knows which release it was for, so the
                    // download button comes straight back rather than sending the user round the
                    // check again.
                    if (status.retryable != null) {
                        Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                            Text("Lataa ja asenna päivitys")
                        }
                    } else {
                        TextButton(onClick = onCheck) { Text("Yritä uudelleen") }
                    }
                }
            }
        }
    }
}
