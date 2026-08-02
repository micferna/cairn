package app.cairn.domain

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * L'image qu'on partage.
 *
 * ============================================================================
 * POURQUOI CETTE FONCTIONNALITÉ EST COHÉRENTE AVEC LE RESTE
 * ============================================================================
 *
 * Le moteur de croissance de Strava, c'est la trace qu'on montre à ses amis —
 * et c'est aussi ce qui publie votre domicile. Cairn peut garder le premier
 * sans le second : la forme partagée a déjà été recentrée, pivotée d'un angle
 * aléatoire, privée d'échelle, simplifiée et bruitée. Il n'en reste qu'un
 * dessin.
 *
 * Autrement dit : on peut partager un souvenir sans partager une adresse. C'est
 * exactement l'argument du produit, rendu visible par une image.
 *
 * L'image porte la marque et l'adresse du dépôt. Ce n'est pas un filigrane
 * subi : c'est le message. Quelqu'un qui voit passer ce dessin doit pouvoir
 * comprendre en une ligne ce que fait l'application et où la trouver.
 *
 * Aucun réseau n'est impliqué ici. On produit un fichier PNG, on le remet au
 * système via un `ACTION_SEND`, et c'est l'application choisie par
 * l'utilisateur — messagerie, réseau social, mail — qui s'occupe de l'envoi.
 * Cairn n'a toujours aucun moyen de parler à l'extérieur.
 */
object ShareCard {

    const val WIDTH = 1080
    const val HEIGHT = 1350

    private const val VOID = 0xFF0B0E0D.toInt()
    private const val SURFACE = 0xFF141917.toInt()
    private const val HAIRLINE = 0xFF263029.toInt()
    private const val INK = 0xFFE8EDE9.toInt()
    private const val MUTED = 0xFF8B968F.toInt()
    private const val FAINT = 0xFF5A6660.toInt()
    private const val OCHRE = 0xFFD98B4E.toInt()
    private const val STONE_LIT = 0xFFE5A268.toInt()
    private const val STONE_TOP = 0xFFF0C08C.toInt()

    // --- Métriques de mise en page ------------------------------------------
    // La carte est dessinée à taille fixe (1080 × 1350, le format vertical que
    // tous les réseaux acceptent sans recadrer), donc en pixels absolus.
    private const val MARGIN = 84f
    private const val CONTOUR_COUNT = 7
    private const val CONTOUR_STROKE = 2f
    private const val CONTOUR_TOP_RATIO = 0.62f
    private const val CONTOUR_GAP = 46f
    private const val CONTOUR_BLEED = 40f
    private const val CONTOUR_RISE = 70f
    private const val CONTOUR_DIP = 60f
    private const val CONTOUR_FALL = 30f
    private const val CONTOUR_CP1 = 0.25f
    private const val CONTOUR_CP2 = 0.55f

    private const val TOP_OFFSET = 40f
    private const val SHAPE_MAX = 520f
    private const val SHAPE_INSET = 0.86f
    private const val SHAPE_RADIUS = 40f
    private const val SHAPE_STROKE = 7f
    private const val SHAPE_DOT = 13f
    private const val SHAPE_GAP = 64f
    private const val HALF = 2f

    private const val HEADLINE_SIZE = 156f
    private const val UNIT_SIZE = 46f
    private const val CAPTION_SIZE = 38f
    private const val HEADLINE_BASELINE = 120f
    private const val UNIT_GAP = 16f
    private const val CAPTION_BASELINE = 176f
    private const val HEADLINE_BLOCK = 232f

    private const val EQ_HEIGHT = 128f
    private const val EQ_RADIUS = 28f
    private const val EQ_STROKE = 2f
    private const val EQ_PAD = 36f
    private const val EQ_LABEL_SIZE = 26f
    private const val EQ_LABEL_SPACING = 0.14f
    private const val EQ_VALUE_SIZE = 42f
    private const val EQ_LABEL_BASELINE = 48f
    private const val EQ_VALUE_BASELINE = 100f
    private const val EQ_BLOCK = 184f
    private const val EQ_BORDER_ALPHA = 0x66FFFFFF

    private const val STAT_VALUE_SIZE = 54f
    private const val STAT_LABEL_SIZE = 28f
    private const val STAT_VALUE_BASELINE = 56f
    private const val STAT_LABEL_BASELINE = 100f

    private const val BRAND_GLYPH_OFFSET = 42f
    private const val BRAND_TEXT_X = 78f
    private const val BRAND_NAME_SIZE = 46f
    private const val BRAND_NAME_SPACING = 0.16f
    private const val BRAND_TAGLINE_SIZE = 27f
    private const val BRAND_URL_SIZE = 24f
    private const val BRAND_NAME_BASELINE = 52f
    private const val BRAND_TAGLINE_BASELINE = 14f
    private const val BRAND_URL_BASELINE = 22f
    private const val STONE_W1 = 62f
    private const val STONE_H1 = 18f
    private const val STONE_W2 = 50f
    private const val STONE_H2 = 16f
    private const val STONE_W3 = 38f
    private const val STONE_H3 = 14f
    private const val STONE_W4 = 24f
    private const val STONE_H4 = 12f
    private const val STONE_CENTER = 31f
    private const val STONE_GAP = 6f
    private const val STONE_RADIUS_DIV = 2.4f

    /** Contenu d'une carte. Uniquement des nombres et du texte : aucun lieu. */
    data class Content(
        val headline: String,
        val unit: String,
        val caption: String,
        val equivalence: String?,
        val stats: List<Pair<String, String>>,
        val shape: Shape?,
        val accent: Int = OCHRE,
    )

    fun render(content: Content): Bitmap {
        val bmp = createBitmap(WIDTH, HEIGHT)
        val c = Canvas(bmp)
        c.drawColor(VOID)

        drawContours(c)
        var y = MARGIN + TOP_OFFSET
        y = drawShape(c, content, y)
        y = drawHeadline(c, content, y)
        y = drawEquivalence(c, content, y)
        drawStats(c, content, y)
        drawBranding(c)
        return bmp
    }

    /** Courbes de niveau très discrètes : le motif du cairn, pas une carte. */
    private fun drawContours(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = CONTOUR_STROKE
            color = HAIRLINE
        }
        for (i in 0 until CONTOUR_COUNT) {
            val base = HEIGHT * CONTOUR_TOP_RATIO + i * CONTOUR_GAP
            val path = Path().apply {
                moveTo(-CONTOUR_BLEED, base)
                cubicTo(
                    WIDTH * CONTOUR_CP1, base - CONTOUR_RISE,
                    WIDTH * CONTOUR_CP2, base + CONTOUR_DIP,
                    WIDTH + CONTOUR_BLEED, base - CONTOUR_FALL,
                )
            }
            c.drawPath(path, p)
        }
    }

    private fun drawShape(c: Canvas, content: Content, top: Float): Float {
        val shape = content.shape ?: return top
        if (shape.points.size < 2) return top

        val box = WIDTH - MARGIN * 2
        val size = minOf(box, SHAPE_MAX)
        val cx = WIDTH / HALF
        val cy = top + size / HALF
        val scale = size / HALF * SHAPE_INSET

        val card = RectF(MARGIN, top, WIDTH - MARGIN, top + size)
        c.drawRoundRect(card, SHAPE_RADIUS, SHAPE_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE })

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = SHAPE_STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = content.accent
        }
        val path = Path()
        shape.points.forEachIndexed { i, (x, y) ->
            val px = cx + x * scale
            val py = cy - y * scale
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        c.drawPath(path, stroke)

        val (sx, sy) = shape.points.first()
        c.drawCircle(
            cx + sx * scale, cy - sy * scale, SHAPE_DOT,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = content.accent },
        )
        return top + size + SHAPE_GAP
    }

    private fun drawHeadline(c: Canvas, content: Content, top: Float): Float {
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = HEADLINE_SIZE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED
            textSize = UNIT_SIZE
            typeface = Typeface.SANS_SERIF
        }
        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED
            textSize = CAPTION_SIZE
            typeface = Typeface.SANS_SERIF
        }

        val headlineWidth = big.measureText(content.headline)
        c.drawText(content.headline, MARGIN, top + HEADLINE_BASELINE, big)
        c.drawText(content.unit, MARGIN + headlineWidth + UNIT_GAP, top + HEADLINE_BASELINE, unit)
        c.drawText(content.caption, MARGIN, top + CAPTION_BASELINE, caption)
        return top + HEADLINE_BLOCK
    }

    private fun drawEquivalence(c: Canvas, content: Content, top: Float): Float {
        val text = content.equivalence ?: return top
        val box = RectF(MARGIN, top, WIDTH - MARGIN, top + EQ_HEIGHT)
        c.drawRoundRect(box, EQ_RADIUS, EQ_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE })
        c.drawRoundRect(
            box, EQ_RADIUS, EQ_RADIUS,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = EQ_STROKE
                color = content.accent and EQ_BORDER_ALPHA
            },
        )
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FAINT
            textSize = EQ_LABEL_SIZE
            letterSpacing = EQ_LABEL_SPACING
            typeface = Typeface.SANS_SERIF
        }
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = EQ_VALUE_SIZE
            typeface = Typeface.SANS_SERIF
        }
        c.drawText("CE QUE ÇA REPRÉSENTE", MARGIN + EQ_PAD, top + EQ_LABEL_BASELINE, label)
        val available = WIDTH - MARGIN * HALF - EQ_PAD * HALF
        c.drawText(ellipsize(text, value, available), MARGIN + EQ_PAD, top + EQ_VALUE_BASELINE, value)
        return top + EQ_BLOCK
    }

    private fun drawStats(c: Canvas, content: Content, top: Float) {
        if (content.stats.isEmpty()) return
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = STAT_VALUE_SIZE
            typeface = Typeface.SANS_SERIF
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FAINT
            textSize = STAT_LABEL_SIZE
            typeface = Typeface.SANS_SERIF
        }
        val slot = (WIDTH - MARGIN * HALF) / content.stats.size
        content.stats.forEachIndexed { i, (v, l) ->
            val x = MARGIN + slot * i
            c.drawText(v, x, top + STAT_VALUE_BASELINE, value)
            c.drawText(l, x, top + STAT_LABEL_BASELINE, label)
        }
    }

    /**
     * La marque, en bas : un cairn dessiné, le nom, la promesse et l'adresse.
     * C'est ce bloc qui transforme un partage en présentation du produit.
     */
    private fun drawBranding(c: Canvas) {
        val baseline = HEIGHT - MARGIN
        drawCairnGlyph(c, MARGIN, baseline - BRAND_GLYPH_OFFSET)

        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = BRAND_NAME_SIZE
            letterSpacing = BRAND_NAME_SPACING
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val tagline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OCHRE
            textSize = BRAND_TAGLINE_SIZE
            typeface = Typeface.SANS_SERIF
        }
        val url = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FAINT
            textSize = BRAND_URL_SIZE
            typeface = Typeface.MONOSPACE
        }
        val x = MARGIN + BRAND_TEXT_X
        c.drawText("CAIRN", x, baseline - BRAND_NAME_BASELINE, name)
        c.drawText("Vos kilomètres, jamais vos lieux.", x, baseline - BRAND_TAGLINE_BASELINE, tagline)
        c.drawText("github.com/micferna/cairn", x, baseline + BRAND_URL_BASELINE, url)
    }

    /** Le logo : quatre pierres empilées, dessinées plutôt que chargées. */
    private fun drawCairnGlyph(c: Canvas, left: Float, bottom: Float) {
        // De la pierre de base au faîte : largeur, hauteur, teinte.
        val stones = listOf(
            Triple(STONE_W1, STONE_H1, OCHRE),
            Triple(STONE_W2, STONE_H2, STONE_LIT),
            Triple(STONE_W3, STONE_H3, OCHRE),
            Triple(STONE_W4, STONE_H4, STONE_TOP),
        )
        var y = bottom
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        stones.forEach { (w, h, color) ->
            paint.color = color
            val cx = left + STONE_CENTER
            c.drawRoundRect(
                RectF(cx - w / HALF, y - h, cx + w / HALF, y),
                h / STONE_RADIUS_DIV, h / STONE_RADIUS_DIV, paint,
            )
            y -= h + STONE_GAP
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var cut = text.length
        while (cut > 1 && paint.measureText(text.take(cut) + "…") > maxWidth) cut--
        return text.take(cut) + "…"
    }
}
