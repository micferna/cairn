package app.cairn.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong

/** Mise en forme à la française : virgule décimale, espace fine pour les milliers. */
object Fmt {

    /** Espace fine insécable : le séparateur de milliers typographique français. */
    private const val NARROW_NBSP = ' '

    private const val GROUP_SIZE = 3
    private const val METERS_PER_KM = 1_000.0
    private const val KM_PRECISION_LIMIT_M = 100_000.0
    private const val TWO_DECIMALS_LIMIT_M = 10_000.0
    private const val SECONDS_PER_HOUR = 3_600
    private const val SECONDS_PER_MINUTE = 60
    private const val MS_TO_KMH = 3.6
    private const val GRAMS_PER_KG = 1_000.0
    private const val GRAMS_PER_TONNE = 1_000_000.0
    private const val BYTES_PER_KIB = 1_024.0
    private const val BYTES_PER_MIB = BYTES_PER_KIB * BYTES_PER_KIB

    fun int(v: Long): String {
        val digits = abs(v).toString()
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % GROUP_SIZE == 0) sb.append(NARROW_NBSP)
            sb.append(c)
        }
        return (if (v < 0) "−" else "") + sb
    }

    fun int(v: Int): String = int(v.toLong())

    /** Distance adaptative : mètres en dessous du kilomètre, sinon kilomètres. */
    fun distance(meters: Double): Pair<String, String> = when {
        meters < METERS_PER_KM -> int(meters.roundToLong()) to "m"
        meters < KM_PRECISION_LIMIT_M -> decimal(meters / METERS_PER_KM, 1) to "km"
        else -> int((meters / METERS_PER_KM).roundToLong()) to "km"
    }

    fun km(meters: Double): String =
        if (meters < TWO_DECIMALS_LIMIT_M) decimal(meters / METERS_PER_KM, 2)
        else decimal(meters / METERS_PER_KM, 1)

    fun duration(seconds: Long): String {
        val h = seconds / SECONDS_PER_HOUR
        val m = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val s = seconds % SECONDS_PER_MINUTE
        return when {
            h > 0 -> "%dh%02d".format(h, m)
            m > 0 -> "%d min".format(m)
            else -> "%d s".format(s)
        }
    }

    fun speedKmh(ms: Double): String = decimal(ms * MS_TO_KMH, 1)

    fun co2(grams: Double): Pair<String, String> = when {
        grams < GRAMS_PER_KG -> int(grams.roundToLong()) to "g"
        grams < GRAMS_PER_TONNE -> decimal(grams / GRAMS_PER_KG, 1) to "kg"
        else -> decimal(grams / GRAMS_PER_TONNE, 1) to "t"
    }

    fun bytes(n: Long): String = when {
        n < BYTES_PER_KIB -> "$n o"
        n < BYTES_PER_MIB -> "${decimal(n / BYTES_PER_KIB, 1)} ko"
        else -> "${decimal(n / BYTES_PER_MIB, 1)} Mo"
    }

    private val WEEKDAYS = listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim")

    fun weekdayShort(isoDay: String): String = runCatching {
        WEEKDAYS[LocalDate.parse(isoDay).dayOfWeek.value - 1]
    }.getOrDefault("")

    fun dayLabel(isoDay: String): String = runCatching {
        val d = LocalDate.parse(isoDay)
        val today = LocalDate.now()
        when (d) {
            today -> "aujourd'hui"
            today.minusDays(1) -> "hier"
            else -> "%s %02d/%02d".format(weekdayShort(isoDay), d.dayOfMonth, d.monthValue)
        }
    }.getOrDefault(isoDay)

    fun clockMs(ms: Long): String = runCatching {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()
        "%02d/%02d %02d:%02d:%02d".format(
            t.dayOfMonth, t.monthValue, t.hour, t.minute, t.second,
        )
    }.getOrDefault("")

    /** Arrondi à [places] décimales, avec la virgule française. */
    private fun decimal(value: Double, places: Int): String =
        "%.${places}f".format(value).replace('.', ',')
}
