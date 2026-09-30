package com.swipegallery.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.swipegallery.R

/** Instrument Serif (OFL 1.1), bundled. Used only for large editorial headings. */
val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

/** Inter (OFL 1.1), bundled. Body copy, buttons, counters and navigation. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

@Immutable
data class SwipeTypography(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val numeral: TextStyle,
    val bodyLarge: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val button: TextStyle,
    val nav: TextStyle,
    val stamp: TextStyle,
)

fun swipeTypography(displaySize: TextUnit): SwipeTypography = SwipeTypography(
    display = TextStyle(
        fontFamily = InstrumentSerif,
        fontSize = displaySize,
        lineHeight = displaySize * 1.05f,
        letterSpacing = (-0.01).em,
    ),
    headline = TextStyle(fontFamily = InstrumentSerif, fontSize = 36.sp, lineHeight = 40.sp),
    title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
    numeral = TextStyle(fontFamily = InstrumentSerif, fontSize = 44.sp, lineHeight = 46.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 17.sp, lineHeight = 25.sp),
    body = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
    label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.01.em),
    button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    nav = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    stamp = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 0.08.em),
)

/** 44–52sp depending on available width. Font scaling still applies on top (sp). */
@Composable
fun responsiveDisplaySize(): TextUnit {
    val width = LocalConfiguration.current.screenWidthDp
    return when {
        width < 360 -> 44.sp
        width < 600 -> 48.sp
        else -> 52.sp
    }
}
