package uz.tiyin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

data class Strand(val weight: Float, val color: Color)

/**
 * A strip of ikat cloth: one vertical band per thread, its width proportional to the weight, with the
 * stepped, feathered borders of abr dyeing. The offsets are deterministic so the cloth does not
 * shimmer between recompositions.
 */
@Composable
fun IkatCloth(threads: List<Strand>, modifier: Modifier = Modifier, steps: Int = 11, featherFraction: Float = 0.026f) {
    Canvas(modifier) {
        val total = threads.sumOf { it.weight.toDouble() }.toFloat()
        if (total <= 0f) return@Canvas
        val xs = threads.runningFold(0f) { acc, t -> acc + size.width * t.weight / total }
        val amp = size.width * featherFraction
        val rowH = size.height / steps
        fun border(j: Int): List<Pair<Float, Float>> {
            if (j == 0 || j == xs.lastIndex) return listOf(xs[j] to 0f, xs[j] to size.height)
            // Each border may eat at most 40% of either neighbour, so a thin stripe stays a stripe.
            val reach = minOf(amp, 0.4f * minOf(xs[j] - xs[j - 1], xs[j + 1] - xs[j]))
            return (0 until steps).flatMap { k ->
                val off = reach * ((((k * 7 + j * 5 + 3) % 5) - 2) / 2f)
                listOf(xs[j] + off to k * rowH, xs[j] + off to (k + 1) * rowH)
            }
        }
        threads.forEachIndexed { i, t ->
            val pts = border(i) + border(i + 1).reversed()
            val path = Path().apply {
                moveTo(pts[0].first, pts[0].second)
                pts.drop(1).forEach { (x, y) -> lineTo(x, y) }
                close()
            }
            drawPath(path, t.color)
        }
    }
}

/** A small, straight-edged swatch of the same cloth, for places where the feathering would be noise. */
@Composable
fun ClothSwatch(threads: List<Strand>, empty: Color, modifier: Modifier = Modifier) {
    Row(modifier.background(empty)) {
        threads.filter { it.weight > 0f }.forEach {
            Box(Modifier.weight(it.weight).fillMaxHeight().background(it.color))
        }
    }
}
