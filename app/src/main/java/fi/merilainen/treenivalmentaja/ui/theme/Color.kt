package fi.merilainen.treenivalmentaja.ui.theme

import androidx.compose.ui.graphics.Color

val BluePrimary = Color(0xFF007AFF)
val BluePrimaryDark = Color(0xFF0A5CC7)
val PrimaryContainerDark = Color(0xFF002B75)
val PrimaryContainerLight = Color(0xFFD0E4FF)
val GreenAccent = Color(0xFF43A047)
val RedAccent = Color(0xFFE53935)
val YellowAccent = Color(0xFFFDD835)

// Tonal containers for the two accents. Material 3 derives these from its own baseline palette
// when a scheme leaves them out, which is how a FilledTonalButton came out lilac in an app whose
// palette has no lilac in it. Same families as the accents above, two steps lighter and darker.
val SecondaryContainerLight = Color(0xFFC8E6C9)
val OnSecondaryContainerLight = Color(0xFF1B5E20)
val SecondaryContainerDark = Color(0xFF1B5E20)
val OnSecondaryContainerDark = Color(0xFFC8E6C9)
val ErrorContainerLight = Color(0xFFFFCDD2)
val OnErrorContainerLight = Color(0xFFB71C1C)
val ErrorContainerDark = Color(0xFF7F1D1D)
val OnErrorContainerDark = Color(0xFFFFCDD2)

val SurfaceLight = Color(0xFFF8F9FA)
val SurfaceContainerLowLight = Color(0xFFF1F3F4)
val SurfaceContainerLight = Color(0xFFE8EAED)
val SurfaceContainerHighLight = Color(0xFFDADCE0)
val BackgroundLight = SurfaceLight
val TextPrimaryLight = Color(0xFF1C1B1F)
val TextSecondaryLight = Color(0xFF49454F)

val SurfaceDark = Color(0xFF121212)
val SurfaceContainerLowDark = Color(0xFF1E1E1E)
val SurfaceContainerDark = Color(0xFF252525)
val SurfaceContainerHighDark = Color(0xFF2C2C2C)
val BackgroundDark = SurfaceDark
val TextPrimaryDark = Color(0xFFE6E1E5)
val TextSecondaryDark = Color(0xFFCAC4D0)

// Status colors
val ColorGreen = Color(0xFF4CAF50)
val ColorYellow = Color(0xFFFFB300)
val ColorRed = Color(0xFFF44336)
val ColorGray = Color(0xFF9E9E9E)
val ColorBlue = BluePrimary
