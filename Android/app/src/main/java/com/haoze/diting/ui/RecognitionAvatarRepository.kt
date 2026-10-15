package com.haoze.diting.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val AVATAR_DIRECTORY = "recognition_avatars"
private const val AVATAR_BASE_URL =
    "https://raw.githubusercontent.com/haoze-evolluling/DITING/main/avatars/"
private const val MAX_AVATAR_BYTES = 1_024 * 1_024L

sealed interface RecognitionAvatarState {
    data object Loading : RecognitionAvatarState
    data class Available(val file: File) : RecognitionAvatarState
    data object Failed : RecognitionAvatarState
}

/** Persists acknowledgement avatars outside the APK and shares concurrent downloads by file name. */
object RecognitionAvatarRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val downloadMutex = Mutex()

    // Decoded, downsampled avatars keyed by (path, file size, target size). The
    // avatar set is small and fixed, so this stays tiny for the process lifetime.
    private val decodeCache = ConcurrentHashMap<String, ImageBitmap>()

    fun cachedAvatar(context: Context, avatarFileName: String): File? {
        val file = avatarDirectory(context).resolve(avatarFileName)
        return file.takeIf(::isValidImage)
    }

    /**
     * Decodes an avatar to display size on a background thread, downsampled to
     * roughly [targetSizePx] with a power-of-two [BitmapFactory.Options.inSampleSize].
     * The old path decoded the full-resolution JPEG on the main thread inside a
     * composable; here decoding never touches the UI thread and the sampled
     * bitmap is a small fraction of the source. The result is memoized per
     * (file, target size) for the process.
     */
    suspend fun decodeScaled(context: Context, file: File, targetSizePx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val key = decodeCacheKey(file, targetSizePx)
            decodeCache[key]?.let { return@withContext it }
            val decoded = decodeScaledInternal(file, targetSizePx) ?: return@withContext null
            val image = decoded.asImageBitmap()
            decodeCache[key] = image
            image
        }

    private fun decodeScaledInternal(file: File, targetSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val target = targetSizePx.coerceAtLeast(1)
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= target &&
            bounds.outHeight / (sampleSize * 2) >= target
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    private fun decodeCacheKey(file: File, targetSizePx: Int): String =
        "${file.absolutePath}@${file.length()}@$targetSizePx"

    suspend fun loadAvatar(context: Context, avatarFileName: String): File? =
        withContext(Dispatchers.IO) {
            validateAvatarFileName(avatarFileName)
            cachedAvatar(context, avatarFileName)?.let { return@withContext it }

            downloadMutex.withLock {
                cachedAvatar(context, avatarFileName)?.let { return@withLock it }
                downloadAvatar(context, avatarFileName)
            }
        }

    private fun downloadAvatar(context: Context, avatarFileName: String): File? {
        val directory = avatarDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) return null

        val destination = directory.resolve(avatarFileName)
        val temporary = directory.resolve(".$avatarFileName.download")
        temporary.delete()

        return try {
            val request = Request.Builder().url(AVATAR_BASE_URL + avatarFileName).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("头像响应为空")
                body.byteStream().use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var totalBytes = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            totalBytes += count
                            if (totalBytes > MAX_AVATAR_BYTES) {
                                throw IOException("头像文件过大")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            if (!isValidImage(temporary)) throw IOException("头像文件无效")
            replaceAtomically(destination, temporary)
            destination
        } catch (_: IOException) {
            null
        } finally {
            temporary.delete()
        }
    }

    private fun avatarDirectory(context: Context): File = File(context.filesDir, AVATAR_DIRECTORY)

    private fun replaceAtomically(destination: File, source: File) {
        val atomicFile = AtomicFile(destination)
        val output = atomicFile.startWrite()
        try {
            source.inputStream().use { input -> input.copyTo(output) }
            atomicFile.finishWrite(output)
        } catch (error: IOException) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun isValidImage(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun validateAvatarFileName(avatarFileName: String) {
        require(avatarFileName.matches(Regex("[a-z0-9_]+_avatar\\.jpg"))) {
            "头像文件名无效"
        }
    }
}
