package com.dvil.retui.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class LauncherFrameRenderer private constructor(
    context: Context,
    private val prefs: SharedPreferences
) {
    private val context = context.applicationContext
    private var roles = loadRoles()
    private val sources = object : LruCache<String, Bitmap>(SOURCE_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.allocationByteCount / 1024)
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue) oldValue.recycle()
        }
    }
    private val rendered = object : LruCache<RenderKey, Bitmap>(RENDER_CACHE_KB) {
        override fun sizeOf(key: RenderKey, value: Bitmap): Int = max(1, value.allocationByteCount / 1024)
        override fun entryRemoved(evicted: Boolean, key: RenderKey, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue) oldValue.recycle()
        }
    }

    fun accept(bundle: Bundle): Boolean {
        if (!prefs.getBoolean(KeyboardPrefs.KEY_ACCEPT_LAUNCHER_FRAMES, KeyboardPrefs.DEFAULT_ACCEPT_LAUNCHER_FRAMES)) return false
        val roleBundles = linkedMapOf<String, Bundle>()
        val nested = bundle.getBundle(RetuiVisualContract.FRAME_ROLES)
        if (nested == null) {
            if (bundle.containsKey(RetuiVisualContract.FRAME_AVAILABLE)) {
                roleBundles[RetuiVisualContract.FRAME_ROLE_KEYBOARD] = bundle
            }
        } else {
            ROLE_NAMES.forEach { role ->
                if (nested.containsKey(role)) nested.getBundle(role)?.let { roleBundles[role] = it }
            }
        }
        if (roleBundles.isEmpty()) return false

        val updates = linkedMapOf<String, RoleState?>()
        roleBundles.forEach { (role, payload) ->
            if (!payload.containsKey(RetuiVisualContract.FRAME_AVAILABLE)) return@forEach
            if (!payload.getBoolean(RetuiVisualContract.FRAME_AVAILABLE, false)) {
                updates[role] = null
                return@forEach
            }
            try {
                updates[role] = importRole(payload)
            } catch (error: Exception) {
                Log.w(TAG, "Ignoring invalid Launcher $role frame: ${error.message}")
            }
        }
        if (updates.isEmpty()) return false

        val next = mergeRoleStates(roles, updates)
        if (next == roles) return false
        val editor = prefs.edit()
        updates.forEach { (role, state) ->
            if (state == null) removeRole(editor, role) else putRole(editor, role, state)
        }
        editor.putInt(KEY_REVISION, prefs.getInt(KEY_REVISION, 0) + 1)
        if (!editor.commit()) return false
        roles = next
        rendered.evictAll()
        return true
    }

    fun reload() {
        roles = loadRoles()
        rendered.evictAll()
    }

    fun drawable(
        role: String,
        fill: Int,
        fallback: Drawable? = null,
        pressedOverlay: Int? = null,
        intrinsicDp: Float? = null
    ): Drawable = RoleFrameDrawable(this, role, fill, fallback, pressedOverlay, intrinsicDp)

    fun hasRole(role: String): Boolean = role in roles

    private fun importRole(bundle: Bundle): RoleState {
        val assetId = bundle.getString(RetuiVisualContract.FRAME_ASSET_ID)?.trim().orEmpty()
        val imageId = bundle.getString(RetuiVisualContract.FRAME_IMAGE_ID)?.trim().orEmpty()
        val uri = bundle.getString(RetuiVisualContract.FRAME_IMAGE_URI)?.let(Uri::parse) ?: error("missing frame URI")
        require(assetId.isNotEmpty() && assetId.length <= MAX_ID_LENGTH) { "invalid frame asset ID" }
        require(SHA_256.matches(imageId)) { "invalid frame image ID" }
        val spec = FrameSpec(
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
        val bytes = readPng(uri)
        require(sha256(bytes) == imageId) { "frame PNG hash does not match frame_image_id" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("unable to decode frame PNG")
        try {
            spec.validate(bitmap.width, bitmap.height)
            publishArtwork(imageId, bytes)
            if (sources.get(imageId) == null) sources.put(imageId, bitmap) else bitmap.recycle()
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
        return RoleState(assetId, imageId, spec)
    }

    private fun readPng(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_PNG_BYTES) { "frame PNG exceeds 4 MiB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }?.also { bytes ->
        require(bytes.size >= PNG_SIGNATURE.size && bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            "frame is not PNG"
        }
    } ?: error("unable to open frame URI")

    private fun publishArtwork(imageId: String, bytes: ByteArray) {
        val directory = artworkDirectory()
        require(directory.isDirectory || directory.mkdirs()) { "unable to create frame cache" }
        val destination = artworkFile(imageId)
        val temporary = File(directory, "$imageId.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            temporary.delete()
        }
    }

    private fun render(role: String, width: Int, height: Int, fill: Int): Bitmap? {
        val state = roles[role] ?: return null
        if (width <= 0 || height <= 0) return null
        val key = RenderKey(state.assetId, width, height, fill)
        rendered.get(key)?.let { return it }
        val source = source(state.imageId) ?: return null
        try {
            state.spec.validate(source.width, source.height)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(fill)
        val spec = state.spec
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
                if (mode != "none") {
                    drawRegion(canvas, source, Rect(sx[column], sy[row], sx[column + 1], sy[row + 1]), destination, mode, row, column, drawPaint, spec)
                }
            }
        }
        rendered.put(key, output)
        return output
    }

    private fun source(imageId: String): Bitmap? {
        sources.get(imageId)?.let { return it }
        val bitmap = BitmapFactory.decodeFile(artworkFile(imageId).absolutePath) ?: return null
        sources.put(imageId, bitmap)
        return bitmap
    }

    private fun drawRegion(
        canvas: Canvas,
        source: Bitmap,
        sourceRect: Rect,
        destination: Rect,
        mode: String,
        row: Int,
        column: Int,
        paint: Paint,
        spec: FrameSpec
    ) {
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
            else -> spec.tileScale(context.resources.displayMetrics.density)
        }.coerceAtLeast(0.01f)
        val shader = BitmapShader(
            tile,
            if (vertical) Shader.TileMode.CLAMP else Shader.TileMode.REPEAT,
            if (horizontal) Shader.TileMode.CLAMP else Shader.TileMode.REPEAT
        )
        shader.setLocalMatrix(Matrix().apply {
            setScale(
                if (vertical) destination.width().toFloat() / tile.width else scale,
                if (horizontal) destination.height().toFloat() / tile.height else scale
            )
            postTranslate(destination.left.toFloat(), destination.top.toFloat())
        })
        paint.shader = shader
        canvas.drawRect(RectF(destination), paint)
        paint.shader = null
        tile.recycle()
    }

    private fun loadRoles(): Map<String, RoleState> {
        if (!prefs.getBoolean(KeyboardPrefs.KEY_ACCEPT_LAUNCHER_FRAMES, KeyboardPrefs.DEFAULT_ACCEPT_LAUNCHER_FRAMES)) {
            return emptyMap()
        }
        return ROLE_NAMES.mapNotNull { role ->
        if (!prefs.getBoolean(roleKey(role, KEY_ACTIVE), false)) return@mapNotNull null
        val assetId = prefs.getString(roleKey(role, KEY_ASSET_ID), null).orEmpty()
        val imageId = prefs.getString(roleKey(role, KEY_IMAGE_ID), null).orEmpty()
        if (assetId.isEmpty() || !SHA_256.matches(imageId) || !artworkFile(imageId).isFile) return@mapNotNull null
        role to RoleState(
            assetId,
            imageId,
            FrameSpec(
                prefs.getInt(roleKey(role, KEY_LEFT), 0),
                prefs.getInt(roleKey(role, KEY_TOP), 0),
                prefs.getInt(roleKey(role, KEY_RIGHT), 0),
                prefs.getInt(roleKey(role, KEY_BOTTOM), 0),
                prefs.getFloat(roleKey(role, KEY_BORDER_LEFT), 0f),
                prefs.getFloat(roleKey(role, KEY_BORDER_TOP), 0f),
                prefs.getFloat(roleKey(role, KEY_BORDER_RIGHT), 0f),
                prefs.getFloat(roleKey(role, KEY_BORDER_BOTTOM), 0f),
                prefs.getString(roleKey(role, KEY_MODE_TOP), null).orEmpty(),
                prefs.getString(roleKey(role, KEY_MODE_RIGHT), null).orEmpty(),
                prefs.getString(roleKey(role, KEY_MODE_BOTTOM), null).orEmpty(),
                prefs.getString(roleKey(role, KEY_MODE_LEFT), null).orEmpty(),
                prefs.getString(roleKey(role, KEY_MODE_CENTER), null).orEmpty(),
                prefs.getString(roleKey(role, KEY_FILTERING), null).orEmpty()
            )
        )
        }.toMap()
    }

    private fun putRole(editor: SharedPreferences.Editor, role: String, state: RoleState) {
        val spec = state.spec
        editor.putBoolean(roleKey(role, KEY_ACTIVE), true)
            .putString(roleKey(role, KEY_ASSET_ID), state.assetId)
            .putString(roleKey(role, KEY_IMAGE_ID), state.imageId)
            .putInt(roleKey(role, KEY_LEFT), spec.left)
            .putInt(roleKey(role, KEY_TOP), spec.top)
            .putInt(roleKey(role, KEY_RIGHT), spec.right)
            .putInt(roleKey(role, KEY_BOTTOM), spec.bottom)
            .putFloat(roleKey(role, KEY_BORDER_LEFT), spec.borderLeftDp)
            .putFloat(roleKey(role, KEY_BORDER_TOP), spec.borderTopDp)
            .putFloat(roleKey(role, KEY_BORDER_RIGHT), spec.borderRightDp)
            .putFloat(roleKey(role, KEY_BORDER_BOTTOM), spec.borderBottomDp)
            .putString(roleKey(role, KEY_MODE_TOP), spec.topMode)
            .putString(roleKey(role, KEY_MODE_RIGHT), spec.rightMode)
            .putString(roleKey(role, KEY_MODE_BOTTOM), spec.bottomMode)
            .putString(roleKey(role, KEY_MODE_LEFT), spec.leftMode)
            .putString(roleKey(role, KEY_MODE_CENTER), spec.centerMode)
            .putString(roleKey(role, KEY_FILTERING), spec.filtering)
    }

    private fun removeRole(editor: SharedPreferences.Editor, role: String) {
        ROLE_KEYS.forEach { editor.remove(roleKey(role, it)) }
    }

    private fun artworkDirectory() = File(context.filesDir, FRAME_DIRECTORY)
    private fun artworkFile(imageId: String) = File(artworkDirectory(), "$imageId.png")

    internal class RoleFrameDrawable(
        private val renderer: LauncherFrameRenderer,
        private val role: String,
        private val fill: Int,
        internal val fallback: Drawable?,
        private val pressedOverlay: Int?,
        private val intrinsicDp: Float?
    ) : Drawable() {
        private val paint = Paint().apply { isFilterBitmap = false }
        private val overlayPaint = Paint()
        private var pressed = false

        override fun draw(canvas: Canvas) {
            val bitmap = renderer.render(role, bounds.width(), bounds.height(), fill)
            if (bitmap == null) {
                fallback?.bounds = bounds
                fallback?.draw(canvas)
            } else {
                canvas.drawBitmap(bitmap, bounds.left.toFloat(), bounds.top.toFloat(), paint)
                if (pressed && pressedOverlay != null) {
                    overlayPaint.color = Color.argb(72, Color.red(pressedOverlay), Color.green(pressedOverlay), Color.blue(pressedOverlay))
                    canvas.drawRect(bounds, overlayPaint)
                }
            }
        }

        override fun onBoundsChange(bounds: Rect) {
            fallback?.bounds = bounds
        }

        override fun isStateful(): Boolean = pressedOverlay != null || fallback?.isStateful == true

        override fun onStateChange(state: IntArray): Boolean {
            val next = state.contains(android.R.attr.state_pressed)
            val changed = next != pressed
            pressed = next
            val fallbackChanged = fallback?.setState(state) == true
            if (changed || fallbackChanged) invalidateSelf()
            return changed || fallbackChanged
        }

        override fun getIntrinsicWidth(): Int = intrinsicSize()?.first ?: fallback?.intrinsicWidth ?: -1
        override fun getIntrinsicHeight(): Int = intrinsicSize()?.second ?: fallback?.intrinsicHeight ?: -1

        private fun intrinsicSize(): Pair<Int, Int>? {
            val size = intrinsicDp ?: return null
            val state = renderer.roles[role] ?: return null
            val source = renderer.source(state.imageId) ?: return null
            val maxSize = size * renderer.context.resources.displayMetrics.density
            val scale = maxSize / max(source.width, source.height)
            return (source.width * scale).roundToInt().coerceAtLeast(1) to (source.height * scale).roundToInt().coerceAtLeast(1)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha.coerceIn(0, 255)
            fallback?.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            fallback?.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    internal data class RoleState(val assetId: String, val imageId: String, val spec: FrameSpec)
    private data class RenderKey(val assetId: String, val width: Int, val height: Int, val fill: Int)

    internal data class FrameSpec(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val borderLeftDp: Float = left.toFloat(),
        val borderTopDp: Float = top.toFloat(),
        val borderRightDp: Float = right.toFloat(),
        val borderBottomDp: Float = bottom.toFloat(),
        val topMode: String = "stretch",
        val rightMode: String = "stretch",
        val bottomMode: String = "stretch",
        val leftMode: String = "stretch",
        val centerMode: String = "stretch",
        val filtering: String = "nearest"
    ) {
        fun validate(width: Int, height: Int) {
            require(width in 3..2048 && height in 3..2048) { "invalid frame dimensions" }
            require(left > 0 && top > 0 && right > 0 && bottom > 0) { "frame slices must be positive" }
            require(left + right < width && top + bottom < height) { "frame slices leave no center" }
            require(listOf(borderLeftDp, borderTopDp, borderRightDp, borderBottomDp).all { it.isFinite() && it in 0f..256f }) {
                "frame borders must be between 0 and 256 dp"
            }
            require(listOf(topMode, rightMode, bottomMode, leftMode).all { it == "tile" || it == "stretch" }) { "invalid edge mode" }
            require(centerMode in setOf("tile", "stretch", "none")) { "invalid center mode" }
            require(filtering == "nearest" || filtering == "linear") { "invalid filtering" }
        }

        fun destinationBorders(width: Int, height: Int, density: Float = 1f): FrameSpec {
            val baseLeft = borderLeftDp * density
            val baseTop = borderTopDp * density
            val baseRight = borderRightDp * density
            val baseBottom = borderBottomDp * density
            val horizontal = baseLeft + baseRight
            val vertical = baseTop + baseBottom
            val scale = min(
                1f,
                min(if (horizontal == 0f) 1f else width / horizontal, if (vertical == 0f) 1f else height / vertical)
            )
            return FrameSpec(
                (baseLeft * scale).roundToInt(),
                (baseTop * scale).roundToInt(),
                (baseRight * scale).roundToInt(),
                (baseBottom * scale).roundToInt()
            )
        }

        fun tileScale(density: Float): Float = listOf(
            borderLeftDp * density / left,
            borderTopDp * density / top,
            borderRightDp * density / right,
            borderBottomDp * density / bottom
        ).firstOrNull { it > 0f } ?: 1f
    }

    companion object {
        private const val TAG = "RETUI-FRAME"
        private const val SOURCE_CACHE_KB = 16 * 1024
        private const val RENDER_CACHE_KB = 12 * 1024
        private const val MAX_PNG_BYTES = 4 * 1024 * 1024
        private const val MAX_ID_LENGTH = 256
        private const val FRAME_DIRECTORY = "launcher-frames"
        internal const val KEY_REVISION = "frame.roles.revision"
        private const val KEY_ACTIVE = "active"
        private const val KEY_ASSET_ID = "assetId"
        private const val KEY_IMAGE_ID = "imageId"
        private const val KEY_LEFT = "left"
        private const val KEY_TOP = "top"
        private const val KEY_RIGHT = "right"
        private const val KEY_BOTTOM = "bottom"
        private const val KEY_BORDER_LEFT = "borderLeft"
        private const val KEY_BORDER_TOP = "borderTop"
        private const val KEY_BORDER_RIGHT = "borderRight"
        private const val KEY_BORDER_BOTTOM = "borderBottom"
        private const val KEY_MODE_TOP = "modeTop"
        private const val KEY_MODE_RIGHT = "modeRight"
        private const val KEY_MODE_BOTTOM = "modeBottom"
        private const val KEY_MODE_LEFT = "modeLeft"
        private const val KEY_MODE_CENTER = "modeCenter"
        private const val KEY_FILTERING = "filtering"
        private val ROLE_KEYS = listOf(
            KEY_ACTIVE, KEY_ASSET_ID, KEY_IMAGE_ID, KEY_LEFT, KEY_TOP, KEY_RIGHT, KEY_BOTTOM,
            KEY_BORDER_LEFT, KEY_BORDER_TOP, KEY_BORDER_RIGHT, KEY_BORDER_BOTTOM,
            KEY_MODE_TOP, KEY_MODE_RIGHT, KEY_MODE_BOTTOM, KEY_MODE_LEFT, KEY_MODE_CENTER, KEY_FILTERING
        )
        internal val ROLE_NAMES: Set<String> = RetuiVisualContract.KEYBOARD_FRAME_ROLES.toSet()
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        @Volatile private var shared: LauncherFrameRenderer? = null

        fun shared(context: Context, prefs: SharedPreferences): LauncherFrameRenderer = shared ?: synchronized(this) {
            shared ?: LauncherFrameRenderer(context, prefs).also { shared = it }
        }

        internal fun mergeRoleStates(
            current: Map<String, RoleState>,
            updates: Map<String, RoleState?>
        ): Map<String, RoleState> = current.toMutableMap().apply {
            updates.forEach { (role, state) ->
                if (role !in ROLE_NAMES) return@forEach
                if (state == null) remove(role) else put(role, state)
            }
        }

        private fun roleKey(role: String, field: String) = "frame.role.$role.$field"

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
