package com.example.acp.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * PaymentService
 * - 两种模式：
 *   (1) 模拟模式（stripe.enabled=false）：不调用 Stripe，直接返回成功占位，便于联调
 *   (2) 生产模式（stripe.enabled=true）：使用 Shared Payment Token (SPT, spt_...) 在 Stripe 创建+确认 PaymentIntent
 *
 * 环境变量建议：
 *   STRIPE_ENABLED=true|false
 *   STRIPE_API_KEY=sk_live_...       // 或 STRIPE_SECRET_KEY
 *   STRIPE_CONNECT_ACCOUNT=acct_xxx  // 可选：Connect 被连商户账号
 */
public class PaymentService {

    private final boolean stripeEnabled;
    private final String stripeApiKey;
    private final String defaultStripeConnectAccount; // 可选

    public PaymentService() {
        // 修正：提供重载，最后一个参数是 boolean，不再走 varargs 的 String...
        this.stripeEnabled = getEnvFlag("STRIPE_ENABLED", "stripe.enabled", false);

        this.stripeApiKey = firstNonBlank(
                System.getenv("STRIPE_API_KEY"),
                System.getenv("STRIPE_SECRET_KEY"),
                System.getenv("stripe.api.key")
        );

        this.defaultStripeConnectAccount = firstNonBlank(
                System.getenv("STRIPE_CONNECT_ACCOUNT"),
                System.getenv("STRIPE_ACCOUNT_ID"),
                System.getenv("stripe.account.id")
        );

        if (this.stripeEnabled) {
            if (isBlank(this.stripeApiKey)) {
                throw new IllegalStateException("Stripe enabled but STRIPE_API_KEY/STRIPE_SECRET_KEY is not configured");
            }
            Stripe.apiKey = this.stripeApiKey;
        }
    }

    /** 向下兼容原有调用 */
    public Map<String, Object> charge(String paymentMethodToken, long amountCents, String currency) {
        return charge(paymentMethodToken, amountCents, currency, null, null, null);
    }

    /**
     * 推荐入口：允许透传 Idempotency-Key 与 Connect 账户
     * @param paymentMethodToken SPT（stripe.enabled=true 时必须以 spt_ 开头）
     * @param amountCents        最小货币单位（来自会话 totals 中 type=total）
     * @param currency           小写币种，如 usd
     * @param idempotencyKey     从 HTTP 头透传，保证支付层幂等
     * @param connectAccountId   可选：指定被连商户（acct_xxx）；为空则使用默认或主账号
     * @param metadata           可选：写入 PaymentIntent.metadata 的键值（如 checkout_session_id）
     */
    public Map<String, Object> charge(
            String paymentMethodToken,
            long amountCents,
            String currency,
            String idempotencyKey,
            String connectAccountId,
            Map<String, String> metadata
    ) {

        String cur = (currency == null) ? null : currency.toLowerCase(Locale.ROOT);

        if (amountCents <= 0) {
            return fail("Invalid amount: " + amountCents);
        }
        if (isBlank(cur)) {
            return fail("Missing currency");
        }

        // ---- 模拟模式：不调用 Stripe，直接返回成功（联调用） ----
        if (!stripeEnabled) {
            Map<String, Object> ok = new HashMap<>();
            ok.put("status", "succeeded");
            ok.put("payment_intent_id", "pi_stub_" + Instant.now().toEpochMilli());
            ok.put("payment_intent_status", "succeeded");
            return ok;
        }

        // ---- 生产模式：必须是有效 SPT ----
        if (isBlank(paymentMethodToken) || !paymentMethodToken.startsWith("spt_")) {
            return fail("A valid Shared Payment Token (spt_...) is required when Stripe is enabled");
        }

        try {
            // 构建 PaymentIntent 参数
            PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(cur)
                    // 关键：SPT 通过 shared_payment_granted_token 传入
                    .putExtraParam("shared_payment_granted_token", paymentMethodToken)
                    // 直接创建并确认
                    .setConfirm(true);

            // metadata：Stripe Java SDK 用 putMetadata，而不是 Metadata.Builder
            if (metadata != null) {
                for (Map.Entry<String, String> e : metadata.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        builder.putMetadata(e.getKey(), e.getValue());
                    }
                }
            }
            builder.putMetadata("integration", "openai-agentic-commerce");

            PaymentIntentCreateParams params = builder.build();

            // RequestOptions：幂等键 & 可选 Connect
            RequestOptions.RequestOptionsBuilder ro = RequestOptions.builder();
            if (!isBlank(idempotencyKey)) {
                ro.setIdempotencyKey(idempotencyKey);
            }

            String acct = !isBlank(connectAccountId) ? connectAccountId : defaultStripeConnectAccount;
            if (!isBlank(acct)) {
                ro.setStripeAccount(acct);
            }
            RequestOptions requestOptions = ro.build();

            // 真正创建+确认 PaymentIntent
            PaymentIntent pi = PaymentIntent.create(params, requestOptions);

            // 成功态：succeeded；如你要“先授权后捕获”，也接受 requires_capture
            String s = safe(pi.getStatus());
            if ("succeeded".equalsIgnoreCase(s) || "requires_capture".equalsIgnoreCase(s)) {
                Map<String, Object> ok = new HashMap<>();
                ok.put("status", "succeeded");
                ok.put("payment_intent_id", pi.getId());
                ok.put("payment_intent_status", s);
                return ok;
            }

            // 其它状态视为失败
            String failure = "Stripe PaymentIntent status=" + s;
            if (pi.getLastPaymentError() != null && pi.getLastPaymentError().getMessage() != null) {
                failure += " | " + pi.getLastPaymentError().getMessage();
            }
            return fail(failure);

        } catch (StripeException e) {
            String msg = e.getMessage();
            if (e.getStripeError() != null && e.getStripeError().getMessage() != null) {
                msg = e.getStripeError().getMessage();
            }
            return fail("Stripe error: " + msg);
        } catch (Exception e) {
            return fail("Unexpected error: " + e.getMessage());
        }
    }

    // ---------- 工具方法 ----------

    /** 重载：两个 key + 默认值（boolean） */
    private static boolean getEnvFlag(String key1, String key2, boolean defaultVal) {
        String v1 = System.getenv(key1);
        if (!isBlank(v1)) return truthy(v1);
        String v2 = System.getenv(key2);
        if (!isBlank(v2)) return truthy(v2);
        return defaultVal;
    }

    private static boolean truthy(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (!isBlank(v)) return v;
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "payment_failed");
        m.put("failure_message", message);
        return m;
    }
}
