package app.cairn.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Le dessin d'un parcours, débarrassé de tout ce qui permettrait de le situer.
 *
 * L'idée : ce qui rend une sortie belle à regarder, c'est sa *forme* — la
 * boucle, le aller-retour, le zigzag en lacets. Pas ses coordonnées. On peut
 * donc garder l'un en jetant l'autre.
 *
 * Quatre opérations rendent le résultat non localisable :
 *
 *  1. **Aucune origine.** On n'enregistre jamais un point absolu. On accumule
 *     des déplacements successifs en mètres ; à tout instant la mémoire ne
 *     contient que le point précédent, écrasé au suivant. Même un vidage de la
 *     RAM ne livrerait pas de position de départ.
 *  2. **Rotation aléatoire.** Un angle tiré au hasard à la fin de la session.
 *     L'orientation nord-sud, qui suffirait à recaler la trace sur un réseau
 *     routier, disparaît.
 *  3. **Perte d'échelle.** La forme est ramenée à une boîte unitaire. Un tour
 *     de pâté de maisons et une étape de 80 km donnent le même cadre.
 *  4. **Simplification et bruit.** Douglas-Peucker ramène à quelques dizaines
 *     de points, puis un léger jitter casse la correspondance exacte avec la
 *     géométrie réelle des rues.
 *
 * Il reste une image partageable, et rien d'exploitable.
 */
object ShapeArt {

    private const val MAX_POINTS = 64
    private const val JITTER = 0.012f
    private const val INITIAL_CAPACITY = 512
    private const val MAX_RAW_POINTS = 4_000
    private const val MIN_RAW_POINTS = 8
    private const val MIN_SHAPE_POINTS = 6

    /** En dessous de 50 m d'amplitude, il n'y a pas de forme à dessiner. */
    private const val MIN_EXTENT_M = 50.0

    /** Tolérance Douglas-Peucker, proportionnelle à la taille du parcours. */
    private const val SIMPLIFY_RATIO = 0.004

    private const val METERS_PER_DEG_LAT = 110_540.0
    private const val METERS_PER_DEG_LON_EQUATOR = 111_320.0
    private const val STRAIGHT_ANGLE = 180.0
    private const val FULL_TURN = 2.0

    /** La forme est ramenée dans [-1, 1], soit une boîte de côté 2. */
    private const val UNIT_BOX = 2.0

    private const val HALF = 0.5f

    /** En dessous de trois points, il n'y a pas de sommet à simplifier. */
    private const val MIN_POLYLINE = 3

    private const val EPSILON = 1e-6
    private const val TINY = 1e-9

    /** Bruit final, centré sur zéro, d'amplitude [JITTER]. */
    private fun jitter(random: Random): Float = (random.nextFloat() - HALF) * JITTER

    /**
     * Accumulateur de forme. Vit uniquement en RAM, uniquement pendant une
     * session, uniquement si l'utilisateur a activé l'option.
     */
    class Recorder {
        // Le point précédent, écrasé à chaque nouvelle mesure. Jamais lu ailleurs.
        private var prevLat = Double.NaN
        private var prevLon = Double.NaN

        // Position courante en mètres, relative à un départ arbitraire (0,0).
        private var cursorX = 0.0
        private var cursorY = 0.0

        private val offsets = ArrayList<Pair<Double, Double>>(INITIAL_CAPACITY)

        /** Nombre de points bruts retenus (toujours des offsets, jamais des coordonnées). */
        val size: Int get() = offsets.size

        fun add(latDeg: Double, lonDeg: Double) {
            if (!prevLat.isNaN()) {
                val mPerDegLat = METERS_PER_DEG_LAT
                val mPerDegLon = METERS_PER_DEG_LON_EQUATOR * cos(latDeg * PI / STRAIGHT_ANGLE)
                cursorX += (lonDeg - prevLon) * mPerDegLon
                cursorY += (latDeg - prevLat) * mPerDegLat
                offsets += cursorX to cursorY
                if (offsets.size > MAX_RAW_POINTS) decimate()
            } else {
                offsets += 0.0 to 0.0
            }
            // On écrase immédiatement : la mémoire ne retient jamais deux points absolus.
            prevLat = latDeg
            prevLon = lonDeg
        }

        private fun decimate() {
            val kept = ArrayList<Pair<Double, Double>>(offsets.size / 2 + 1)
            for (i in offsets.indices) if (i % 2 == 0) kept += offsets[i]
            offsets.clear()
            offsets.addAll(kept)
        }

        /**
         * Produit la forme anonymisée puis efface tout état interne.
         * Renvoie null si le parcours est trop court pour dessiner quoi que ce soit.
         */
        fun build(random: Random = Random.Default): Shape? {
            val result = anonymize(offsets, random)
            // Effacement explicite : plus rien d'exploitable ne survit à l'appel.
            offsets.clear()
            prevLat = Double.NaN
            prevLon = Double.NaN
            cursorX = 0.0
            cursorY = 0.0
            return result
        }
    }

    /** Applique simplification, rotation aléatoire, normalisation et bruit. */
    fun anonymize(raw: List<Pair<Double, Double>>, random: Random = Random.Default): Shape? {
        val simplified = simplify(raw)
        return if (simplified.isEmpty()) null else normalize(rotate(simplified, random), random)
    }

    /**
     * Étape 1 : réduit la trace à ses sommets significatifs. Liste vide si le
     * parcours est trop court ou trop ramassé pour dessiner quoi que ce soit.
     */
    private fun simplify(raw: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (raw.size < MIN_RAW_POINTS) return emptyList()
        val extent = extentOf(raw)
        if (extent < MIN_EXTENT_M) return emptyList()

        var pts = douglasPeucker(raw, tolerance = extent * SIMPLIFY_RATIO)
        if (pts.size > MAX_POINTS) pts = evenlySample(pts, MAX_POINTS)
        return if (pts.size < MIN_SHAPE_POINTS) emptyList() else pts
    }

    /** Étape 2 : rotation d'un angle tiré au hasard. L'orientation réelle est perdue. */
    private fun rotate(
        pts: List<Pair<Double, Double>>,
        random: Random,
    ): List<Pair<Double, Double>> {
        val theta = random.nextDouble(0.0, FULL_TURN * PI)
        val cosT = cos(theta)
        val sinT = sin(theta)
        return pts.map { (x, y) -> (x * cosT - y * sinT) to (x * sinT + y * cosT) }
    }

    /** Étapes 3 et 4 : recentrage, perte d'échelle, puis bruit final. */
    private fun normalize(rotated: List<Pair<Double, Double>>, random: Random): Shape? {
        val minX = rotated.minOf { it.first }
        val maxX = rotated.maxOf { it.first }
        val minY = rotated.minOf { it.second }
        val maxY = rotated.maxOf { it.second }
        val span = max(maxX - minX, maxY - minY)
        if (span <= EPSILON) return null

        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        return Shape(
            rotated.map { (x, y) ->
                val nx = ((x - cx) / span * UNIT_BOX).toFloat() + jitter(random)
                val ny = ((y - cy) / span * UNIT_BOX).toFloat() + jitter(random)
                nx to ny
            }
        )
    }

    private fun extentOf(pts: List<Pair<Double, Double>>): Double {
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        return max(maxX - minX, maxY - minY)
    }

    /** Ramène une polyligne à ses sommets significatifs. */
    private fun douglasPeucker(
        pts: List<Pair<Double, Double>>,
        tolerance: Double,
    ): List<Pair<Double, Double>> {
        if (pts.size < MIN_POLYLINE) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true

        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to pts.size - 1)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            if (last <= first + 1) continue
            var maxDist = 0.0
            var index = first
            for (i in (first + 1) until last) {
                val d = perpendicularDistance(pts[i], pts[first], pts[last])
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (maxDist > tolerance) {
                keep[index] = true
                stack.addLast(first to index)
                stack.addLast(index to last)
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun perpendicularDistance(
        p: Pair<Double, Double>,
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
    ): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len = hypot(dx, dy)
        if (len < TINY) return hypot(p.first - a.first, p.second - a.second)
        return abs(dy * p.first - dx * p.second + b.first * a.second - b.second * a.first) / len
    }

    private fun evenlySample(pts: List<Pair<Double, Double>>, target: Int): List<Pair<Double, Double>> {
        if (pts.size <= target) return pts
        val step = (pts.size - 1).toDouble() / (target - 1)
        return (0 until target).map { i -> pts[(i * step).toInt().coerceAtMost(pts.size - 1)] }
    }
}
