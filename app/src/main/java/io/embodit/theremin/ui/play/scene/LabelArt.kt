package io.embodit.theremin.ui.play.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import io.embodit.theremin.domain.model.Instrument
import io.embodit.theremin.domain.model.Octave
import io.embodit.theremin.ui.theme.ThColors

/**
 * Bitmaps drawn at run time for 3D text (ripple labels, the hand tag's note, the arrow, the name plate).
 * Drawn the normal way up: the quads give their top edge v = 1 (`MeshBuilder.quadUv`), which is the bitmap's
 * top row on this engine. The debug key `labeltest=1` swaps every label for an asymmetric "F↑" so that QA can
 * see any mirroring or flipping at a glance.
 */
internal object LabelArt {
    const val W = 256
    const val H = 128

    private val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    /** Ripple label / hand tag of semitone [n]: "mi" (Chinese UI) or "E" (English); C carries its octave. */
    fun noteText(n: Int, english: Boolean, octave: Octave): Pair<String, String?> {
        val c = ((n % 12) + 12) % 12 == 0
        val main = if (english) (if (c) Instrument.letter(n, octave) else Instrument.letterOnly(n)) else Instrument.solfege(n)
        val sub = if (!english && c) Instrument.letter(n, octave) else null
        return main to sub
    }

    fun label(main: String, sub: String?, test: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThColors.LabelInk.copy(alpha = 0.78f).toArgb() }
        cv.drawRoundRect(RectF(4f, 4f, W - 4f, H - 4f), 40f, 40f, plate)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThColors.RippleIvory.toArgb()
            typeface = bold
            textAlign = Paint.Align.CENTER
        }
        val text = if (test) "F↑" else main
        if (sub == null || test) {
            ink.textSize = 78f
            cv.drawText(text, W / 2f, H / 2f + 27f, ink)
        } else {
            ink.textSize = 64f
            cv.drawText(text, W / 2f, 66f, ink)
            ink.textSize = 34f
            ink.color = ThColors.BrassHighlight.toArgb()
            cv.drawText(sub, W / 2f, 108f, ink)
        }
        return bmp
    }

    /** An upward arrow (the down arrow is the same quad turned 180 degrees about its normal: no mirroring). */
    fun arrow(): Bitmap {
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThColors.RippleIvory.toArgb() }
        val path = Path().apply {
            moveTo(64f, 10f)
            lineTo(116f, 70f)
            lineTo(82f, 70f)
            lineTo(82f, 118f)
            lineTo(46f, 118f)
            lineTo(46f, 70f)
            lineTo(12f, 70f)
            close()
        }
        cv.drawPath(path, p)
        return bmp
    }

    /** The small brass name plate on the cabinet front. */
    fun namePlate(): Bitmap {
        val bmp = Bitmap.createBitmap(256, 64, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(ThColors.Brass.toArgb())
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThColors.WalnutDark.toArgb()
            typeface = bold
            textAlign = Paint.Align.CENTER
            textSize = 26f
        }
        cv.drawText("空中琴", 128f, 28f, ink)
        ink.textSize = 18f
        ink.letterSpacing = 0.2f
        cv.drawText("AIR THEREMIN", 128f, 54f, ink)
        return bmp
    }
}
