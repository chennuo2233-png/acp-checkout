package com.example.acp.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PaymentService {

    private final boolean stripeEnabled;   // true = 真支付；false = Stub

    public PaymentService(@Value("${stripe.secret:}") String secretKey,
                          @Value("${stripe.enabled:false}") boolean stripeEnabled) {
        this.stripeEnabled = stripeEnabled;

        if (stripeEnabled) {               // 只有开启时才初始化 Stripe
            Stripe.apiKey = secretKey;
            Stripe.overrideApiBase("https://api.stripe.com");
            Stripe.setAppInfo("acp-checkout-demo", "0.0.1", null);
        }
    }

    /**
     * 向 PSP 收款；stub 模式直接返回成功
     */
    public Map<String, Object> charge(long amount, String currency, String token) {
        /* ---------- ① Stub 路径：开发阶段 / 未开通 Stripe Delegated Payment ---------- */
        if (!stripeEnabled || token.startsWith("test_ok")) {
            return Map.of(
                    "status", "succeeded",
                    "payment_intent_id", "pi_stub_" + System.currentTimeMillis()
            );
        }

        /* ---------- ② 真正调用 Stripe Delegated Payment ---------- */
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                     .setAmount(amount)
                     .setCurrency(currency)
                     .putExtraParam("shared_payment_granted_token", token) // ✅ 使用 SPT
                     .setConfirm(true)                                     // ✅ 直接确认
                     .build();

            PaymentIntent pi = PaymentIntent.create(params);
            return Map.of(
                    "status", pi.getStatus(),
                    "payment_intent_id", pi.getId()
            );

        } catch (StripeException e) {
            return Map.of(
                    "status", "payment_failed",
                    "failure_message", e.getMessage()
            );
        }
    }
}
