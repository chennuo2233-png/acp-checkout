package com.example.acp.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付服务（Stub + Stripe SPT）
 * 约定：
 *  - 开发/联调（stripe.enabled=false）：只接受 test_ok_* 作为测试令牌，返回 pi_stub_*。
 *  - 生产/预发（stripe.enabled=true）：只接受 spt_*（Shared Payment Token），调用 Stripe 真扣款。
 */
@Service
public class PaymentService {

    /** true = 真支付；false = Stub */
    private final boolean stripeEnabled;

    /** Stripe 密钥（仅在真支付分支使用） */
    private final String stripeSecret;

    public PaymentService(
            @Value("${stripe.enabled:false}") boolean stripeEnabled,
            @Value("${stripe.secret:}") String stripeSecret
    ) {
        this.stripeEnabled = stripeEnabled;
        this.stripeSecret = stripeSecret == null ? "" : stripeSecret.trim();
        if (this.stripeEnabled && !this.stripeSecret.isEmpty()) {
            Stripe.apiKey = this.stripeSecret;
        }
    }

    /* ---------- 兼容多种调用名，内部都走 doPayment() ---------- */

    /** 常用签名：token/amount/currency */
    public Map<String, Object> pay(String token, long amount, String currency) {
        return doPayment(token, amount, currency);
    }

    /** 可能的别名（若控制器用这个名，也能对上） */
    public Map<String, Object> processPayment(String token, long amount, String currency) {
        return doPayment(token, amount, currency);
    }

    /** 另一常见别名 */
    public Map<String, Object> charge(String token, long amount, String currency) {
        return doPayment(token, amount, currency);
    }

    /* ---------- 核心支付逻辑 ---------- */

    private Map<String, Object> doPayment(String token, long amount, String currency) {
        String cur = (currency == null ? "usd" : currency).toLowerCase();

        // ========== 开发/联调：Stub 分支 ==========
        if (!stripeEnabled) {
            if (token == null || !token.startsWith("test_ok")) {
                return Map.of(
                        "status", "payment_failed",
                        "failure_message", "stub mode requires token starting with 'test_ok_'"
                );
            }
            String piId = "pi_stub_" + System.currentTimeMillis();
            return Map.of(
                    "status", "succeeded",
                    "payment_intent_id", piId
            );
        }

        // ========== 生产/预发：Stripe SPT 分支 ==========
        // 只接受 spt_*（Shared Payment Token）
        if (token == null || !token.startsWith("spt_")) {
            return Map.of(
                    "status", "payment_failed",
                    "failure_message", "invalid or non-SPT token in production (expected token starting with 'spt_')"
            );
        }

        if (this.stripeSecret.isEmpty()) {
            return Map.of(
                    "status", "payment_failed",
                    "failure_message", "Stripe secret is not configured"
            );
        }

        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(amount)              // 单位：最小货币单位（如 USD=美分）
                    .setCurrency(cur)               // 需与 SPT 的 usage_limits.currency 对齐
                    // 关键：SPT 顶层字段（若 SDK 暂无专门 setter，用 extra param）
                    .putExtraParam("shared_payment_granted_token", token)
                    .setConfirm(true)               // 创建即确认
                    // 可选增强：便于对账
                    .putMetadata("integration", "agentic-commerce")
                    .build();

            PaymentIntent pi = PaymentIntent.create(params);

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", pi.getStatus());          // 典型：'succeeded' 或 'requires_action'
            resp.put("payment_intent_id", pi.getId());
            return resp;

        } catch (StripeException e) {
            return Map.of(
                    "status", "payment_failed",
                    "failure_message", e.getMessage()
            );
        }
    }
}
