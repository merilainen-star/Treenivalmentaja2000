package fi.merilainen.treenivalmentaja.domain

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.LocalDate
import java.time.LocalTime

sealed interface AdvisorOperation {
  val sessionId: String

  data class Move(
    override val sessionId: String,
    val newDate: LocalDate,
    val newTime: LocalTime? = null,
  ) : AdvisorOperation

  data class Lighten(override val sessionId: String) : AdvisorOperation
}

data class AiPlanProposal(val summary: String, val operations: List<AdvisorOperation>)

sealed interface AdvisorResponse {
  data class Clarification(val question: String) : AdvisorResponse
  data class Proposal(val value: AiPlanProposal) : AdvisorResponse

  /**
   * The advisor looked and has nothing to change. This is a successful answer, not a failure —
   * on most days a plan that is being followed needs no operation at all, and an advisor that
   * cannot say so is pushed into inventing one.
   */
  data class NoChange(val reason: String) : AdvisorResponse
}

sealed interface AiPlanProposalState {
  data object Loading : AiPlanProposalState
  data class NeedsClarification(val question: String, val prompt: String) : AiPlanProposalState
  data class Ready(val proposal: AiPlanProposal, val prompt: String) : AiPlanProposalState
  data class NoChange(val reason: String, val prompt: String) : AiPlanProposalState
  data class Applying(val proposal: AiPlanProposal) : AiPlanProposalState
  data class Applied(val summary: String) : AiPlanProposalState
  /** [rawResponse] is what the model actually returned, so a parse failure can be diagnosed. */
  data class Failed(
    val message: String,
    val canRetry: Boolean,
    val rawResponse: String? = null,
  ) : AiPlanProposalState
}

class AdvisorResponseParser {
  private val adapter =
    Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(WireResponse::class.java)

  fun parse(raw: String): Result<AdvisorResponse> = runCatching {
    val json = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)
    val wire = adapter.fromJson(json) ?: error("AI-vastaus oli tyhjä")
    when (wire.status?.lowercase()) {
      "clarification" ->
        AdvisorResponse.Clarification(
          wire.question?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Tarkentavasta vastauksesta puuttui kysymys")
        )
      "no_change", "nochange", "no change" ->
        AdvisorResponse.NoChange(
          wire.summary?.trim()?.takeIf { it.isNotEmpty() }
            ?: wire.question?.trim()?.takeIf { it.isNotEmpty() }
            ?: "AI ei ehdota muutoksia."
        )
      "proposal" -> {
        val operations = wire.operations.orEmpty().map { it.toDomain() }
        // An empty list is the model saying "nothing to do" in the only vocabulary the older
        // prompt gave it. Reading that as a malformed answer turned the commonest correct
        // outcome into a red error, so it is answered rather than rejected.
        if (operations.isEmpty()) {
          return@runCatching AdvisorResponse.NoChange(
            wire.summary?.trim()?.takeIf { it.isNotEmpty() } ?: "AI ei ehdota muutoksia."
          )
        }
        require(operations.size <= 5) { "Yhdessä ehdotuksessa voi olla enintään viisi muutosta" }
        require(operations.map { it.sessionId }.distinct().size == operations.size) {
          "Samaa harjoitusta ei voi muuttaa kahdesti samassa ehdotuksessa"
        }
        AdvisorResponse.Proposal(
          AiPlanProposal(
            summary = wire.summary?.trim()?.takeIf { it.isNotEmpty() } ?: "AI:n muutosehdotus",
            operations = operations,
          )
        )
      }
      else -> error("AI-vastauksen status ei ollut clarification, proposal tai no_change")
    }
  }

  private fun WireOperation.toDomain(): AdvisorOperation {
    val id = sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: error("Operaatiosta puuttui sessionId")
    return when (type?.uppercase()) {
      "MOVE" ->
        AdvisorOperation.Move(
          sessionId = id,
          newDate = LocalDate.parse(newDate ?: error("MOVE-operaatiosta puuttui newDate")),
          newTime = newTime?.let(LocalTime::parse),
        )
      "LIGHTEN" -> AdvisorOperation.Lighten(id)
      else -> error("Tuntematon AI-operaatio: $type")
    }
  }

  private data class WireResponse(
    val status: String? = null,
    val question: String? = null,
    val summary: String? = null,
    val operations: List<WireOperation>? = null,
  )

  private data class WireOperation(
    val type: String? = null,
    val sessionId: String? = null,
    val newDate: String? = null,
    val newTime: String? = null,
  )
}

class AdvisorPromptBuilder {
  fun build(
    target: TrainingSession,
    sessions: List<TrainingSession>,
    today: LocalDate,
    constraints: String,
    healthContext: String = "ei saatavilla",
    clarificationQuestion: String? = null,
    clarificationAnswer: String? = null,
  ): String = buildString {
    appendLine("Toimit harjoitussuunnitelman neuvojana. Tänään on $today.")
    appendLine("Kohdeharjoitus: ${target.id}, ${target.scheduledDate}, ${target.type.name}.")
    appendLine("Käyttäjän pysyvät rajoitteet: ${constraints.ifBlank { "ei annettu" }}")
    appendLine("Palautumis- ja kuormituskonteksti (puuttuvia arvoja ei saa päätellä):")
    appendLine(healthContext.ifBlank { "ei saatavilla" })
    clarificationQuestion?.takeIf { it.isNotBlank() }?.let { appendLine("Aiempi tarkentava kysymys: $it") }
    clarificationAnswer?.takeIf { it.isNotBlank() }?.let { appendLine("Käyttäjän vastaus: $it") }
    appendLine("Avoimet harjoitukset, joita saat ehdottaa muutettaviksi:")
    sessions.filter { it.status.isOpen }.sortedBy { it.scheduledDate }.forEach { session ->
      appendLine(
        "- id=${session.id}; päivä=${session.scheduledDate}; aika=${session.scheduledTime ?: "ei aikaa"}; " +
          "laji=${session.type.name}; intensiteetti=${session.intensity?.name ?: "ei annettu"}; " +
          "kesto=${session.durationMin ?: "ei annettu"} min"
      )
    }
    appendLine()
    appendLine("Jos päätökseen tarvitaan käyttäjän tieto, kysy yksi tarkentava kysymys:")
    appendLine("{\"status\":\"clarification\",\"question\":\"...\"}")
    appendLine("Jos suunnitelma on kunnossa eikä muutosta tarvita, vastaa näin — tämä on")
    appendLine("täysin hyväksyttävä vastaus, älä keksi muutosta vain vastataksesi jotain:")
    appendLine("{\"status\":\"no_change\",\"summary\":\"...\"}")
    appendLine("Jos ehdotat muutosta, vastaa vain tällä JSON-rakenteella, ei markdownia:")
    appendLine(
      "{\"status\":\"proposal\",\"summary\":\"...\",\"operations\":[" +
        "{\"type\":\"MOVE\",\"sessionId\":\"...\",\"newDate\":\"YYYY-MM-DD\",\"newTime\":\"HH:mm\"}," +
        "{\"type\":\"LIGHTEN\",\"sessionId\":\"...\"}]}"
    )
    appendLine("Sallitut operaatiot ovat vain MOVE ja LIGHTEN. Älä ehdota mennyttä päivää.")
    appendLine("Mitään muutosta ei tehdä ilman käyttäjän erillistä hyväksyntää.")
  }
}
