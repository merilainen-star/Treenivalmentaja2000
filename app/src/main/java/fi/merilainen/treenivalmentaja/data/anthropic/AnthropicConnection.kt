package fi.merilainen.treenivalmentaja.data.anthropic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Settings draws, and the only Anthropic state anything outside this package sees. */
sealed interface AnthropicConnectionState {

  /** No API key yet — Settings shows the field for one. */
  data object NotConfigured : AnthropicConnectionState

  /**
   * A key is stored.
   *
   * **There is no `Verified` state here**, unlike the intervals.icu connection. Nothing has proved
   * this key works, and nothing will until the first analysis is asked for — see
   * [AnthropicConnection] for why that is deliberate rather than an omission.
   */
  data object Configured : AnthropicConnectionState
}

/**
 * The Anthropic key, and whether there is one.
 *
 * Smaller even than the intervals.icu connection, and the reason is the one design decision worth
 * recording here: **saving a key does not test it.**
 *
 * intervals.icu's connection tests on save, because its test call is free and a key pasted with a
 * character missing would otherwise look accepted and then quietly fetch nothing. Every call to
 * Anthropic costs the owner money. Spending it to validate a paste — at the moment the user is
 * setting things up, not asking for anything — is the app deciding to bill someone for reassurance
 * they did not request. So the first real "AI-analyysi" tap is the test, and a `401` there says so
 * in as many words.
 *
 * That leaves two states rather than five, no `Testing`, and no `Failed`: a failure belongs to the
 * analysis that provoked it and is shown on that card, not stored here as a property of the key.
 */
class AnthropicConnection internal constructor(private val store: AnthropicApiKeyStorage) {

  private val _state =
    MutableStateFlow<AnthropicConnectionState>(AnthropicConnectionState.NotConfigured)
  val state: StateFlow<AnthropicConnectionState> = _state.asStateFlow()

  /** One operation at a time; two taps of "save" would otherwise race to write the state. */
  private val mutex = Mutex()

  /** Reads what is already on disk. Called at startup, before anything asks Anthropic. */
  suspend fun refreshState() {
    _state.value =
      if (store.hasApiKey()) AnthropicConnectionState.Configured
      else AnthropicConnectionState.NotConfigured
  }

  /**
   * Stores the key pasted from the Anthropic console.
   *
   * @return false when the field is blank, which is the only check made. Whether the key is *valid*
   *   is a question only Anthropic can answer, and asking costs money — so it is asked when the user
   *   wants an analysis anyway.
   */
  suspend fun saveApiKey(key: String): Boolean =
    mutex.withLock {
      val trimmed = key.trim()
      if (trimmed.isEmpty()) return false
      store.saveApiKey(trimmed)
      _state.value = AnthropicConnectionState.Configured
      return true
    }

  /**
   * Forgets the key.
   *
   * Nothing else to clear: no analysis is ever written to the database. Each one lives as long as
   * the card that shows it, which is the other half of "this feature changes nothing".
   */
  suspend fun clearApiKey() {
    mutex.withLock {
      store.clearApiKey()
      _state.value = AnthropicConnectionState.NotConfigured
    }
  }

  /** The key for [AnthropicClient], read fresh from the store on every call. */
  fun keySource(): AnthropicApiKeySource = AnthropicApiKeySource { store.apiKey() }
}
