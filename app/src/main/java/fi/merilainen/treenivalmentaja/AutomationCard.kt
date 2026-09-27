package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.data.settings.AutomationSettings

@Composable
fun AutomationCard(settings: AutomationSettings, onChange: (AutomationSettings) -> Unit, exportMessage: String?) {
  Card {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Treenien automatiikka", style = MaterialTheme.typography.titleLarge)
      AutomationSwitch("Merkitse tunnistettu Suunto-lenkki suoritetuksi", settings.completeRuns) { onChange(settings.copy(completeRuns = it)) }
      Text("Vain yksiselitteinen osuma. Merkinnän voi perua treenin kortista.", style = MaterialTheme.typography.bodySmall)
      AutomationSwitch("Automaattinen AI-analyysi", settings.analyseRuns) { onChange(settings.copy(analyseRuns = it)) }
      Text("Lähettää tehdyn juoksun ja palautumistiedot valitsemallesi AI-palvelulle tallennetulla avaimella. Palvelu voi veloittaa käytöstä. Ilmoitus avaa valmiin analyysin.", style = MaterialTheme.typography.bodySmall)
      AutomationSwitch("Vie seuraavat 14 päivää automaattisesti", settings.exportRuns) { onChange(settings.copy(exportRuns = it)) }
      Text("Päivittää tämän sovelluksen juoksut Intervals.icu:hun myös ohjelman muuttuessa. Suunto-sovelluksen ja kellon synkronoinnin täytyy toimia erikseen.", style = MaterialTheme.typography.bodySmall)
      exportMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
  }
}

@Composable
private fun AutomationSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f))
    Switch(checked, onCheckedChange = onChange)
  }
}
