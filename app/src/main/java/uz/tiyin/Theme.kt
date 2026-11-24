package uz.tiyin

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import uz.tiyin.core.Categories

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Jost = FontFamily(variable(R.font.jost, 400), variable(R.font.jost, 500), variable(R.font.jost, 600))
val Golos = FontFamily(variable(R.font.golos_text, 400), variable(R.font.golos_text, 500), variable(R.font.golos_text, 600))

/** Natural ikat dyes; every category keeps its dye in every period so colours can be recognised. */
private enum class Dye(val light: Long, val dark: Long) {
    SAFFRON(0xFFE3A12F, 0xFFF0B547),
    POMEGRANATE(0xFFB7334A, 0xFFE0607A),
    VIOLET(0xFF6B3E8E, 0xFFA47FCF),
    TURQUOISE(0xFF1C8C99, 0xFF3FB8C4),
    INDIGO(0xFF3E4C9A, 0xFF7F8CE0),
    MADDER(0xFFD0612B, 0xFFE8814F),
    PISTACHIO(0xFF7C8B3A, 0xFFAEBE66),
    ROSE(0xFFD46A8C, 0xFFE892AE),
    WALNUT(0xFF8A5A2B, 0xFFC08A55),
    SKY(0xFF4F8FC9, 0xFF7FB2E3),
    MOSS(0xFF2F7D4F, 0xFF5FB27F),
    SLATE(0xFF7D7F96, 0xFF9C9EB8),
    SAND(0xFFA3A89F, 0xFF8E968E),
}

private val categoryDye = mapOf(
    "Продукты и доставка" to Dye.SAFFRON,
    "Кафе и рестораны" to Dye.POMEGRANATE,
    "Покупки" to Dye.VIOLET,
    "Техника" to Dye.VIOLET,
    "Маркетплейсы" to Dye.ROSE,
    "Красота" to Dye.ROSE,
    "Одежда" to Dye.MADDER,
    "Подписки и сервисы" to Dye.MADDER,
    "Здоровье" to Dye.TURQUOISE,
    "Путешествия" to Dye.SKY,
    "Такси" to Dye.INDIGO,
    "Образование" to Dye.INDIGO,
    "Транспорт" to Dye.SKY,
    "Связь" to Dye.SKY,
    "Коммунальные" to Dye.SKY,
    "Дом" to Dye.MOSS,
    "Жильё" to Dye.MOSS,
    "Спорт и развлечения" to Dye.PISTACHIO,
    "Авто" to Dye.WALNUT,
    "Животные" to Dye.WALNUT,
    "Услуги" to Dye.WALNUT,
    Categories.VIA_PAYME to Dye.SLATE,
    Categories.OTHER to Dye.SAND,
)

@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val track: Color,
    val income: Color,
    val sheetScrim: Color,
) {
    fun dye(category: String): Color {
        val d = categoryDye[category] ?: Dye.entries[Math.floorMod(category.hashCode(), Dye.entries.size - 2)]
        return Color(if (dark) d.dark else d.light)
    }
}

private val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF0F1ED), ink = Color(0xFF1B1F3B), muted = Color(0xFF5E6274), faint = Color(0xFFC3C5CC),
    track = Color(0xFFE2E3DD), income = Color(0xFF12707C), sheetScrim = Color(0x991B1F3B),
)

private val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF14162B), ink = Color(0xFFECEBE4), muted = Color(0xFFA3A6B8), faint = Color(0xFF4A4E6A),
    track = Color(0xFF23264A), income = Color(0xFF4FC3CE), sheetScrim = Color(0xCC0A0B18),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun TiyinTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
        background = p.bg, surface = p.bg, onBackground = p.ink, onSurface = p.ink,
        primary = p.ink, onPrimary = p.bg, surfaceContainerLow = p.bg,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
