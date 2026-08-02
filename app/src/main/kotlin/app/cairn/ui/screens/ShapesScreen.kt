package app.cairn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.domain.Mode
import app.cairn.domain.Session
import app.cairn.domain.Shape
import app.cairn.domain.ShapeArt
import app.cairn.ui.CairnViewModel
import app.cairn.ui.Fmt
import app.cairn.ui.components.Panel
import app.cairn.ui.components.SectionLabel
import app.cairn.ui.components.ShapeCanvas
import app.cairn.ui.theme.Stone
import app.cairn.ui.theme.color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * La galerie de formes : l'équivalent Cairn du partage de trace Strava.
 *
 * On garde ce qui rend une sortie belle — son dessin — et on jette ce qui la
 * rend dangereuse — son emplacement, son orientation, son échelle.
 */
@Composable
fun ShapesScreen(data: CairnViewModel.Snapshot, keepShapes: Boolean) {

    val demo = remember { demoShapes() }
    val hasReal = data.shapes.isNotEmpty()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 18.dp, 16.dp, 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Column(Modifier.padding(bottom = 6.dp)) {
                Text("Formes", color = Stone.Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (hasReal) "vos parcours, sans leur emplacement"
                    else "à quoi ressemble un parcours anonymisé",
                    color = Stone.Faint, fontSize = 11.sp,
                )
            }
        }

        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Panel(accent = Stone.Ochre) {
                SectionLabel("Ce qui a été retiré")
                Spacer(Modifier.height(12.dp))
                Bullet("l'origine — aucune position absolue n'a jamais été gardée")
                Bullet("l'orientation — chaque forme est pivotée d'un angle tiré au hasard")
                Bullet("l'échelle — un tour du quartier et une étape de 80 km ont le même cadre")
                Bullet("le détail — la trace est réduite à quelques dizaines de points, puis bruitée")
                Spacer(Modifier.height(12.dp))
                Text(
                    "Il reste une image que vous pouvez montrer à qui vous voulez, " +
                        "sans lui apprendre où vous habitez.",
                    color = Stone.Muted, fontSize = 12.sp,
                )
            }
        }

        if (!hasReal) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Panel {
                    Text(
                        if (keepShapes)
                            "Les formes sont activées. Elles apparaîtront après vos prochains " +
                                "déplacements mesurés avec la localisation."
                        else
                            "Les formes sont désactivées, comme tout ce qui touche à la " +
                                "localisation. Vous pouvez les activer dans Confidentialité — " +
                                "ci-dessous, des exemples générés pour vous montrer le rendu.",
                        color = Stone.Muted, fontSize = 13.sp,
                    )
                }
            }
        }

        val entries: List<Pair<Shape, String>> = if (hasReal) {
            data.shapes.map { s: Session -> s.shape!! to shapeCaption(s) }
        } else {
            demo.mapIndexed { i, s -> s to listOf("exemple", "exemple", "exemple", "exemple")[i % 4] }
        }
        val colors = listOf(Mode.FOOT, Mode.BIKE, Mode.RUN, Mode.SCOOTER)

        items(entries.size) { index ->
            val (shape, caption) = entries[index]
            val color = if (hasReal) data.shapes[index].mode.color()
            else colors[index % colors.size].color()
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Stone.Surface)
                    .border(1.dp, Stone.Hairline, RoundedCornerShape(16.dp))
                    .padding(10.dp)
            ) {
                ShapeCanvas(
                    shape = shape,
                    color = color,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                Spacer(Modifier.height(6.dp))
                Text(caption, color = Stone.Faint, fontSize = 10.sp)
            }
        }
    }
}

private fun shapeCaption(s: Session): String =
    "${s.mode.label} · ${Fmt.km(s.distanceM)} km · ${Fmt.dayLabel(s.day)}"

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("—", color = Stone.Ochre, fontSize = 13.sp)
        Spacer(Modifier.padding(horizontal = 5.dp))
        Text(text, color = Stone.Muted, fontSize = 13.sp)
    }
}

/**
 * Quelques formes synthétiques pour illustrer l'écran avant le premier trajet.
 * Elles passent par le même pipeline d'anonymisation que les vraies.
 */
private fun demoShapes(): List<Shape> {
    val rnd = Random(DEMO_SEED)
    fun make(builder: (Double) -> Pair<Double, Double>): Shape {
        val raw = (0..DEMO_SAMPLES).map { i -> builder(i / DEMO_SAMPLES.toDouble() * FULL_TURN * PI) }
        return ShapeArt.anonymize(raw, rnd) ?: Shape(emptyList())
    }
    // Quatre silhouettes de trajets courants, décrites en coordonnées polaires.
    // Les nombres sont des amplitudes en mètres : elles disparaissent de toute
    // façon à la normalisation, seule la forme relative compte.
    return listOf(
        // boucle irrégulière, typique d'une sortie en ville
        make { t ->
            val r = LOOP_RADIUS_M
            (r * cos(t) * (1 + LOOP_WOBBLE_A * sin(HARMONIC_3 * t))) to
                (r * sin(t) * (1 + LOOP_WOBBLE_B * cos(HARMONIC_2 * t)))
        },
        // aller-retour, typique d'un trajet domicile-travail
        make { t ->
            (COMMUTE_LENGTH_M * sin(t)) to
                (COMMUTE_DRIFT_M * sin(HARMONIC_2 * t) + COMMUTE_JIGGLE_M * sin(HARMONIC_5 * t))
        },
        // lacets de montée
        make { t -> (SWITCHBACK_WIDTH_M * sin(HARMONIC_6 * t)) to (t * SWITCHBACK_CLIMB_M) },
        // grande boucle avec un détour
        make { t ->
            val r = BIG_LOOP_RADIUS_M
            val d = BIG_LOOP_DETOUR_M
            (r * cos(t) + d * cos(HARMONIC_5 * t)) to (r * sin(t) + d * sin(HARMONIC_4 * t))
        },
    ).filter { it.points.isNotEmpty() }
}

// Harmoniques utilisées pour donner du relief aux silhouettes de démonstration.
private const val HARMONIC_2 = 2
private const val HARMONIC_3 = 3
private const val HARMONIC_4 = 4
private const val HARMONIC_5 = 5
private const val HARMONIC_6 = 6

private const val DEMO_SEED = 7
private const val DEMO_SAMPLES = 220
private const val FULL_TURN = 2

private const val LOOP_RADIUS_M = 1_400.0
private const val LOOP_WOBBLE_A = 0.22
private const val LOOP_WOBBLE_B = 0.18
private const val COMMUTE_LENGTH_M = 2_600.0
private const val COMMUTE_DRIFT_M = 260.0
private const val COMMUTE_JIGGLE_M = 90.0
private const val SWITCHBACK_WIDTH_M = 700.0
private const val SWITCHBACK_CLIMB_M = 420.0
private const val BIG_LOOP_RADIUS_M = 1_800.0
private const val BIG_LOOP_DETOUR_M = 380.0
