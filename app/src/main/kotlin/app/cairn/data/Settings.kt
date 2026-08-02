package app.cairn.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Préférences locales.
 *
 * Principe de conception : **tout ce qui augmente la collecte est désactivé par
 * défaut.** Cairn démarre dans son état le plus discret possible — podomètre et
 * baromètre seulement, aucune permission de localisation demandée — et
 * l'utilisateur ouvre les vannes une par une, en sachant à chaque fois ce qu'il
 * échange contre quoi.
 *
 * C'est l'inverse de l'usage : la plupart des applications activent tout à
 * l'installation et laissent l'utilisateur découvrir les réglages plus tard.
 */
class Settings private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("cairn_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state

    data class Snapshot(
        /** Mesurer les distances en véhicule via le GPS. Désactivé par défaut. */
        val useGps: Boolean,
        /** Conserver la forme anonymisée des parcours. Désactivé par défaut. */
        val keepShapes: Boolean,
        /** Arrondir l'heure des sessions. Activé par défaut : les horaires identifient. */
        val quantizeTime: Boolean,
        /** Longueur de foulée en mètres, pour la distance à pied sans GPS. */
        val strideM: Float,
    )

    private fun read() = Snapshot(
        useGps = prefs.getBoolean(KEY_GPS, false),
        keepShapes = prefs.getBoolean(KEY_SHAPES, false),
        quantizeTime = prefs.getBoolean(KEY_QUANTIZE, true),
        strideM = prefs.getFloat(KEY_STRIDE, DEFAULT_STRIDE_M),
    )

    fun setUseGps(v: Boolean) = put { putBoolean(KEY_GPS, v) }
    fun setKeepShapes(v: Boolean) = put { putBoolean(KEY_SHAPES, v) }
    fun setQuantizeTime(v: Boolean) = put { putBoolean(KEY_QUANTIZE, v) }
    fun setStride(v: Float) = put { putFloat(KEY_STRIDE, v.coerceIn(MIN_STRIDE_M, MAX_STRIDE_M)) }

    private inline fun put(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    companion object {
        private const val KEY_GPS = "use_gps"
        private const val KEY_SHAPES = "keep_shapes"
        private const val KEY_QUANTIZE = "quantize_time"
        private const val KEY_STRIDE = "stride_m"

        /** Longueur de foulée moyenne d'un adulte, en mètres. */
        private const val DEFAULT_STRIDE_M = 0.72f
        private const val MIN_STRIDE_M = 0.4f
        private const val MAX_STRIDE_M = 1.2f

        @Volatile
        private var instance: Settings? = null

        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(context).also { instance = it }
            }
    }
}
