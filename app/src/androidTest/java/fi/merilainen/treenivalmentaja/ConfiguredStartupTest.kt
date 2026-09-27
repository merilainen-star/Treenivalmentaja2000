package fi.merilainen.treenivalmentaja

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.merilainen.treenivalmentaja.data.settings.AutomationSettings
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ConfiguredStartupTest {
  @get:Rule val compose = createEmptyComposeRule()

  @Test fun savedProgramAndConfiguredConnectionsSurviveStartupAndRecreation() {
    val app = ApplicationProvider.getApplicationContext<TreenivalmentajaApplication>()
    val today = LocalDate.now()
    val supplied = InstrumentationRegistry.getArguments().getString("startupPlan")
    val raw = supplied?.let { File(app.getExternalFilesDir(null), it).readText() } ?: """
      {"schemaVersion":1,"plan":{"id":"startup","name":"Startup","timeZone":"Europe/Helsinki","startDate":"$today"},
       "weeks":[{"weekNumber":1,"sessions":[{"id":"run","type":"RUNNING","date":"$today","distanceKm":7,
       "runSteps":[{"name":"Kevyt juoksu","distanceMeters":7000}]}]}]}
    """.trimIndent()
    runBlocking {
      // Fake credentials are persisted exactly as real keys; disabled automation prevents network.
      app.automationSettingsStore.set(AutomationSettings(false, false, false))
      app.db.clearAllTables()
      app.repository.importPlan(raw)
      app.intervalsConnection.saveApiKey("startup-test-invalid-key")
      app.analysisConnection.saveApiKey(AnalysisProvider.OPENAI, "startup-test-invalid-key")
    }
    try {
      ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Tänään").fetchSemanticsNodes().isNotEmpty() }
        scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Tänään").fetchSemanticsNodes().isNotEmpty() }
      }
    } finally {
      runBlocking {
        app.intervalsConnection.clearApiKey()
        app.analysisConnection.clearApiKey(AnalysisProvider.OPENAI)
      }
    }
  }
}
