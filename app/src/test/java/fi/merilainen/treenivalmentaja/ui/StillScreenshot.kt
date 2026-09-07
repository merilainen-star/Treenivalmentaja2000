package fi.merilainen.treenivalmentaja.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import kotlinx.coroutines.CancellationException

/** An indeterminate spinner has no idle state. Freeze infinite animations only in screenshots;
 * finite transitions still settle normally and the production composables stay untouched.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal fun stillScreenshot(block: () -> Unit) {
  val policy = object : InfiniteAnimationPolicy {
    override suspend fun <R> onInfiniteOperation(block: suspend () -> R): R =
      throw CancellationException("Still screenshot: infinite animation is frozen")
  }
  WindowRecomposerPolicy.withFactory(
    WindowRecomposerFactory { it.createLifecycleAwareWindowRecomposer(coroutineContext = policy) },
    block,
  )
}
