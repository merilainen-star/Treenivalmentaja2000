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
import fi.merilainen.treenivalmentaja.domain.MissedProposalDismissalStore
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.update.HttpUpdateService
import fi.merilainen.treenivalmentaja.domain.CheckForUpdateUseCase
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import fi.merilainen.treenivalmentaja.data.settings.MissedProposalSettingsStore
import fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase
import fi.merilainen.treenivalmentaja.domain.ResolveReminderUseCase
import fi.merilainen.treenivalmentaja.domain.TrainingEngine


import fi.merilainen.treenivalmentaja.data.notification.NotificationChannels
import fi.merilainen.treenivalmentaja.data.alarm.ReminderScheduler
import fi.merilainen.treenivalmentaja.data.oura.OuraAuthService
import fi.merilainen.treenivalmentaja.data.oura.OuraAuthenticator
import fi.merilainen.treenivalmentaja.data.oura.OuraClient
import fi.merilainen.treenivalmentaja.data.oura.OuraConnection
import fi.merilainen.treenivalmentaja.data.oura.OuraConnectionState
import fi.merilainen.treenivalmentaja.data.oura.OuraSyncWorker
import fi.merilainen.treenivalmentaja.data.repository.OuraRepository
import fi.merilainen.treenivalmentaja.data.oura.OuraCredentials
import fi.merilainen.treenivalmentaja.data.oura.OuraCredentialsSource
import fi.merilainen.treenivalmentaja.data.oura.OuraTokenStore
import fi.merilainen.treenivalmentaja.data.oura.clearCachedOuraData
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsApiKeyStore
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsClient
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsConnection
import fi.merilainen.treenivalmentaja.data.intervals.clearCachedIntervalsData
import fi.merilainen.treenivalmentaja.data.repository.IntervalsRepository
import fi.merilainen.treenivalmentaja.data.analysis.AnalysisApiKeyStore
import fi.merilainen.treenivalmentaja.data.analysis.AnalysisClient
import fi.merilainen.treenivalmentaja.data.analysis.AnalysisConnection
import fi.merilainen.treenivalmentaja.data.analysis.AnthropicClient
import fi.merilainen.treenivalmentaja.data.analysis.GeminiClient
import fi.merilainen.treenivalmentaja.data.analysis.OpenAiClient
import fi.merilainen.treenivalmentaja.data.settings.AnalysisSettingsStore
import fi.merilainen.treenivalmentaja.domain.AnalysisPromptBuilder
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class TreenivalmentajaApplication : Application(), ImageLoaderFactory {

  /**
   * For work that must outlive the screen that started it — currently the OAuth token exchange,
   * which arrives at an activity that finishes immediately after forwarding it.
   */
  val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

  private val ouraTokenStore: OuraTokenStore by lazy { OuraTokenStore(this) }

  /**
   * What the user typed into Settings, and only failing that what the build was compiled with.
   *
   * That order is the point. Oura withdrew personal access tokens, so an application registered in
   * their developer portal is the only way in, and requiring its secret to be compiled in would
   * mean a PC, a checkout and a local build — none of which this app is installed by. Typed
   * credentials therefore win; `BuildConfig` remains so that a local `.env` build keeps working
   * without anyone typing anything. See ADR-009 in `docs/DECISIONS.md`.
   */
  private val ouraCredentials: OuraCredentialsSource = OuraCredentialsSource {
    ouraTokenStore.credentials()
      ?: OuraCredentials(
        clientId = BuildConfig.OURA_CLIENT_ID,
        clientSecret = BuildConfig.OURA_CLIENT_SECRET,
      )
  }

  private val ouraAuthService: OuraAuthService by lazy { OuraAuthService(ouraCredentials) }

  /** One for the whole process: the state Settings observes has to be the state that changes. */
  val ouraConnection: OuraConnection by lazy {
    OuraConnection(
      store = ouraTokenStore,
      authService = ouraAuthService,
      credentials = ouraCredentials,
      onDisconnected = { db.ouraDao().clearCachedOuraData() },
    )
  }

  /**
   * The Oura API client, with token renewal installed.
   *
   * The `Authenticator` goes on this client and not on the one inside [OuraAuthService]: the
   * refresh call must never be able to trigger a refresh of its own.
   */
  internal val ouraClient: OuraClient by lazy {
    OuraClient(
      tokens = ouraConnection.tokenSource(),
      calls =
        OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(10, TimeUnit.SECONDS)
          .authenticator(
            OuraAuthenticator(
              store = ouraTokenStore,
              service = ouraAuthService,
              onRefreshFailed = { applicationScope.launch { ouraConnection.refreshState() } },
            )
          )
          .build(),
    )
  }

  internal val ouraRepository: OuraRepository by lazy {
    OuraRepository(client = ouraClient, dao = db.ouraDao())
  }

  private val intervalsApiKeyStore: IntervalsApiKeyStore by lazy { IntervalsApiKeyStore(this) }

  /**
   * No `Authenticator` here, unlike the Oura client.
   *
   * That piece exists to renew an access token on a `401` without spending a rotated refresh token
   * twice. A personal API key does not expire and is not rotated, so there is nothing to renew:
   * a `401` from intervals.icu means the key is wrong, which is a state for Settings to show
   * rather than something a retry could fix.
   */
  internal val intervalsClient: IntervalsClient by lazy {
    IntervalsClient(
      apiKeys = { intervalsApiKeyStore.apiKey() },
      calls =
        OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(10, TimeUnit.SECONDS)
          .build(),
    )
  }

  /** One for the whole process, for the reason [ouraConnection] is. */
  val intervalsConnection: IntervalsConnection by lazy {
    IntervalsConnection(
      store = intervalsApiKeyStore,
      client = intervalsClient,
      onKeyCleared = { db.intervalsDao().clearCachedIntervalsData() },
    )
  }

  internal val intervalsRepository: IntervalsRepository by lazy {
    IntervalsRepository(client = intervalsClient, dao = db.intervalsDao())
  }

  /**
   * One key store per provider, each with its own Keystore alias and its own preferences file.
   *
   * Separate files so that clearing one provider's key cannot touch another's — a property of the
   * layout rather than of careful key naming.
   */
  private val analysisKeyStores by lazy {
    AnalysisProvider.entries.associateWith { AnalysisApiKeyStore(this, it) }
  }

  /** One for the whole process, for the reason [ouraConnection] is. */
  val analysisConnection: AnalysisConnection by lazy {
    AnalysisConnection(stores = analysisKeyStores)
  }

  /**
   * One client per provider, chosen at request time by the selected model's provider.
   *
   * Built eagerly into a map rather than lazily per call: they are stateless, hold nothing but a key
   * source, and constructing one costs a hash lookup. Each takes its key from its *own* store, so a
   * key pasted into the wrong field cannot authenticate somewhere it was not meant to.
   */
  internal val analysisClients: Map<AnalysisProvider, AnalysisClient> by lazy {
    mapOf(
      AnalysisProvider.ANTHROPIC to
        AnthropicClient(apiKeys = analysisConnection.keySource(AnalysisProvider.ANTHROPIC)),
      AnalysisProvider.OPENAI to
        OpenAiClient(apiKeys = analysisConnection.keySource(AnalysisProvider.OPENAI)),
      AnalysisProvider.GEMINI to
        GeminiClient(apiKeys = analysisConnection.keySource(AnalysisProvider.GEMINI)),
    )
  }

  val analysisSettingsStore: AnalysisSettingsStore by lazy { AnalysisSettingsStore(this) }

  /** Keeps a "ei nyt" on the missed-session card across restarts and app updates. */
  val missedProposalDismissalStore: MissedProposalDismissalStore by lazy {
    MissedProposalSettingsStore(this)
  }

  /** Pure, stateless, and shared for that reason — it holds nothing between calls. */
  val analysisPromptBuilder: AnalysisPromptBuilder by lazy { AnalysisPromptBuilder() }

  override fun onCreate() {
    super.onCreate()
    NotificationChannels.createChannels(this)
    applicationScope.launch {
      // Settings must be able to say whether Oura is connected the moment it is opened, and the
      // answer is on disk behind a Keystore decryption rather than in memory.
      ouraConnection.refreshState()
      intervalsConnection.refreshState()
      analysisConnection.refreshState()
    }
    // Scheduled only while there is something to fetch with — a worker that woke daily to discover
    // it has no token would be a battery cost with no possible result. Collected rather than
    // checked once, so connecting or disconnecting mid-session takes effect immediately instead of
    // at the next launch.
    applicationScope.launch {
      ouraConnection.state.collect { state ->
        if (state == OuraConnectionState.Connected) {
          OuraSyncWorker.schedule(this@TreenivalmentajaApplication)
        } else {
          OuraSyncWorker.cancel(this@TreenivalmentajaApplication)
        }
      }
    }
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
