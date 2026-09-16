package app.auxparty.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Synthetic album covers for screenshot tests. Drawn in code so no copyrighted
 * artwork enters the repository, but varied enough to exercise the colour engine.
 */
object SampleArtwork {
    private const val SIZE = 600

    /** Warm: dusk sky with a low sun. */
    fun sunset(): Bitmap = draw { c, p ->
        p.shader = LinearGradient(0f, 0f, 0f, SIZE.toFloat(), intArrayOf(0xFF2B1055.toInt(), 0xFFD6246E.toInt(), 0xFFFF8A3D.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), p)
        p.shader = RadialGradient(300f, 380f, 150f, intArrayOf(0xFFFFE08A.toInt(), 0xFFFFB347.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(300f, 380f, 130f, p)
        p.shader = null
        p.color = 0xFF1A0B2E.toInt()
        c.drawRect(0f, 440f, SIZE.toFloat(), SIZE.toFloat(), p)
    }

    /** Cool: deep water with pale bands. */
    fun ocean(): Bitmap = draw { c, p ->
        p.shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), intArrayOf(0xFF03363D.toInt(), 0xFF0B7A75.toInt(), 0xFF7FD1B9.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 18f
        p.color = 0x55E8FFF7
        for (i in 0 until 7) c.drawCircle(420f, 180f, 60f + i * 55f, p)
    }

    private fun draw(block: (Canvas, Paint) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        block(Canvas(bitmap), Paint(Paint.ANTI_ALIAS_FLAG))
        return bitmap
    }
}
