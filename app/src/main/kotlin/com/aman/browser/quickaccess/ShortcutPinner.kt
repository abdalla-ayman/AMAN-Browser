package com.aman.browser.quickaccess

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Pins a [WebApp] to the device launcher so the user can tap its icon and
 * jump straight into its standalone WebAppActivity – exactly like opening a
 * native app.
 */
object ShortcutPinner {

    fun pin(context: Context, webApp: WebApp) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, "Launcher does not support pinning", Toast.LENGTH_SHORT).show()
            return
        }

        val icon = buildIcon(context, webApp)
        val launchIntent = WebAppActivity.launchIntent(context, webApp)

        val shortcut = ShortcutInfoCompat.Builder(context, "webapp_${webApp.id}")
            .setShortLabel(webApp.name)
            .setLongLabel(webApp.name)
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()

        val callback = PendingIntent.getBroadcast(
            context, 0,
            android.content.Intent("com.aman.browser.PIN_RESULT"),
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0)
                or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, callback.intentSender)
    }

    private fun buildIcon(context: Context, webApp: WebApp): IconCompat {
        // Use the bundled vector when available; otherwise generate a circular
        // letter avatar tinted with the app's accent colour.
        if (webApp.iconRes != 0) {
            return IconCompat.createWithResource(context, webApp.iconRes)
        }
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val accent = try { Color.parseColor(webApp.colorHex) } catch (_: Exception) { Color.DKGRAY }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.5f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        val letter = webApp.letter.take(1).ifEmpty { "?" }
        textPaint.getTextBounds(letter, 0, letter.length, bounds)
        val y = size / 2f - bounds.exactCenterY()
        canvas.drawText(letter, size / 2f, y, textPaint)

        return IconCompat.createWithBitmap(bmp)
    }
}
