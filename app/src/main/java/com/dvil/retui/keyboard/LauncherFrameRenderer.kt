package com.dvil.retui.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import com.dvil.retui.contract.RetuiVisualContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class LauncherFrameRenderer(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    private var source: Bitmap? = null
    private var spec: FrameSpec? = null
    private val rendered = object : LruCache<RenderKey, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: RenderKey, value: Bitmap): Int = max(1, value.allocationByteCount / 1024)
    }
    init {
        reload()
    }

    fun accept(bundle: Bundle): Boolean {
        if (!bundle.containsKey(RetuiVisualContract.FRAME_AVAILABLE)) return false
        if (!bundle.getBoolean(RetuiVisualContract.FRAME_AVAILABLE, false)) {
            if (!prefs.getBoolean(KEY_ACTIVE, false) && !frameFile().exists()) return false
            prefs.edit().remove(KEY_ACTIVE).remove(KEY_ASSET_ID).apply()
            frameFile().delete()
            reload()
            return true
        }

        return try {
            val assetId = bundle.getString(RetuiVisualContract.FRAME_ASSET_ID)?.trim().orEmpty()
            val uri = bundle.getString(RetuiVisualContract.FRAME_IMAGE_URI)?.let(Uri::parse)
                ?: error("missing frame URI")
            val slices = FrameSpec(
                bundle.getInt(RetuiVisualContract.FRAME_SLICE_LEFT_PX),
                bundle.getInt(RetuiVisualContract.FRAME_SLICE_TOP_PX),
                bundle.getInt(RetuiVisualContract.FRAME_SLICE_RIGHT_PX),
                bundle.getInt(RetuiVisualContract.FRAME_SLICE_BOTTOM_PX),
                bundle.getFloat(RetuiVisualContract.FRAME_BORDER_LEFT_DP),
                bundle.getFloat(RetuiVisualContract.FRAME_BORDER_TOP_DP),
                bundle.getFloat(RetuiVisualContract.FRAME_BORDER_RIGHT_DP),
                bundle.getFloat(RetuiVisualContract.FRAME_BORDER_BOTTOM_DP),
                bundle.getString(RetuiVisualContract.FRAME_MODE_TOP).orEmpty(),
                bundle.getString(RetuiVisualContract.FRAME_MODE_RIGHT).orEmpty(),
                bundle.getString(RetuiVisualContract.FRAME_MODE_BOTTOM).orEmpty(),
                bundle.getString(RetuiVisualContract.FRAME_MODE_LEFT).orEmpty(),
                bundle.getString(RetuiVisualContract.FRAME_MODE_CENTER).orEmpty(),
                bundle.getString(RetuiVisualContract.FRAME_FILTERING).orEmpty()
            )
            require(assetId.isNotEmpty()) { "missing frame asset ID" }
            if (assetId == prefs.getString(KEY_ASSET_ID, null) && prefs.getInt(KEY_RENDER_VERSION, 0) == RENDER_VERSION && prefs.getBoolean(KEY_ACTIVE, false) && frameFile().isFile) {
                return false
            }

            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream(8192)
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_PNG_BYTES) { "frame PNG exceeds 4 MiB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("unable to open frame URI")
            require(bytes.size >= PNG_SIGNATURE.size && bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
                "frame is not PNG"
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            slices.validate(options.outWidth, options.outHeight)

            val temporary = File(context.filesDir, "$FRAME_FILE.tmp")
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            val destination = frameFile()
            if (destination.exists()) require(destination.delete()) { "unable to replace cached frame" }
            require(temporary.renameTo(destination)) { "unable to publish cached frame" }
            prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putString(KEY_ASSET_ID, assetId)
                .putInt(KEY_LEFT, slices.left)
                .putInt(KEY_TOP, slices.top)
                .putInt(KEY_RIGHT, slices.right)
                .putInt(KEY_BOTTOM, slices.bottom)
                .putFloat(KEY_BORDER_LEFT, slices.borderLeftDp)
                .putFloat(KEY_BORDER_TOP, slices.borderTopDp)
                .putFloat(KEY_BORDER_RIGHT, slices.borderRightDp)
                .putFloat(KEY_BORDER_BOTTOM, slices.borderBottomDp)
                .putString(KEY_MODE_TOP, slices.topMode)
                .putString(KEY_MODE_RIGHT, slices.rightMode)
                .putString(KEY_MODE_BOTTOM, slices.bottomMode)
                .putString(KEY_MODE_LEFT, slices.leftMode)
                .putString(KEY_MODE_CENTER, slices.centerMode)
                .putString(KEY_FILTERING, slices.filtering)
                .putInt(KEY_RENDER_VERSION, RENDER_VERSION)
                .apply()
            reload()
            true
        } catch (error: Exception) {
            Log.w(TAG, "Ignoring invalid Launcher frame: ${error.message}")
            if (prefs.getBoolean(KEY_ACTIVE, false)) prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
            reload()
            true
        }
    }

    fun reload() {
        source?.recycle()
        source = null
        spec = null
        rendered.evictAll()
        if (!prefs.getBoolean(KeyboardPrefs.KEY_ACCEPT_LAUNCHER_FRAMES, KeyboardPrefs.DEFAULT_ACCEPT_LAUNCHER_FRAMES)) return
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return
        val nextSpec = FrameSpec(
            prefs.getInt(KEY_LEFT, 0),
            prefs.getInt(KEY_TOP, 0),
            prefs.getInt(KEY_RIGHT, 0),
            prefs.getInt(KEY_BOTTOM, 0),
            prefs.getFloat(KEY_BORDER_LEFT, 0f),
            prefs.getFloat(KEY_BORDER_TOP, 0f),
            prefs.getFloat(KEY_BORDER_RIGHT, 0f),
            prefs.getFloat(KEY_BORDER_BOTTOM, 0f),
            prefs.getString(KEY_MODE_TOP, null).orEmpty(),
            prefs.getString(KEY_MODE_RIGHT, null).orEmpty(),
            prefs.getString(KEY_MODE_BOTTOM, null).orEmpty(),
            prefs.getString(KEY_MODE_LEFT, null).orEmpty(),
            prefs.getString(KEY_MODE_CENTER, null).orEmpty(),
            prefs.getString(KEY_FILTERING, null).orEmpty()
        )
        val bitmap = BitmapFactory.decodeFile(frameFile().absolutePath) ?: return
        try {
            nextSpec.validate(bitmap.width, bitmap.height)
            source = bitmap
            spec = nextSpec
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
        }
    }

    fun close() {
        source?.recycle()
        source = null
        spec = null
        rendered.evictAll()
    }

    fun drawable(fill: Int, pressedOverlay: Int? = null): Drawable? {
        if (source == null || spec == null) return null
        return CachedFrameDrawable(this, fill, pressedOverlay)
    }

    private fun render(width: Int, height: Int, fill: Int): Bitmap? {
        val source = source ?: return null
        val spec = spec ?: return null
        if (width <= 0 || height <= 0) return null
        val key = RenderKey(width, height, fill)
        rendered.get(key)?.let { return it }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(fill)
        val borders = spec.destinationBorders(width, height, context.resources.displayMetrics.density)
        val sx = intArrayOf(0, spec.left, source.width - spec.right, source.width)
        val sy = intArrayOf(0, spec.top, source.height - spec.bottom, source.height)
        val dx = intArrayOf(0, borders.left, width - borders.right, width)
        val dy = intArrayOf(0, borders.top, height - borders.bottom, height)
        val drawPaint = Paint().apply { isFilterBitmap = spec.filtering == "linear" }
        for (row in 0..2) for (column in 0..2) {
            val destination = Rect(dx[column], dy[row], dx[column + 1], dy[row + 1])
            if (!destination.isEmpty) {
                val mode = when {
                    row == 0 && column == 1 -> spec.topMode
                    row == 1 && column == 2 -> spec.rightMode
                    row == 2 && column == 1 -> spec.bottomMode
                    row == 1 && column == 0 -> spec.leftMode
                    row == 1 && column == 1 -> spec.centerMode
                    else -> "stretch"
                }
                if (mode != "none") drawRegion(canvas, source, Rect(sx[column], sy[row], sx[column + 1], sy[row + 1]), destination, mode, row, column, drawPaint)
            }
        }
        rendered.put(key, output)
        return output
    }

    private fun drawRegion(canvas: Canvas, source: Bitmap, sourceRect: Rect, destination: Rect, mode: String, row: Int, column: Int, paint: Paint) {
        if (mode == "stretch") {
            canvas.drawBitmap(source, sourceRect, destination, paint)
            return
        }
        val tile = Bitmap.createBitmap(source, sourceRect.left, sourceRect.top, sourceRect.width(), sourceRect.height())
        val horizontal = row != 1 && column == 1
        val vertical = column != 1 && row == 1
        val scale = when {
            horizontal -> destination.height().toFloat() / tile.height
            vertical -> destination.width().toFloat() / tile.width
            else -> spec!!.tileScale(context.resources.displayMetrics.density)
        }
        val shader = BitmapShader(tile, if (vertical) Shader.TileMode.CLAMP else Shader.TileMode.REPEAT, if (horizontal) Shader.TileMode.CLAMP else Shader.TileMode.REPEAT)
        shader.setLocalMatrix(Matrix().apply {
            setScale(if (vertical) destination.width().toFloat() / tile.width else scale, if (horizontal) destination.height().toFloat() / tile.height else scale)
            postTranslate(destination.left.toFloat(), destination.top.toFloat())
        })
        paint.shader = shader
        canvas.drawRect(RectF(destination), paint)
        paint.shader = null
        tile.recycle()
    }

    private fun frameFile() = File(context.filesDir, FRAME_FILE)

    private class CachedFrameDrawable(
        private val renderer: LauncherFrameRenderer,
        private val fill: Int,
        private val pressedOverlay: Int?
    ) : Drawable() {
        private val paint = Paint().apply { isFilterBitmap = false }
        private val overlayPaint = Paint()
        private var pressed = false

        override fun draw(canvas: Canvas) {
            val bitmap = renderer.render(bounds.width(), bounds.height(), fill) ?: return
            canvas.drawBitmap(bitmap, bounds.left.toFloat(), bounds.top.toFloat(), paint)
            if (pressed && pressedOverlay != null) {
                overlayPaint.color = Color.argb(72, Color.red(pressedOverlay), Color.green(pressedOverlay), Color.blue(pressedOverlay))
                canvas.drawRect(bounds, overlayPaint)
            }
        }

        override fun isStateful(): Boolean = pressedOverlay != null

        override fun onStateChange(state: IntArray): Boolean {
            val next = state.contains(android.R.attr.state_pressed)
            if (next == pressed) return false
            pressed = next
            invalidateSelf()
            return true
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha.coerceIn(0, 255) }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private data class RenderKey(val width: Int, val height: Int, val fill: Int)

    internal data class FrameSpec(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val borderLeftDp: Float = left.toFloat(), val borderTopDp: Float = top.toFloat(),
        val borderRightDp: Float = right.toFloat(), val borderBottomDp: Float = bottom.toFloat(),
        val topMode: String = "stretch", val rightMode: String = "stretch",
        val bottomMode: String = "stretch", val leftMode: String = "stretch",
        val centerMode: String = "stretch", val filtering: String = "nearest"
    ) {
        fun validate(width: Int, height: Int) {
            require(width in 3..2048 && height in 3..2048) { "invalid frame dimensions" }
            require(left > 0 && top > 0 && right > 0 && bottom > 0) { "frame slices must be positive" }
            require(left + right < width && top + bottom < height) { "frame slices leave no center" }
            require(borderLeftDp > 0f && borderTopDp > 0f && borderRightDp > 0f && borderBottomDp > 0f) { "frame borders must be positive" }
            require(listOf(topMode, rightMode, bottomMode, leftMode).all { it == "tile" || it == "stretch" }) { "invalid edge mode" }
            require(centerMode in setOf("tile", "stretch", "none")) { "invalid center mode" }
            require(filtering == "nearest" || filtering == "linear") { "invalid filtering" }
        }

        fun destinationBorders(width: Int, height: Int, density: Float = 1f): FrameSpec {
            val baseLeft = borderLeftDp * density
            val baseTop = borderTopDp * density
            val baseRight = borderRightDp * density
            val baseBottom = borderBottomDp * density
            val scale = min(1f, min(width / (baseLeft + baseRight), height / (baseTop + baseBottom)))
            return FrameSpec(
                (baseLeft * scale).roundToInt(), (baseTop * scale).roundToInt(),
                (baseRight * scale).roundToInt(), (baseBottom * scale).roundToInt()
            )
        }

        fun tileScale(density: Float) = listOf(borderLeftDp * density / left, borderTopDp * density / top, borderRightDp * density / right, borderBottomDp * density / bottom).first()
    }

    companion object {
        private const val TAG = "RETUI-FRAME"
        private const val CACHE_KB = 12 * 1024
        private const val MAX_PNG_BYTES = 4 * 1024 * 1024
        private const val FRAME_FILE = "launcher-frame.png"
        private const val KEY_ACTIVE = "frame.launcher.active"
        private const val KEY_ASSET_ID = "frame.launcher.assetId"
        private const val KEY_LEFT = "frame.launcher.left"
        private const val KEY_TOP = "frame.launcher.top"
        private const val KEY_RIGHT = "frame.launcher.right"
        private const val KEY_BOTTOM = "frame.launcher.bottom"
        private const val KEY_BORDER_LEFT = "frame.launcher.borderLeft"
        private const val KEY_BORDER_TOP = "frame.launcher.borderTop"
        private const val KEY_BORDER_RIGHT = "frame.launcher.borderRight"
        private const val KEY_BORDER_BOTTOM = "frame.launcher.borderBottom"
        private const val KEY_MODE_TOP = "frame.launcher.modeTop"
        private const val KEY_MODE_RIGHT = "frame.launcher.modeRight"
        private const val KEY_MODE_BOTTOM = "frame.launcher.modeBottom"
        private const val KEY_MODE_LEFT = "frame.launcher.modeLeft"
        private const val KEY_MODE_CENTER = "frame.launcher.modeCenter"
        private const val KEY_FILTERING = "frame.launcher.filtering"
        private const val KEY_RENDER_VERSION = "frame.launcher.renderVersion"
        private const val RENDER_VERSION = 1
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
