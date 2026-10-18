package ir.studioai.productphoto

import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest

class BazaarBillingManager(private val activity: ComponentActivity) {

    companion object {
        // وقتی برنامه را در پیشخوان کافه بازار ساختید، کلید RSA را اینجا قرار دهید
        private const val BAZAAR_RSA_PUBLIC_KEY = "PUT_YOUR_CAFEBAZAAR_RSA_KEY_HERE"

        // شناسه‌های محصولات (SKU) که در پیشخوان کافه بازار تعریف می‌کنید
        const val SKU_STARTER_15 = "pack_15_coins"
        const val SKU_SHOP_60 = "pack_60_coins"
        const val SKU_VIP_250 = "pack_250_coins"
    }

    private var paymentConnection: Connection? = null
    private lateinit var payment: Payment
    private var isConnectedToBazaar = false

    fun initBilling() {
        val securityCheck = if (BAZAAR_RSA_PUBLIC_KEY.startsWith("PUT_YOUR")) {
            SecurityCheck.Disable
        } else {
            SecurityCheck.Enable(rsaPublicKey = BAZAAR_RSA_PUBLIC_KEY)
        }

        val paymentConfig = PaymentConfiguration(localSecurityCheck = securityCheck)
        payment = Payment(context = activity, config = paymentConfig)

        paymentConnection = payment.connect {
            connectionSucceed {
                isConnectedToBazaar = true
            }
            connectionFailed {
                isConnectedToBazaar = false
            }
            disconnected {
                isConnectedToBazaar = false
            }
        }
    }

    fun startPurchase(
        productId: String,
        coinsReward: Int,
        onSuccess: (Int) -> Unit
    ) {
        // اگر هنوز کلید بازار را نگذاشته‌اید یا روی شبیه‌ساز بدون بازار هستید -> تست آنی
        if (BAZAAR_RSA_PUBLIC_KEY.startsWith("PUT_YOUR") || !isConnectedToBazaar) {
            Toast.makeText(
                activity,
                "✅ خرید آزمایشی انجام شد (+$coinsReward سکه + فعال‌سازی VIP)",
                Toast.LENGTH_LONG
            ).show()
            onSuccess(coinsReward)
            return
        }

        val purchaseRequest = PurchaseRequest(
            productId = productId,
            payload = "studioai_${System.currentTimeMillis()}"
        )

        payment.purchaseProduct(
            registry = activity.activityResultRegistry,
            request = purchaseRequest
        ) {
            purchaseFlowBegan {}
            failedToBeginFlow {
                Toast.makeText(activity, "خطا در باز کردن درگاه کافه بازار", Toast.LENGTH_SHORT).show()
            }
            purchaseSucceed { purchaseEntity ->
                // مصرف کردن خرید (Consume) تا کاربر بتواند در آینده دوباره سکه بخرد
                payment.consumeProduct(purchaseEntity.purchaseToken) {
                    consumeSucceed {
                        Toast.makeText(activity, "🎉 خرید موفق! $coinsReward سکه اضافه شد.", Toast.LENGTH_LONG).show()
                        onSuccess(coinsReward)
                    }
                    consumeFailed {
                        // حتی اگر مصرف توکن با تاخیر مواجه شد، سکه کاربر را بدهیم
                        onSuccess(coinsReward)
                    }
                }
            }
            purchaseCanceled {
                Toast.makeText(activity, "خرید لغو شد", Toast.LENGTH_SHORT).show()
            }
            purchaseFailed {
                Toast.makeText(activity, "خطا در انجام پرداخت", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun release() {
        paymentConnection?.disconnect()
    }
}