package voice.core.playback.session

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import voice.core.logging.api.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Dedicated ContentProvider for serving audiobook covers to external surfaces like
 * Android Auto, Automotive OS, Wear OS, and SystemUI.
 *
 * Exported without restrictive permissions so Android Auto projection hosts can load
 * covers via [android.content.ContentResolver.openInputStream] without SecurityException /
 * Permission Denial, while strictly enforcing read-only access and canonical path containment.
 *
 * Automatically composes a Coolwalk-optimized layout (ratio ~0.85 portrait) with an ambient
 * blurred backdrop and the complete, uncropped book cover framed in the upper showcase window,
 * leaving the lower half clean for Android Auto's playback controls and chapter title.
 */
class CoverContentProvider : ContentProvider() {

  override fun onCreate(): Boolean = true

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    if (mode != "r") {
      throw SecurityException("Write access denied")
    }
    val file = resolveFile(uri) ?: throw FileNotFoundException("Cover not found for $uri")
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  }

  override fun getType(uri: Uri): String {
    val path = uri.path?.lowercase() ?: ""
    return when {
      path.endsWith(".png") -> "image/png"
      path.endsWith(".webp") -> "image/webp"
      else -> "image/jpeg"
    }
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? {
    val file = resolveFile(uri) ?: return null
    val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    val cursor = MatrixCursor(cols, 1)
    val row = cursor.newRow()
    for (col in cols) {
      when (col) {
        OpenableColumns.DISPLAY_NAME -> row.add(col, file.name)
        OpenableColumns.SIZE -> row.add(col, file.length())
        else -> row.add(col, null)
      }
    }
    return cursor
  }

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

  private fun resolveFile(uri: Uri): File? {
    val ctx = context ?: return null
    val rawPath = uri.path?.trimStart('/') ?: return null

    val candidateFile = when {
      rawPath.startsWith("crazy_covers/") -> File(ctx.filesDir, rawPath)
      rawPath.startsWith("bookCovers/") -> File(ctx.filesDir, rawPath)
      rawPath.startsWith("covers/") -> {
        val sub = rawPath.removePrefix("covers/")
        val crazy = File(ctx.filesDir, "crazy_covers/$sub")
        if (crazy.exists()) crazy else File(ctx.filesDir, "bookCovers/$sub")
      }
      else -> {
        val crazy = File(ctx.filesDir, "crazy_covers/$rawPath")
        if (crazy.exists()) crazy else File(ctx.filesDir, "bookCovers/$rawPath")
      }
    }

    return try {
      val canonical = candidateFile.canonicalFile
      val filesDirCanonical = ctx.filesDir.canonicalFile
      if (!canonical.path.startsWith(filesDirCanonical.path)) {
        Logger.e("CoverContentProvider: Path traversal attempt: $rawPath")
        null
      } else if (canonical.exists() && canonical.isFile && canonical.length() > 0) {
        getOrGenerateCarCover(ctx, canonical)
      } else {
        null
      }
    } catch (e: Exception) {
      Logger.w(e, "CoverContentProvider: Error resolving file for $rawPath")
      null
    }
  }

  private fun getOrGenerateCarCover(ctx: Context, original: File): File {
    val autoCoversDir = File(ctx.filesDir, "auto_covers")
    if (!autoCoversDir.exists()) {
      autoCoversDir.mkdirs()
    }
    val targetFile = File(autoCoversDir, "aa_" + original.name)
    if (targetFile.exists() && targetFile.length() > 0 && targetFile.lastModified() >= original.lastModified()) {
      return targetFile
    }

    return try {
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(original.absolutePath, options)
      val w = options.outWidth
      val h = options.outHeight
      if (w <= 0 || h <= 0) return original

      val cardW = 512
      val cardH = 600

      val maxDim = maxOf(w, h)
      var sampleSize = 1
      while (maxDim / (sampleSize * 2) >= 600) {
        sampleSize *= 2
      }
      val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
      val srcBitmap = BitmapFactory.decodeFile(original.absolutePath, decodeOptions) ?: return original

      val compositeBitmap = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(compositeBitmap)

      // 1. Ambient blurred & darkened background
      val tiny = Bitmap.createScaledBitmap(srcBitmap, 16, 20, true)
      val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
      canvas.drawBitmap(tiny, Rect(0, 0, 16, 20), RectF(0f, 0f, cardW.toFloat(), cardH.toFloat()), bgPaint)
      tiny.recycle()

      // Darken overlay (60%) so text and controls stand out clearly
      canvas.drawColor(Color.argb(160, 12, 12, 12))

      // 2. Fit complete cover in the upper showcase window (above Android Auto controls)
      val maxShowcaseH = 340f
      val maxShowcaseW = 460f
      val scale = minOf(maxShowcaseW / srcBitmap.width, maxShowcaseH / srcBitmap.height)
      val scaledW = (srcBitmap.width * scale).toInt()
      val scaledH = (srcBitmap.height * scale).toInt()
      val left = (cardW - scaledW) / 2f
      val top = 22f

      // Subtle framing border
      val borderPaint = Paint().apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
      }
      canvas.drawRect(left - 1, top - 1, left + scaledW + 1, top + scaledH + 1, borderPaint)

      val srcRect = Rect(0, 0, srcBitmap.width, srcBitmap.height)
      val dstRect = RectF(left, top, left + scaledW, top + scaledH)
      val coverPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
      canvas.drawBitmap(srcBitmap, srcRect, dstRect, coverPaint)
      srcBitmap.recycle()

      val tmpFile = File(autoCoversDir, "aa_" + original.name + ".tmp")
      FileOutputStream(tmpFile).use { fos ->
        compositeBitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
      }
      compositeBitmap.recycle()
      tmpFile.renameTo(targetFile)
      if (targetFile.exists() && targetFile.length() > 0) targetFile else original
    } catch (e: Exception) {
      Logger.w(e, "CoverContentProvider: Failed to generate car cover for ${original.name}")
      original
    }
  }
}
