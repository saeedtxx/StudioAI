package ir.studioai.productphoto

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ImageProcessor {

    private const val WORKER_URL = "https://studioai.saeedtx.workers.dev/"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ۱. دوربری سریع (حداکثر ۳.۵ ثانیه مهلت به سرویس گوگل، در غیر این صورت دوربری فوری داخلی)
    suspend fun removeBackground(context: Context, imageUri: Uri): Bitmap = withContext(Dispatchers.Default) {
        val mlKitBitmap = withTimeoutOrNull(3500L) {
            try {
                val inputImage = InputImage.fromFilePath(context, imageUri)
                val options = SubjectSegmenterOptions.Builder()
                    .enableForegroundBitmap()
                    .build()
                val segmenter = SubjectSegmentation.getClient(options)
                val result = segmenter.process(inputImage).await()
                result.foregroundBitmap
            } catch (e: Exception) {
                null
            }
        }

        // اگر ML Kit در ۳.۵ ثانیه جواب داد از آن استفاده کن، وگرنه بلافاصله دوربری داخلی را انجام بده
        mlKitBitmap ?: fallbackSmartCutout(context, imageUri)
    }

    // موتور دوربری سریع داخلی (مخصوص Genymotion و گوشی‌های بدون Google Play)
    private fun fallbackSmartCutout(context: Context, imageUri: Uri): Bitmap {
        val stream = context.contentResolver.openInputStream(imageUri)
            ?: throw Exception("خطا در خواندن فایل تصویر")
        val rawBitmap = BitmapFactory.decodeStream(stream)
        stream.close()

        // کوچک‌سازی هوشمند عکس‌های خیلی بزرگ برای پردازش در کسری از ثانیه
        val maxDim = 900f
        val scale = min(1f, min(maxDim / rawBitmap.width, maxDim / rawBitmap.height))
        val width = (rawBitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (rawBitmap.height * scale).toInt().coerceAtLeast(1)

        val result = Bitmap.createScaledBitmap(rawBitmap, width, height, true)
            .copy(Bitmap.Config.ARGB_8888, true)

        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        // نمونه‌برداری رنگ پس‌زمینه از ۴ گوشه تصویر
        val cornerColor = pixels[0]
        val bgR = Color.red(cornerColor)
        val bgG = Color.green(cornerColor)
        val bgB = Color.blue(cornerColor)
        val threshold = 65

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

    // ۲. دریافت دکور آتلیه‌ای از کلادفلر ورکر
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
            throw Exception("خطا در دریافت دکور: کد ${response.code}")
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
            ?: throw Exception("فرمت تصویر دریافتی معتبر نیست")
    }

    // ۳. ترکیب هوشمند محصول + سایه استودیویی + برچسب فروش + واترمارک
    suspend fun composeStudioPhoto(
        background: Bitmap,
        foreground: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        promoBadgeText: String,
        addWatermark: Boolean
    ): Bitmap = withContext(Dispatchers.Default) {
        val finalBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)

        // برش مرکزی (Center-Crop) پس‌زمینه برای جلوگیری از کشیدگی تصویر
        val scaleBg = max(
            targetWidth.toFloat() / background.width,
            targetHeight.toFloat() / background.height
        )
        val scaledBgW = (background.width * scaleBg).toInt().coerceAtLeast(1)
        val scaledBgH = (background.height * scaleBg).toInt().coerceAtLeast(1)
        val scaledBg = Bitmap.createScaledBitmap(background, scaledBgW, scaledBgH, true)
        val bgLeft = (targetWidth - scaledBgW) / 2f
        val bgTop = (targetHeight - scaledBgH) / 2f
        canvas.drawBitmap(scaledBg, bgLeft, bgTop, null)

        // تنظیم ابعاد محصول وسط صحنه
        val maxProductWidth = targetWidth * 0.68f
        val maxProductHeight = targetHeight * 0.68f
        val scaleFactor = min(
            maxProductWidth / foreground.width.toFloat(),
            maxProductHeight / foreground.height.toFloat()
        )

        val prodWidth = (foreground.width * scaleFactor).toInt().coerceAtLeast(1)
        val prodHeight = (foreground.height * scaleFactor).toInt().coerceAtLeast(1)
        val scaledProduct = Bitmap.createScaledBitmap(foreground, prodWidth, prodHeight, true)

        val left = (targetWidth - prodWidth) / 2f
        val top = (targetHeight - prodHeight) * 0.55f

        // سایه تماس روی میز
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(145, 0, 0, 0)
            maskFilter = BlurMaskFilter(36f, BlurMaskFilter.Blur.NORMAL)
        }
        val shadowOval = RectF(
            left + prodWidth * 0.1f,
            top + prodHeight - (prodHeight * 0.06f),
            left + prodWidth * 0.9f,
            top + prodHeight + (prodHeight * 0.05f)
        )
        canvas.drawOval(shadowOval, shadowPaint)

        // سایه نرم پشت محصول
        val alphaShadow = scaledProduct.extractAlpha()
        val dropShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 0, 0)
            maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawBitmap(alphaShadow, left, top + 12f, dropShadowPaint)

        // رسم خود محصول
        val productPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaledProduct, left, top, productPaint)

        // برچسب فروشگاهی
        if (promoBadgeText.isNotBlank() && promoBadgeText != "بدون برچسب") {
            val promoBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(231, 76, 60)
                setShadowLayer(12f, 0f, 6f, Color.argb(120, 0, 0, 0))
            }
            val promoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = targetWidth * 0.042f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val tWidth = promoTextPaint.measureText(promoBadgeText)
            val padH = 36f
            val badgeRight = targetWidth - 36f
            val badgeLeft = badgeRight - tWidth - (padH * 2)
            val promoRect = RectF(badgeLeft, 36f, badgeRight, 118f)

            canvas.drawRoundRect(promoRect, 50f, 50f, promoBgPaint)
            canvas.drawText(promoBadgeText, badgeLeft + padH, 90f, promoTextPaint)
        }

        // واترمارک نسخه رایگان
        if (addWatermark) {
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(175, 15, 15, 20)
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