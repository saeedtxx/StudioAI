package ir.studioai.productphoto

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object ImageProcessor {

    private const val WORKER_URL = "https://studioai.saeedtx.workers.dev/"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ۱. حذف پس‌زمینه (همراه با دانلود خودکار ماژول + پشتیبان برای Genymotion/Huawei)
    suspend fun removeBackground(context: Context, imageUri: Uri): Bitmap = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromFilePath(context, imageUri)
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        try {
            // بررسی و درخواست دانلود خودکار ماژول در صورت نبودن روی گوشی
            val moduleInstallClient = ModuleInstall.getClient(context)
            val areAvailable = moduleInstallClient.areModulesAvailable(segmenter).await()

            if (!areAvailable.areModulesAvailable()) {
                val installRequest = ModuleInstallRequest.newBuilder()
                    .addApi(segmenter)
                    .build()
                moduleInstallClient.installModules(installRequest).await()

                // صبر تا تکمیل دانلود اولیه ماژول (حداکثر ۱۲ ثانیه)
                repeat(12) {
                    delay(1000)
                    val check = moduleInstallClient.areModulesAvailable(segmenter).await()
                    if (check.areModulesAvailable()) return@repeat
                }
            }

            val result = segmenter.process(inputImage).await()
            result.foregroundBitmap ?: fallbackSmartCutout(context, imageUri)

        } catch (e: Exception) {
            // اگر روی Genymotion بدون GApps یا گوشی بدون سرویس گوگل بود، از دوربری داخلی استفاده کن
            fallbackSmartCutout(context, imageUri)
        }
    }

    // موتور دوربری پشتیبان (ویژه شبیه‌ساز Genymotion و گوشی‌های بدون Google Play)
    private fun fallbackSmartCutout(context: Context, imageUri: Uri): Bitmap {
        val stream = context.contentResolver.openInputStream(imageUri)
            ?: throw Exception("خطا در خواندن فایل تصویر")
        val original = BitmapFactory.decodeStream(stream)
        stream.close()

        val width = original.width
        val height = original.height
        val result = original.copy(Bitmap.Config.ARGB_8888, true)

        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        // تشخیص رنگ پس‌زمینه از گوشه‌های تصویر و حذف نرم آن (Chroma/Corner Keying)
        val cornerColor = pixels[0]
        val bgR = Color.red(cornerColor)
        val bgG = Color.green(cornerColor)
        val bgB = Color.blue(cornerColor)
        val threshold = 48

        for (i in pixels.indices) {
            val c = pixels[i]
            val diff = abs(Color.red(c) - bgR) + abs(Color.green(c) - bgG) + abs(Color.blue(c) - bgB)
            if (diff < threshold) {
                pixels[i] = Color.TRANSPARENT
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    // ۲. دریافت دکور آتلیه‌ای از کلادفلر ورکر شما
    suspend fun generateBackground(prompt: String, width: Int, height: Int): Bitmap = withContext(Dispatchers.IO) {
        val jsonBody = JSONObject().apply {
            put("prompt", prompt)
            put("width", width)
            put("height", height)
        }.toString()

        val request = Request.Builder()
            .url(WORKER_URL)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("خطا در ارتباط با سرور هوش مصنوعی: ${response.code}")
        }

        val contentType = response.header("Content-Type") ?: ""
        val bytes = if (contentType.contains("application/json")) {
            val bodyString = response.body?.string() ?: ""
            val base64Image = JSONObject(bodyString).getString("imageBase64")
            Base64.decode(base64Image, Base64.DEFAULT)
        } else {
            response.body?.bytes() ?: throw Exception("تصویری از سرور دریافت نشد")
        }

        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw Exception("خطا در پردازش تصویر دریافتی")
    }

    // ۳. ترکیب هوشمند محصول + سایه طبیعی استودیویی + پس‌زمینه + واترمارک
    suspend fun composeStudioPhoto(
        background: Bitmap,
        foreground: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        addWatermark: Boolean
    ): Bitmap = withContext(Dispatchers.Default) {
        val finalBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)

        val scaledBg = Bitmap.createScaledBitmap(background, targetWidth, targetHeight, true)
        canvas.drawBitmap(scaledBg, 0f, 0f, null)

        val maxProductWidth = targetWidth * 0.68f
        val maxProductHeight = targetHeight * 0.68f
        val scaleFactor = minOf(
            maxProductWidth / foreground.width.toFloat(),
            maxProductHeight / foreground.height.toFloat()
        )

        val prodWidth = (foreground.width * scaleFactor).toInt().coerceAtLeast(1)
        val prodHeight = (foreground.height * scaleFactor).toInt().coerceAtLeast(1)
        val scaledProduct = Bitmap.createScaledBitmap(foreground, prodWidth, prodHeight, true)

        val left = (targetWidth - prodWidth) / 2f
        val top = (targetHeight - prodHeight) * 0.56f

        // سایه تماس روی میز
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(135, 0, 0, 0)
            maskFilter = BlurMaskFilter(38f, BlurMaskFilter.Blur.NORMAL)
        }
        val shadowOval = RectF(
            left + prodWidth * 0.1f,
            top + prodHeight - (prodHeight * 0.07f),
            left + prodWidth * 0.9f,
            top + prodHeight + (prodHeight * 0.05f)
        )
        canvas.drawOval(shadowOval, shadowPaint)

        // سایه نرم پشت محصول
        val alphaShadow = scaledProduct.extractAlpha()
        val dropShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(85, 0, 0, 0)
            maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawBitmap(alphaShadow, left, top + 14f, dropShadowPaint)

        // رسم خود محصول
        val productPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaledProduct, left, top, productPaint)

        // افزودن واترمارک نسخه رایگان
        if (addWatermark) {
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(170, 15, 15, 20)
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(245, 176, 65)
                textSize = targetWidth * 0.032f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val watermarkText = "ساخته شده با استودیو هوش مصنوعی"
            val textWidth = textPaint.measureText(watermarkText)
            val padH = 28f
            val badgeRect = RectF(
                24f,
                targetHeight - 85f,
                24f + textWidth + (padH * 2),
                targetHeight - 24f
            )
            canvas.drawRoundRect(badgeRect, 20f, 20f, badgePaint)
            canvas.drawText(watermarkText, 24f + padH, targetHeight - 44f, textPaint)
        }

        finalBitmap
    }

    // ۴. ذخیره تصویر در گالری
    suspend fun saveToGallery(context: Context, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        try {
            val filename = "StudioAI_${System.currentTimeMillis()}.jpg"
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/StudioAI")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext false

            resolver.openOutputStream(imageUri)?.use { outStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outStream)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}