package fi.merilainen.treenivalmentaja.data.security

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by one service's connection, authenticator and repository in the application graph.
 * Network requests run outside the lock. Disconnect invalidates their generation and clears
 * storage under the same lock used for commits, so a late response cannot resurrect deleted data.
 */
class ConnectionGeneration {
  private val generation = AtomicLong()
  private val commits = Mutex()

  fun current(): Long = generation.get()

  suspend fun <T : Any> commit(expected: Long, block: suspend () -> T): T? =
    commits.withLock {
      if (generation.get() == expected) block() else null
    }

  suspend fun <T> invalidate(block: suspend () -> T): T = commits.withLock {
    generation.incrementAndGet()
    block()
  }
}
