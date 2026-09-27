package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.merilainen.treenivalmentaja.domain.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun RunProfileCard(
  steps: List<RunStep>, modifier: Modifier = Modifier, title: String = "Suunnitellut juoksuvaiheet",
  recording: RunTrace? = null, completed: Boolean = false, showPrescription: Boolean = true,
  laps: List<RunLap> = emptyList(),
) {
  val recorded = remember(steps, recording) { recording?.let { recordedTimeline(it, steps) } }
  if (steps.isEmpty() && recorded == null) return
  val plot = recorded ?: remember(steps) { plannedTimeline(steps) }
  var selected by rememberSaveable(steps, plot.recorded) { mutableIntStateOf(-1) }
  var showStages by rememberSaveable { mutableStateOf(true) }
  var showHr by rememberSaveable { mutableStateOf(true) }
  var showPace by rememberSaveable { mutableStateOf(true) }
  val scheme = MaterialTheme.colorScheme
  val paceColor = if (scheme.surface.luminance() < .3f) Color(0xFFFFC857) else Color(0xFF925500)
  val measured = plot.heartRate.mapNotNull { it.bpm }
  val hasHr = measured.isNotEmpty()
  val paces = remember(plot) { plot.speed.mapNotNull { it.paceSecPerKm } }
  val hasPace = paces.isNotEmpty()
  val drawHr = hasHr && showHr
  val drawPace = hasPace && showPace
  val fastPace = floor((paces.minOrNull() ?: 180.0) / 60) * 60
  val slowPace = maxOf(fastPace + 120, ceil((paces.maxOrNull() ?: 600.0) / 60) * 60)
  val low = floor(((measured.minOrNull() ?: 80) - 10) / 20.0).toInt() * 20
  val high = maxOf(low + 40, ceil(((measured.maxOrNull() ?: 180) + 10) / 20.0).toInt() * 20)
  val segments = remember(plot) { plot.heartRate.heartRateSegments() }
  val paceSegments = remember(plot) { plot.speed.paceSegments() }
  val axisLabel = when {
    plot.recorded -> "Kellon aikajana · sisältää tauot"
    plot.axis == RunAxis.DISTANCE -> "Leveys = matka"
    plot.axis == RunAxis.ORDER -> "Vaihejärjestys · osasta puuttuu kesto tai tavoitevauhti"
    plot.estimated -> "Leveys = arvioitu kesto · matkaosuudet tavoitevauhdista"
    else -> "Leveys = kesto"
  }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (completed) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(selected = showStages, onClick = { showStages = !showStages }, label = { Text("Vaiheet") })
      FilterChip(selected = showHr, onClick = { showHr = !showHr }, label = { Text("Syke") })
      FilterChip(selected = showPace, onClick = { showPace = !showPace }, label = { Text("Vauhti") })
    }
    if (drawHr || drawPace) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(if (drawHr) "Syke /min" else "", style = MaterialTheme.typography.labelSmall, color = scheme.tertiary)
      if (drawPace) Text("Vauhti min/km", style = MaterialTheme.typography.labelSmall, color = paceColor)
    }
    Canvas(Modifier.fillMaxWidth().height(if (completed) 220.dp else 116.dp).semantics {
      contentDescription = "$axisLabel. ${plot.stages.size} vaihetta." +
        (if (showStages) " Vaihepalkit näkyvissä." else "") +
        (if (drawHr) " Mitattu syke ${measured.minOrNull()}–${measured.maxOrNull()} lyöntiä minuutissa." else "") +
        (if (drawPace) " Mitattu vauhtikäyrä näkyvissä." else "")
    }.pointerInput(plot, drawHr, drawPace) {
      detectTapGestures {
        val left = if (drawHr) 32.dp.toPx() else 0f
        val right = if (drawPace) 40.dp.toPx() else 0f
        selected = plot.stageAt((it.x - left) / (size.width - left - right))
      }
    }) {
      val left = if (drawHr) 32.dp.toPx() else 0f
      val right = size.width - if (drawPace) 40.dp.toPx() else 0f
      val top = 12.dp.toPx()
      val bottom = size.height - 23.dp.toPx()
      val width = right - left
      val height = bottom - top
      fun x(value: Double) = left + (value / plot.extent).toFloat() * width
      fun y(bpm: Int) = bottom - (bpm - low).toFloat() / (high - low) * height
      fun paceY(pace: Double) = top + ((pace - fastPace) / (slowPace - fastPace)).toFloat() * height
      val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = scheme.onSurfaceVariant.toArgb(); textSize = 10.sp.toPx()
      }
      if (drawHr) for (value in listOf(low, (low + high) / 2, high)) {
        drawLine(scheme.outlineVariant, Offset(left, y(value)), Offset(right, y(value)), 1.dp.toPx())
        drawContext.canvas.nativeCanvas.drawText(value.toString(), 0f, y(value) + 3.dp.toPx(), paint)
      }
      if (drawPace) {
        paint.color = paceColor.toArgb()
        for (value in listOf(fastPace, (fastPace + slowPace) / 2, slowPace)) {
          if (!drawHr) drawLine(scheme.outlineVariant, Offset(left, paceY(value)), Offset(right, paceY(value)), 1.dp.toPx())
          drawContext.canvas.nativeCanvas.drawText(value.roundToInt().paceText(), right + 4.dp.toPx(), paceY(value) + 3.dp.toPx(), paint)
        }
        paint.color = scheme.onSurfaceVariant.toArgb()
      }
      plot.stages.forEachIndexed { index, stage ->
        val fraction = when (stage.phase) {
          RunPhase.SPRINT, RunPhase.STRIDE -> .85f
          RunPhase.WORK -> .68f
          RunPhase.EASY -> .36f
          RunPhase.RECOVERY -> .22f
          RunPhase.UNKNOWN -> .45f
        }
        val color = when (stage.phase) {
          RunPhase.SPRINT, RunPhase.STRIDE -> scheme.tertiary
          RunPhase.WORK -> scheme.primary
          RunPhase.EASY -> scheme.secondary
          else -> scheme.outline
        }
        // No gap or minimum width that could exaggerate a short acceleration.
        val barHeight = height * fraction
        if (showStages) {
          drawRect(color.copy(alpha = if (completed) .38f else .85f), Offset(x(stage.start), bottom - barHeight),
            Size(x(stage.end) - x(stage.start), barHeight))
          drawLine(color, Offset(x(stage.start), bottom - barHeight), Offset(x(stage.end), bottom - barHeight), 1.dp.toPx())
        }
        if (selected == index) drawRect(scheme.onSurface, Offset(x(stage.start), top),
          Size(x(stage.end) - x(stage.start), height), style = Stroke(1.5.dp.toPx()))
      }
      if (drawHr) for (segment in segments) {
        val path = Path()
        segment.forEachIndexed { index, point ->
          if (index == 0) path.moveTo(x(point.second.toDouble()), y(point.bpm!!))
          else path.lineTo(x(point.second.toDouble()), y(point.bpm!!))
        }
        if (segment.size == 1) drawCircle(scheme.tertiary, 2.dp.toPx(), Offset(x(segment[0].second.toDouble()), y(segment[0].bpm!!)))
        else {
          drawPath(path, scheme.surface, style = Stroke(4.dp.toPx()))
          drawPath(path, scheme.tertiary, style = Stroke(2.dp.toPx()))
        }
      }
      if (drawPace) for (segment in paceSegments) {
        val path = Path()
        segment.forEachIndexed { index, point ->
          val pointY = paceY(requireNotNull(point.paceSecPerKm))
          if (index == 0) path.moveTo(x(point.second.toDouble()), pointY) else path.lineTo(x(point.second.toDouble()), pointY)
        }
        if (segment.size == 1) drawCircle(paceColor, 2.dp.toPx(), Offset(x(segment[0].second.toDouble()), paceY(requireNotNull(segment[0].paceSecPerKm))))
        else {
          drawPath(path, scheme.surface, style = Stroke(4.dp.toPx()))
          drawPath(path, paceColor, style = Stroke(2.dp.toPx()))
        }
      }
      for (tick in 0..4) {
        val value = plot.extent * tick / 4
        val text = when (plot.axis) {
          RunAxis.TIME -> "${value.toInt() / 60}:${(value.toInt() % 60).toString().padStart(2, '0')}"
          RunAxis.DISTANCE -> "${value.toInt()} m"
          RunAxis.ORDER -> if (tick == 0) "Alku" else if (tick == 4) "Loppu" else ""
        }
        val at = (x(value) - paint.measureText(text) / 2).coerceIn(left, (right - paint.measureText(text)).coerceAtLeast(left))
        drawContext.canvas.nativeCanvas.drawText(text, at, size.height - 3.dp.toPx(), paint)
      }
    }
    Text(axisLabel, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    if (completed && showHr && !hasHr) Text("Sykekäyrää ei ole saatavilla.", style = MaterialTheme.typography.bodySmall)
    if (completed && showPace && !hasPace) Text("Vauhtikäyrää ei ole saatavilla.", style = MaterialTheme.typography.bodySmall)
    if (plot.recorded && !plot.paired) Text(if (plot.stages.isEmpty()) "Kellon vaiherajat puuttuvat."
      else "Kellon kierroksia ei ole kohdistettu suunnitelman vaiheisiin.", style = MaterialTheme.typography.bodySmall)
    if (completed && !showStages && !showHr && !showPace) Text("Valitse näytettävä data.", style = MaterialTheme.typography.bodySmall)
    if (showPrescription) Text(steps.compactPrescription().joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
    val stage = plot.stages.getOrNull(selected)
    if (stage != null) {
      Text("${selected + 1}/${plot.stages.size} · ${stage.label}", style = MaterialTheme.typography.bodyMedium)
      if (plot.recorded) {
        val values = plot.heartRate.filter { it.second >= stage.start && it.second < stage.end }.mapNotNull { it.bpm }
        Text("Kesto ${(stage.end - stage.start).toInt()} s" + if (values.isNotEmpty()) " · syke ${values.min()}–${values.max()} /min" else " · ei sykenäytteitä",
          style = MaterialTheme.typography.bodySmall)
        val average = stage.averagePace(laps)
        Text(average?.let { "Toteutunut keskivauhti ${it.paceText()} /km" } ?: "Osuuden keskivauhti ei saatavilla",
          style = MaterialTheme.typography.bodyMedium, color = paceColor)
      }
      Row {
        TextButton(onClick = { selected-- }, enabled = selected > 0) { Text("Edellinen vaihe") }
        TextButton(onClick = { selected++ }, enabled = selected < plot.stages.lastIndex) { Text("Seuraava vaihe") }
      }
    } else if (plot.stages.isNotEmpty()) TextButton(onClick = { selected = 0 }) { Text("Tutki vaiheita") }
  }
}

private fun Int.paceText(): String = "${this / 60}:${(this % 60).toString().padStart(2, '0')}"
