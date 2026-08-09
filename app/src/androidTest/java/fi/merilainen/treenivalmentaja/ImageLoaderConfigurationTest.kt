package fi.merilainen.treenivalmentaja

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.request.CachePolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one property of the image loader that is a legal requirement rather than a preference:
 * nothing ExerciseDB serves may be written to the device. See `docs/EXERCISE_GUIDE.md` section 3.
 *
 * Coil caches to disk by default, so this is a guard against a future change quietly restoring
 * that default — a breach that would leave no visible trace in the app.
 *
 * It asserts the configuration rather than loading a real image: a test that depended on a free
 * service whose only published rate limit is the word "strict" would fail for reasons that have
 * nothing to do with this app. The empirical check — load a guide, then look at the cache
 * directory — was done by hand on the emulator and is recorded in `PROJECT_STATUS.md`.
 */
@RunWith(AndroidJUnit4::class)
class ImageLoaderConfigurationTest {

  private val context: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun theImageLoaderHasNoDiskCacheAtAll() {
    assertNull("a disk cache is a terms-of-use breach", context.imageLoader.diskCache)
  }

  @Test
  fun requestsNeitherReadNorWriteADiskCache() {
    assertEquals(CachePolicy.DISABLED, context.imageLoader.defaults.diskCachePolicy)
  }

  /** Coil's default directory. It must never come into existence. */
  @Test
  fun coilsCacheDirectoryIsNeverCreated() {
    assertFalse(File(context.cacheDir, "image_cache").exists())
  }

  /** The memory cache is what the terms do allow: it dies with the process. */
  @Test
  fun theMemoryCacheStaysOn() {
    assertEquals(CachePolicy.ENABLED, context.imageLoader.defaults.memoryCachePolicy)
  }
}
