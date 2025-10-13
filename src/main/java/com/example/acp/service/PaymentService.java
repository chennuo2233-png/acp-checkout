package com.example.acp.service;

import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    public PaymentService(@Value("${stripe.secret}") String secretKey) {
        Stripe.overrideApiBase("https://api.stripe.com");
        Stripe.apiKey = secretKey;
        Stripe.setAppInfo("acp-checkout-demo", "0.0.1", null);
    }

    /**
     * 使用 Shared-Payment-Granted-Token 创建并确认一笔支付
     */
    public PaymentIntent createIntent(long amount,
                                      String currency,
                                      String sharedPaymentGrantedToken) throws Exception {

        PaymentIntentCreateParams params =
            PaymentIntentCreateParams.builder()
                .setAmount(amount)                 // 单位：美分
                .setCurrency(currency)             // 如 "usd"
                // ↓ 关键：通过 extraParam 传入 shared_payment_granted_token
                .putExtraParam("shared_payment_granted_token", sharedPaymentGrantedToken)
                .setConfirm(true)                  // 立即扣款
                .build();

        return PaymentIntent.create(params);
    }
}
