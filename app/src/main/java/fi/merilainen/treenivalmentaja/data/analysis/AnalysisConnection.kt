package fi.merilainen.treenivalmentaja.data.analysis

import fi.merilainen.treenivalmentaja.domain.AnalysisModel
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider
import fi.merilainen.treenivalmentaja.data.security.CredentialSaveResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which providers have a key, and nothing else.
 *
 * **There is no `Verified` state, and that is the design decision worth recording.** The
 * intervals.icu connection tests a key the moment it is saved, because its test call is free and a
 * key pasted with a character missing would otherwise look accepted and then quietly fetch nothing.
 * Every call to any of these three costs the owner money. Spending it to validate a paste — at the
 * moment they are setting things up, not asking for anything — is the app deciding to bill someone
 * for reassurance they did not request.
 *
 * So the first real "AI-analyysi" tap is the test, a rejected key says so on that card, and this
 * class answers exactly one question per provider: is there a key.
 *
 * A set rather than three booleans because that is what the callers want — the button asks "is the
 * *selected* model's provider configured", and Settings asks it once per section.
 */
class AnalysisConnection internal constructor(
  private val stores: Map<AnalysisProvider, AnalysisApiKeyStorage>,
) {

  private val _configured = MutableStateFlow<Set<AnalysisProvider>>(emptySet())

  /** The providers with a key stored. Observed by Settings and by every workout card. */
  val configured: StateFlow<Set<AnalysisProvider>> = _configured.asStateFlow()
  private val _saveFailure = MutableStateFlow<AnalysisProvider?>(null)
  val saveFailure: StateFlow<AnalysisProvider?> = _saveFailure.asStateFlow()

  /** One operation at a time; two taps of "save" would otherwise race to write the state. */
  private val mutex = Mutex()

  /** Reads what is already on disk. Called at startup, before anything is asked of any provider. */
  suspend fun refreshState() {
    _configured.value = stores.filterValues { it.hasApiKey() }.keys
  }

  /**
   * Stores a key for one provider.
   *
   * @return false when the field is blank, which is the only check made. Whether the key *works* is
   *   a question only the provider can answer, and asking costs money — so it is asked when the user
   *   wants an analysis anyway.
   */
  suspend fun saveApiKey(provider: AnalysisProvider, key: String): CredentialSaveResult =
    mutex.withLock {
      val store = stores[provider] ?: return CredentialSaveResult.InvalidInput
      val trimmed = key.trim()
      if (trimmed.isEmpty()) return CredentialSaveResult.InvalidInput
      val result = store.saveApiKey(trimmed)
      if (result == CredentialSaveResult.Success) {
        _configured.value = _configured.value + provider
        _saveFailure.value = null
      } else {
        _saveFailure.value = provider
      }
      return result
    }

  /**
   * Forgets one provider's key.
   *
   * Nothing else to clear: no analysis is ever written to the database. Each lives as long as the
   * card showing it, which is the other half of "this feature changes nothing".
   */
  suspend fun clearApiKey(provider: AnalysisProvider) {
    mutex.withLock {
      stores[provider]?.clearApiKey()
      _configured.value = _configured.value - provider
      if (_saveFailure.value == provider) _saveFailure.value = null
    }
  }

  /** The key source for one provider's client, read fresh from its own store on every call. */
  fun keySource(provider: AnalysisProvider): AnalysisApiKeySource = AnalysisApiKeySource {
    stores[provider]?.apiKey()
  }
}

/**
 * Whether the provider behind a given model has a key.
 *
 * An extension rather than a method on the model, because "is this configured" is a fact about the
 * device, not about the model.
 */
fun Set<AnalysisProvider>.supports(model: AnalysisModel): Boolean = contains(model.provider)
