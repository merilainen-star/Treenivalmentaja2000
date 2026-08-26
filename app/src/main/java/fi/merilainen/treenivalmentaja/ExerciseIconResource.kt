package fi.merilainen.treenivalmentaja

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.ExerciseIcon

/**
 * The one place an [ExerciseIcon] becomes a drawable.
 *
 * `when` without an `else`, so adding a value to the enum stops the build here rather than shipping
 * a movement with no mark.
 */
@DrawableRes
fun ExerciseIcon.drawableRes(): Int =
  when (this) {
    ExerciseIcon.PLANK -> R.drawable.ic_exercise_plank
    ExerciseIcon.PUSHUP -> R.drawable.ic_exercise_pushup
    ExerciseIcon.SIDE_PLANK -> R.drawable.ic_exercise_side_plank
    ExerciseIcon.SQUAT -> R.drawable.ic_exercise_squat
    ExerciseIcon.LUNGE -> R.drawable.ic_exercise_lunge
    ExerciseIcon.STRETCH -> R.drawable.ic_exercise_hip_flexor_stretch
    ExerciseIcon.ROW -> R.drawable.ic_exercise_dumbbell_row
    ExerciseIcon.SWING -> R.drawable.ic_exercise_kettlebell_swing
    ExerciseIcon.CRUNCH -> R.drawable.ic_exercise_crunch
    ExerciseIcon.QUADRUPED -> R.drawable.ic_exercise_quadruped
    ExerciseIcon.BIRD_DOG -> R.drawable.ic_exercise_bird_dog
    ExerciseIcon.GENERIC -> R.drawable.ic_exercise_generic
  }

/**
 * The movement's category mark, at the size a line of text can carry.
 *
 * [size] is the only knob: the icon sits beside a name on a list row at 20dp and beside a heading
 * on the guided screen at 32dp, and nothing else about it changes between the two.
 */
@Composable
fun ExerciseIcon(
  name: String,
  modifier: Modifier = Modifier,
  size: Dp = 20.dp,
  tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  Icon(
    painter = painterResource(fi.merilainen.treenivalmentaja.domain.ExerciseIcon.forName(name).drawableRes()),
    // The name is already read out beside it; announcing the category again would be noise.
    contentDescription = null,
    modifier = modifier.size(size),
    tint = tint,
  )
}
