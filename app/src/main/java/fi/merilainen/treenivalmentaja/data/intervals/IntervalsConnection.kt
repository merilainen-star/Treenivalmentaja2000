package fi.merilainen.treenivalmentaja.data.intervals

import fi.merilainen.treenivalmentaja.data.local.dao.IntervalsDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Settings draws, and the only intervals.icu state anything outside this package sees. */
sealed interface IntervalsConnectionState {

  /** No API key yet — Settings shows the field for one. */
  data object NotConfigured : IntervalsConnectionState

  /** A key is stored. Whether it *works* is what [IntervalsConnection.testKey] answers. */
  data object Configured : IntervalsConnectionState

  /** A test is in flight. */
  data object Testing : IntervalsConnectionState

  /** The key was tried and worked. [activities] is what the test call found, and may be 0. */
  data class Verified(val activities: Int) : IntervalsConnectionState

  /** The last test failed, with a reason already written in Finnish. */
  data class Failed(val message: String) : IntervalsConnectionState
}

/**
 * The intervals.icu key, and what is known about whether it works.
 *
 * Far smaller than the OAuth connections this replaces, and that is the point of choosing a
 * personal API key for a single-user app: there is no browser round trip to survive, no `state` to
 * validate, no refresh token to avoid spending twice, and no exported callback activity. What
 * remains is "is there a key" and "did it work when we last asked".
 *
 * @param onKeyCleared drops the cached activity rows. Passed in rather than reached for — this
 *   class has no business knowing what a training plan is, and clearing a key must not touch one.
 */
class IntervalsConnection internal constructor(
  private val store: IntervalsApiKeyStorage,
  private val client: IntervalsClient,
  private val onKeyCleared: suspend () -> Unit,
) {

  private val _state = MutableStateFlow<IntervalsConnectionState>(
    IntervalsConnectionState.NotConfigured
  )
  val state: StateFlow<IntervalsConnectionState> = _state.asStateFlow()

  /** One operation at a time; two taps of "test" would otherwise race to write the state. */
  private val mutex = Mutex()

  /** Reads what is already on disk. Called at startup, before anything asks intervals.icu. */
  suspend fun refreshState() {
    _state.value =
      if (store.hasApiKey()) IntervalsConnectionState.Configured
      else IntervalsConnectionState.NotConfigured
  }

  /**
   * Stores the key pasted from intervals.icu's own settings page.
   *
   * @return false when the field is blank, which is the only check possible here — whether the key
   *   is *valid* is a question only intervals.icu can answer, and [testKey] is how it is asked.
   */
  suspend fun saveApiKey(key: String): Boolean =
    mutex.withLock {
      val trimmed = key.trim()
      if (trimmed.isEmpty()) return false
      store.saveApiKey(trimmed)
      _state.value = IntervalsConnectionState.Configured
      return true
    }

  /**
   * Asks intervals.icu whether the stored key works, using the same request the sync makes.
   *
   * Finding zero activities is a **success**: it means the key authenticated and the account
   * simply has nothing in the window. Reporting that as a failure would send someone hunting for a
   * broken key that is fine.
   */
  suspend fun testKey() {
    mutex.withLock {
      if (!store.hasApiKey()) {
        _state.value = IntervalsConnectionState.NotConfigured
        return
      }
      _state.value = IntervalsConnectionState.Testing
      _state.value =
        try {
          IntervalsConnectionState.Verified(client.testKey())
        } catch (e: IntervalsException) {
          IntervalsConnectionState.Failed(e.message ?: "Intervals.icu-yhteys epäonnistui.")
        }
    }
  }

  /** Forgets the key and every activity cached with it. The training plan is untouched. */
  suspend fun clearApiKey() {
    mutex.withLock {
      store.clearApiKey()
      onKeyCleared()
      _state.value = IntervalsConnectionState.NotConfigured
    }
  }

  /** Clears a message the user has read, without changing whether a key is stored. */
  suspend fun dismissFailure() {
    if (_state.value is IntervalsConnectionState.Failed) refreshState()
  }

  /** The key for [IntervalsClient], read fresh from the store on every call. */
  fun keySource(): IntervalsApiKeySource = IntervalsApiKeySource { store.apiKey() }
}

/** Clearing the key drops the cached activity rows and nothing else. */
internal suspend fun IntervalsDao.clearCachedIntervalsData() {
  clearActivities()
}
