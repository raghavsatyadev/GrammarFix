package com.raghavsatyadev.grammarfix.library.support.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.poppins_bold
import grammarfix.composeapp.generated.resources.poppins_medium
import grammarfix.composeapp.generated.resources.poppins_regular
import grammarfix.composeapp.generated.resources.poppins_semibold
import org.jetbrains.compose.resources.Font

@Composable
fun getAppFontFamily() =
  FontFamily(
    Font(Res.font.poppins_bold, FontWeight.Bold, FontStyle.Normal),
    Font(Res.font.poppins_medium, FontWeight.Medium, FontStyle.Normal),
    Font(Res.font.poppins_regular, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.poppins_semibold, FontWeight.SemiBold, FontStyle.Normal),
  )

@Composable
fun getAppTypoGraphy(): Typography {
  val fontFamily = getAppFontFamily()

  val baseline = Typography()

  val typography =
    Typography(
      displayLarge =
        baseline.displayLarge.copy(
          fontFamily = fontFamily,
          fontSize = 36.sp,
          fontWeight = FontWeight.ExtraBold,
          lineHeight = (36 * 1.1).sp,
          letterSpacing = (-0.03).em,
        ),
      displayMedium =
        baseline.displayMedium.copy(
          fontFamily = fontFamily,
          fontSize = 30.sp,
          lineHeight = (30 * 1.15).sp,
        ),
      displaySmall =
        baseline.displaySmall.copy(
          fontFamily = fontFamily,
          fontSize = 24.sp,
          lineHeight = (24 * 1.2).sp,
        ),
      headlineLarge =
        baseline.headlineLarge.copy(
          fontFamily = fontFamily,
          fontSize = 24.sp,
          fontWeight = FontWeight.Bold,
          lineHeight = (24 * 1.2).sp,
          letterSpacing = (-0.02).em,
        ),
      headlineMedium =
        baseline.headlineMedium.copy(
          fontFamily = fontFamily,
          fontSize = 20.sp,
          fontWeight = FontWeight.SemiBold,
          lineHeight = (20 * 1.25).sp,
        ),
      headlineSmall =
        baseline.headlineSmall.copy(
          fontFamily = fontFamily,
          fontSize = 17.sp,
          fontWeight = FontWeight.SemiBold,
          lineHeight = (17 * 1.3).sp,
        ),
      titleLarge =
        baseline.titleLarge.copy(
          fontFamily = fontFamily,
          fontSize = 18.sp,
          fontWeight = FontWeight.SemiBold,
          lineHeight = 22.sp,
        ),
      titleMedium =
        baseline.titleMedium.copy(
          fontFamily = fontFamily,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          lineHeight = 19.sp,
        ),
      titleSmall =
        baseline.titleSmall.copy(
          fontFamily = fontFamily,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          lineHeight = 17.sp,
        ),
      bodyLarge =
        baseline.bodyLarge.copy(
          fontFamily = fontFamily,
          fontSize = 15.sp,
          fontWeight = FontWeight.Normal,
          lineHeight = (15 * 1.45).sp,
        ),
      bodyMedium =
        baseline.bodyMedium.copy(
          fontFamily = fontFamily,
          fontSize = 13.sp,
          fontWeight = FontWeight.Normal,
          lineHeight = (13 * 1.4).sp,
        ),
      bodySmall =
        baseline.bodySmall.copy(
          fontFamily = fontFamily,
          fontSize = 11.sp,
          fontWeight = FontWeight.Normal,
          lineHeight = (11 * 1.3).sp,
        ),
      labelLarge =
        baseline.labelLarge.copy(
          fontFamily = fontFamily,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          lineHeight = 14.sp,
          letterSpacing = 0.02.em,
        ),
      labelMedium =
        baseline.labelMedium.copy(
          fontFamily = fontFamily,
          fontSize = 11.sp,
          fontWeight = FontWeight.Medium,
          lineHeight = 13.sp,
        ),
      labelSmall =
        baseline.labelSmall.copy(
          fontFamily = fontFamily,
          fontSize = 10.sp,
          fontWeight = FontWeight.Medium,
          lineHeight = 12.sp,
        ),
    )

  return typography
}
