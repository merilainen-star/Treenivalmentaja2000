package fi.merilainen.treenivalmentaja

import android.app.Application
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.domain.TrainingEngine

/**
 * Holds the process-wide database and repository. Deliberately a plain lazy field rather than a DI
 * framework — there is exactly one graph and one consumer.
 */
class TreenivalmentajaApplication : Application() {

  val repository: TrainingRepository by lazy {
    TrainingRepository(AppDatabase.getInstance(this))
  }

  val engine: TrainingEngine by lazy {
    TrainingEngine(repository)
  }
}
