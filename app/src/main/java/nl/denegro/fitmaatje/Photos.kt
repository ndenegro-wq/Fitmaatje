package nl.denegro.fitmaatje

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File

object Photos {

    /** A fresh file + content Uri for the camera app to write into. */
    fun newCameraTarget(c: Context): Pair<File, Uri> {
        val dir = File(c.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        return f to FileProvider.getUriForFile(c, "nl.denegro.fitmaatje.files", f)
    }

    /**
     * Copies the photo (camera file or gallery Uri) into the app's photo folder,
     * upright and scaled down to max 1280 px, JPEG ~82%. Returns the stored file path.
     */
    fun store(c: Context, uri: Uri): String {
        val bytes = c.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val rotation = runCatching {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Foto kon niet gelezen worden")

        val scale = 1280f / maxOf(bmp.width, bmp.height)
        val m = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)

        val out = File(Repo.photoDir(), "food_${System.currentTimeMillis()}.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        return out.absolutePath
    }

    private val cache = HashMap<String, ImageBitmap>()

    fun thumb(path: String): ImageBitmap? = cache[path] ?: runCatching {
        val o = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeFile(path, o)?.asImageBitmap()
    }.getOrNull()?.also { cache[path] = it }
}
