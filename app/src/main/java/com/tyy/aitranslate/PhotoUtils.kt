package com.tyy.aitranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

object PhotoUtils {
    private const val MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 85

    data class PreparedPhoto(
        val preview: Bitmap,
        val base64: String
    )

    /**
     * 读取图片，按 EXIF 校正方向、缩小到适合模型处理的大小，并编码为 JPEG Base64。
     */
    fun prepare(context: Context, uri: Uri): PreparedPhoto {
        val bitmap = decodeScaled(context, uri)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            it.toByteArray()
        }
        return PreparedPhoto(
            preview = bitmap,
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        )
    }

    /**
     * 把任意来源的图片（相册大图、HEIC、相机直出）解码后统一转码为
     * 最长边 1600px、JPEG 85% 的小文件再落盘：
     * 几十 MB 的原图也只写几百 KB，既避免超长复制，也防止 cache 膨胀。
     * 失败返回 false，由调用方回退或提示。
     */
    fun processToFile(context: Context, source: Uri, dest: File): Boolean {
        val bitmap = runCatching { decodeScaled(context, source) }.getOrNull() ?: return false
        val dirOk = dest.parentFile?.let { it.isDirectory || it.mkdirs() } == true
        var written = false
        if (dirOk) {
            written = runCatching {
                dest.outputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        "compress failed"
                    }
                }
            }.isSuccess
        }
        bitmap.recycle()
        return written
    }

    /** 解码 + EXIF 方向校正 + 缩放到 MAX_DIMENSION 以内。 */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("无法读取图片")
        }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("无法读取图片")

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
        if (!matrix.isIdentity()) {
            bitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
        }

        val longest = max(bitmap.width, bitmap.height)
        if (longest > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }
        return bitmap
    }
}
