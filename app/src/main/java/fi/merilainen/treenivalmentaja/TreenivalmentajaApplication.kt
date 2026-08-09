package fi.merilainen.treenivalmentaja

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.CachePolicy
import fi.merilainen.treenivalmentaja.data.guide.ExerciseDbProvider
import fi.merilainen.treenivalmentaja.data.guide.WgerProvider
import fi.merilainen.treenivalmentaja.domain.LoadExerciseGuideUseCase
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.update.HttpUpdateService
import fi.merilainen.treenivalmentaja.domain.CheckForUpdateUseCase
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase
import fi.merilainen.treenivalmentaja.domain.ResolveReminderUseCase
import fi.merilainen.treenivalmentaja.domain.TrainingEngine


import fi.merilainen.treenivalmentaja.data.notification.NotificationChannels
import fi.merilainen.treenivalmentaja.data.alarm.ReminderScheduler

class TreenivalmentajaApplication : Application(), ImageLoaderFactory {

  val db: AppDatabase by lazy { AppDatabase.getInstance(this) }

  val repository: TrainingRepository by lazy {
    TrainingRepository(db)
  }

  val engine: TrainingEngine by lazy {
    TrainingEngine(
      repository = repository,
      rescheduleAlarmsUseCase = rescheduleAlarmsUseCase
    )
  }

  val settingsStore: NotificationSettingsStore by lazy {
    NotificationSettingsStore(this)
  }

  val resolveReminderUseCase: ResolveReminderUseCase by lazy {
    ResolveReminderUseCase()
  }


  val reminderScheduler: ReminderScheduler by lazy {
    ReminderScheduler(this)
  }

  val checkForUpdateUseCase: CheckForUpdateUseCase by lazy {
    CheckForUpdateUseCase(
      service = HttpUpdateService(),
      installedVersionName = BuildConfig.VERSION_NAME,
    )
  }

  /**
   * One instance for the whole process: its cache is the only place guide data is allowed to
   * live, and a per-screen instance would refetch the same movement on every tap.
   *
   * ExerciseDB first, because it is the one with an animation for every movement and the only one
   * whose name search works. wger is what a plan reaches for when ExerciseDB has no such movement
   * at all — plank, side plank, plain squat, bird dog and cat-cow are all missing from it.
   */
  val loadExerciseGuideUseCase: LoadExerciseGuideUseCase by lazy {
    LoadExerciseGuideUseCase(providers = listOf(ExerciseDbProvider(), WgerProvider()))
  }

  val rescheduleAlarmsUseCase: RescheduleAlarmsUseCase by lazy {
    RescheduleAlarmsUseCase(
      database = db,
      planDao = db.trainingPlanDao(),
      sessionDao = db.workoutSessionDao(),
      settingsStore = settingsStore,
      resolveReminderUseCase = resolveReminderUseCase,
      reminderScheduler = reminderScheduler
    )
  }

  override fun onCreate() {
    super.onCreate()
    NotificationChannels.createChannels(this)
  }

  /**
   * The image loader every `AsyncImage` in the app uses.
   *
   * **The disabled disk cache is a terms-of-use requirement, not a tuning choice.** ExerciseDB's
   * terms forbid storing its images on the device; Coil writes them to disk by default, so
   * leaving the default in place would put the app in breach the first time a guide was opened.
   * The memory cache stays on: it dies with the process, which is what the terms allow.
   *
   * Both `diskCache(null)` and the disabled policy are set, and neither is redundant. The policy
   * stops requests reading and writing; passing no cache at all means there is no directory to
   * create in the first place, so the guarantee does not rest on every code path honouring a
   * flag. `ImageLoaderConfigurationTest` holds it in place.
   *
   * Coil decodes no animated GIF unless a decoder is registered. `ImageDecoderDecoder` is the
   * hardware-backed one and needs API 28; `minSdk` is 26, so the two older releases fall back to
   * `GifDecoder`.
   */
  override fun newImageLoader(): ImageLoader =
    ImageLoader.Builder(this)
      .diskCache(null)
      .diskCachePolicy(CachePolicy.DISABLED)
      .memoryCachePolicy(CachePolicy.ENABLED)
      .components {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          add(ImageDecoderDecoder.Factory())
        } else {
          add(GifDecoder.Factory())
        }
      }
      .build()
}
