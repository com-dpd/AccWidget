package com.dp.accwidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** iOS-style horizontal battery with SoC fill and resume/pause ticks (no % text). */
object BatteryGlyph {
    fun draw(
        capacity: Int,
        resumePct: Int,
        pausePct: Int,
        widthPx: Int = 160,
        heightPx: Int = 64,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(64)
        val h = heightPx.coerceAtLeast(28)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val padY = h * 0.12f
        val tipW = (w * 0.055f).coerceAtLeast(3f)
        val tipGap = tipW * 0.35f
        val strokeW = (h * 0.07f).coerceIn(2f, 5f)
        val bodyLeft = padY
        val bodyRight = w - padY - tipW - tipGap
        val body = RectF(bodyLeft, padY, bodyRight, h - padY)
        val radius = body.height() * 0.32f

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = 0xE6FFFFFF.toInt()
        }
        val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xE6FFFFFF.toInt()
        }

        val tipH = body.height() * 0.38f
        val tipCy = body.centerY()
        val tipRect = RectF(
            body.right + tipGap,
            tipCy - tipH / 2f,
            body.right + tipGap + tipW,
            tipCy + tipH / 2f,
        )
        c.drawRoundRect(tipRect, tipW * 0.4f, tipW * 0.4f, tip)
        c.drawRoundRect(body, radius, radius, stroke)

        val pct = capacity.coerceIn(0, 100)
        val fillColor = when {
            pct >= 50 -> 0xFF34C759.toInt()
            pct >= 20 -> 0xFFFFCC00.toInt()
            else -> 0xFFFF3B30.toInt()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }

        val inset = strokeW * 1.35f
        val inner = RectF(
            body.left + inset,
            body.top + inset,
            body.right - inset,
            body.bottom - inset,
        )
        val innerRadius = (inner.height() * 0.28f).coerceAtLeast(2f)
        if (pct > 0) {
            val fillRight = inner.left + inner.width() * (pct / 100f)
            val fillRect = RectF(inner.left, inner.top, fillRight.coerceAtLeast(inner.left + 1f), inner.bottom)
            c.drawRoundRect(fillRect, innerRadius, innerRadius, fill)
        }

        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW * 0.7f
            color = 0xAAFFFFFF.toInt()
        }
        fun drawTick(p: Int) {
            val f = p.coerceIn(0, 100) / 100f
            val x = body.left + body.width() * f
            val tickLen = body.height() * 0.22f
            c.drawLine(x, body.top - padY * 0.15f, x, body.top + tickLen, tick)
            c.drawLine(x, body.bottom - tickLen, x, body.bottom + padY * 0.15f, tick)
        }
        drawTick(resumePct)
        drawTick(pausePct)
        return bmp
    }
}
