package ir.studioai.productphoto

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

data class StudioPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val prompt: String,
    val previewColor: Color
)

class MainActivity : ComponentActivity() {

    private lateinit var billingManager: BazaarBillingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        billingManager = BazaarBillingManager(this)
        billingManager.initBilling()

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF0F0F14),
                        surface = Color(0xFF1C1C26),
                        primary = Color(0xFFF5B041)
                    )
                ) {
                    StudioAppScreen(billingManager)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.release()
    }
}

@Composable
fun StudioAppScreen(billingManager: BazaarBillingManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("studio_prefs", Context.MODE_PRIVATE) }

    var coins by remember { mutableIntStateOf(prefs.getInt("coins", 2)) }
    var isVip by remember { mutableStateOf(prefs.getBoolean("is_vip", false)) }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedAspectRatio by remember { mutableStateOf("۱:۱ پست اینستاگرام") }
    var selectedPromoBadge by remember { mutableStateOf("بدون برچسب") }
    var showPaywall by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    fun updateCoins(newCoins: Int, vipUpgrade: Boolean = isVip) {
        coins = newCoins
        isVip = vipUpgrade
        prefs.edit().putInt("coins", newCoins).putBoolean("is_vip", vipUpgrade).apply()
    }

    val presets = remember {
        listOf(
            StudioPreset("marble", "سنگ مرمر لوکس", "ویژه عطر و جواهرات", "luxury white marble podium table, soft studio lighting, elegant bokeh background", Color(0xFF2E4053)),
            StudioPreset("wood", "میز چوب گردو", "ویژه کیف، کفش و ساعت", "warm dark walnut wooden table surface, cinematic studio lighting, cozy background", Color(0xFF6E2C00)),
            StudioPreset("nature", "برگ و قطرات آب", "ویژه آرایشی و بهداشتی", "natural stone podium with tropical green leaves, water droplets, fresh morning sunlight", Color(0xFF145A32)),
            StudioPreset("digikala", "سفید استاندارد", "ویژه دیجی‌کالا و باسلام", "pure clean white minimalist studio table background, soft commercial lighting", Color(0xFF424949)),
            StudioPreset("neon", "نئون سایبرپانک", "ویژه گجت و موبایل", "dark sleek matte podium with purple and blue neon rim lighting, tech studio", Color(0xFF4A235A)),
            StudioPreset("gold", "مخمل مشکی و طلا", "ویژه ساعت و اکسسوری", "black velvet podium with warm golden luxury lighting and sparkles", Color(0xFF5B4814))
        )
    }
    var selectedPreset by remember { mutableStateOf(presets.first()) }

    val promoBadges = remember {
        listOf("بدون برچسب", "🔥 فروش ویژه", "🚚 ارسال رایگان", "💎 تضمین کیفیت", "⚡ موجودی محدود")
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            resultBitmap = null
        }
    }

    Scaffold(
        containerColor = Color(0xFF0F0F14),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "استودیو عکاسی هوش مصنوعی",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isVip) "✨ اشتراک VIP فعال (بدون واترمارک)" else "تبدیل عکس معمولی به عکس تبلیغاتی",
                        color = if (isVip) Color(0xFFF5B041) else Color(0xFF9E9EAE),
                        fontSize = 11.sp
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFF5B041), Color(0xFFE67E22))
                            )
                        )
                        .clickable { showPaywall = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🪙 $coins سکه | خرید VIP",
                        color = Color(0xFF0F0F14),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ۱. بوم نمایش تصویر
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF1C1C26))
                    .border(1.dp, Color(0xFF2E2E3E), RoundedCornerShape(22.dp))
                    .clickable(enabled = !isProcessing) { galleryLauncher.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                when {
                    resultBitmap != null -> {
                        Image(
                            bitmap = resultBitmap!!.asImageBitmap(),
                            contentDescription = "تصویر نهایی",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    selectedImageUri != null -> {
                        AsyncImage(
                            model = selectedImageUri,
                            contentDescription = "عکس محصول",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF282838)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📸", fontSize = 30.sp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "انتخاب عکس محصول از گالری",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "پس‌زمینه به صورت خودکار حذف و نورپردازی می‌شود",
                                color = Color(0xFF8E8E9E),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                if (selectedImageUri != null && !isProcessing) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (resultBitmap != null && !isVip) {
                            Surface(
                                color = Color(0xFFF5B041),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.clickable { showPaywall = true }
                            ) {
                                Text(
                                    text = "👑 حذف واترمارک",
                                    color = Color(0xFF0F0F14),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.clickable { galleryLauncher.launch("image/*") }
                        ) {
                            Text(
                                text = "عکس جدید",
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.82f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFFF5B041))
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = statusMessage,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ۲. انتخاب ابعاد
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("۱:۱ پست اینستاگرام", "۹:۱۶ استوری و ریلز", "۴:۵ دیوار و باسلام").forEach { ratio ->
                    val isSelected = selectedAspectRatio == ratio
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) Color(0xFF2E2E42) else Color(0xFF1C1C26))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(0xFFF5B041) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable(enabled = !isProcessing) { selectedAspectRatio = ratio }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = ratio,
                            color = if (isSelected) Color(0xFFF5B041) else Color(0xFF9E9EAE),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ۳. انتخاب برچسب فروشگاهی (ویژه آنلاین‌شاپ‌ها)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(promoBadges) { badge ->
                    val isSelected = selectedPromoBadge == badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isSelected) Color(0xFFE74C3C) else Color(0xFF1C1C26))
                            .clickable(enabled = !isProcessing) { selectedPromoBadge = badge }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = badge,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ۴. لیست دکورهای آتلیه
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(presets) { preset ->
                    val isSelected = preset.id == selectedPreset.id
                    Column(
                        modifier = Modifier
                            .width(130.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(preset.previewColor)
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) Color(0xFFF5B041) else Color.Transparent,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable(enabled = !isProcessing) { selectedPreset = preset }
                            .padding(10.dp)
                    ) {
                        Text(
                            text = preset.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = preset.subtitle,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ۵. دکمه‌های ساخت و ذخیره
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (selectedImageUri == null) {
                            galleryLauncher.launch("image/*")
                        } else if (coins <= 0) {
                            showPaywall = true
                        } else {
                            scope.launch {
                                try {
                                    isProcessing = true
                                    val (targetW, targetH) = when {
                                        selectedAspectRatio.contains("۹:۱۶") -> 768 to 1344
                                        selectedAspectRatio.contains("۴:۵") -> 896 to 1120
                                        else -> 1024 to 1024
                                    }

                                    statusMessage = "✂️ در حال دوربری هوشمند محصول..."
                                    val cutout = ImageProcessor.removeBackground(context, selectedImageUri!!)

                                    statusMessage = "🎨 در حال ساخت دکور ${selectedPreset.title}..."
                                    val bg = ImageProcessor.generateBackground(selectedPreset.prompt, targetW, targetH)

                                    statusMessage = "✨ در حال نورپردازی و سایه‌زنی استودیویی..."
                                    val finalImg = ImageProcessor.composeStudioPhoto(
                                        background = bg,
                                        foreground = cutout,
                                        targetWidth = targetW,
                                        targetHeight = targetH,
                                        promoBadgeText = selectedPromoBadge,
                                        addWatermark = !isVip
                                    )

                                    resultBitmap = finalImg
                                    updateCoins(coins - 1)
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "خطا: ${e.localizedMessage ?: "دوباره تلاش کنید"}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    isProcessing = false
                                }
                            }
                        }
                    },
                    enabled = !isProcessing,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5B041))
                ) {
                    Text(
                        text = when {
                            selectedImageUri == null -> "انتخاب عکس محصول"
                            resultBitmap != null -> "🔄 ساخت مجدد (۱ سکه)"
                            else -> "✨ ساخت عکس آتلیه‌ای (۱ سکه)"
                        },
                        color = Color(0xFF0F0F14),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                if (resultBitmap != null) {
                    Button(
                        onClick = {
                            scope.launch {
                                val saved = ImageProcessor.saveToGallery(context, resultBitmap!!)
                                if (saved) {
                                    Toast.makeText(context, "✅ در گالری (پوشه StudioAI) ذخیره شد!", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "خطا در ذخیره‌سازی تصویر", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71))
                    ) {
                        Text(
                            text = "📥 ذخیره",
                            color = Color(0xFF0F0F14),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // ۶. مودال خرید متصل به درگاه کافه بازار (پولکی)
    if (showPaywall) {
        AlertDialog(
            onDismissRequest = { showPaywall = false },
            containerColor = Color(0xFF1C1C26),
            title = {
                Text(
                    text = "💎 ارتقا به استودیو VIP",
                    color = Color(0xFFF5B041),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 19.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "با خرید هر بسته، واترمارک برای همیشه حذف شده و عکس‌ها با کیفیت Ultra HD ذخیره می‌شوند:",
                        color = Color(0xFFCCCCCC),
                        fontSize = 12.sp
                    )
                    PricingCard("بسته استارتر (۱۵ عکس + حذف واترمارک)", "۶۹,۰۰۰ تومان", false) {
                        billingManager.startPurchase(BazaarBillingManager.SKU_STARTER_15, 15) { reward ->
                            updateCoins(coins + reward, vipUpgrade = true)
                            showPaywall = false
                        }
                    }
                    PricingCard("بسته پرفروش مغازه‌دار (۶۰ عکس + VIP)", "۱۸۹,۰۰۰ تومان", true) {
                        billingManager.startPurchase(BazaarBillingManager.SKU_SHOP_60, 60) { reward ->
                            updateCoins(coins + reward, vipUpgrade = true)
                            showPaywall = false
                        }
                    }
                    PricingCard("اشتراک ویژه ادمین‌ها (۲۵۰ عکس + VIP)", "۳۹۰,۰۰۰ تومان", false) {
                        billingManager.startPurchase(BazaarBillingManager.SKU_VIP_250, 250) { reward ->
                            updateCoins(coins + reward, vipUpgrade = true)
                            showPaywall = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPaywall = false }) {
                    Text("بستن", color = Color.Gray)
                }
            }
        )
    }
}

@Composable
fun PricingCard(title: String, price: String, isPopular: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isPopular) Color(0xFF2C2518) else Color(0xFF12121A))
            .border(
                width = if (isPopular) 1.5.dp else 1.dp,
                color = if (isPopular) Color(0xFFF5B041) else Color(0xFF2E2E3E),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        if (isPopular) {
            Text(
                text = "🔥 پیشنهاد ویژه و پرفروش",
                color = Color(0xFFF5B041),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = price,
                color = Color(0xFFF5B041),
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}