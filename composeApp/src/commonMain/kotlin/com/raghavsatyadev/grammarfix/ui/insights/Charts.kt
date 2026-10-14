package com.raghavsatyadev.grammarfix.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The charts the Insights tab draws, by hand, on a [Canvas].
 *
 * Deliberately not a charting library. A sparkline and a bar chart are a few lines of drawing each,
 * they take their colours straight from the app's own scheme in both themes, and they cannot fall
 * behind a Compose Multiplatform release the way a dependency can. When a chart here needs axes,
 * scales and touch handling, that is the moment to reach for KoalaPlot — not before.
 */
private val BarCorner = 3.dp

/**
 * A line over [values], oldest first, sized to whatever box it is given.
 *
 * All-equal values (including all zero) draw a flat line through the middle rather than pinning to
 * the top or the bottom, which is what a naive min/max normalisation does with a constant series.
 */
@Composable
fun Sparkline(
  values: List<Int>,
  color: Color,
  modifier: Modifier = Modifier,
  height: Dp = 36.dp,
  strokeWidth: Dp = 2.dp,
) {
  Canvas(modifier.fillMaxWidth().height(height)) {
    if (values.size < 2) return@Canvas
    val points = pointsFor(values, size, strokeWidth.toPx())

    // A soft wash under the line: the trend reads at a glance without a second colour.
    val area =
      Path().apply {
        moveTo(points.first().x, size.height)
        points.forEach { lineTo(it.x, it.y) }
        lineTo(points.last().x, size.height)
        close()
      }
    drawPath(
      area,
      Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)),
    )

    val line =
      Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
      }
    drawPath(line, color, style = Stroke(width = strokeWidth.toPx()))
  }
}

/** Evenly spaced points for [values], inset by half the stroke so the line is not clipped. */
private fun pointsFor(values: List<Int>, size: Size, stroke: Float): List<Offset> {
  val min = values.min()
  val max = values.max()
  val top = stroke / 2
  val usable = (size.height - stroke).coerceAtLeast(1f)
  val step = size.width / (values.size - 1)
  return values.mapIndexed { index, value ->
    val fraction = if (max == min) 0.5f else (value - min).toFloat() / (max - min)
    Offset(x = index * step, y = top + usable * (1f - fraction))
  }
}

/**
 * One bar per day, oldest first.
 *
 * Every day gets a faint track whether or not anything happened in it, so a quiet week looks like a
 * quiet week instead of a chart with pieces missing — and the bars stay where they are as days come
 * and go, which is what makes the shape comparable from one visit to the next.
 */
@Composable
fun ActivityBars(
  values: List<Int>,
  color: Color,
  trackColor: Color,
  modifier: Modifier = Modifier,
  height: Dp = 120.dp,
) {
  Canvas(modifier.fillMaxWidth().height(height)) {
    if (values.isEmpty()) return@Canvas
    val gap = (size.width / values.size * 0.28f).coerceAtMost(6f)
    val barWidth = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)
    val max = values.max().coerceAtLeast(1)

    values.forEachIndexed { index, value ->
      val x = index * (barWidth + gap)
      drawBar(x, barWidth, size.height, size.height, trackColor)
      if (value > 0) {
        // A day with anything in it is never a hairline: one pixel of bar reads as no bar.
        val barHeight = (size.height * value / max).coerceAtLeast(BarCorner.toPx() * 2)
        drawBar(x, barWidth, barHeight, size.height, color)
      }
    }
  }
}

private fun DrawScope.drawBar(x: Float, width: Float, barHeight: Float, full: Float, color: Color) {
  drawRoundRect(
    color = color,
    topLeft = Offset(x, full - barHeight),
    size = Size(width, barHeight),
    cornerRadius = androidx.compose.ui.geometry.CornerRadius(BarCorner.toPx()),
  )
}
