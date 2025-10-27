package com.example.acp.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/stripe/webhook")
public class StripeWebhookController {
    @Autowired private com.example.acp.store.SessionStore sessionStore;
    @Autowired private com.example.acp.service.OrderEventPublisher orderEventPublisher;
    @Autowired private com.example.acp.store.IdempotencyStore idempotencyStore;

    // 从环境/配置注入（Railway 上设置 STRIPE_WEBHOOK_SECRET 会映射到此）
    private final String signingSecret;
    // Stripe-Signature 时间戳容忍度（秒），默认 300
    private final long toleranceSec;

    private final ObjectMapper mapper = new ObjectMapper();

    public StripeWebhookController(
            @Value("${stripe.webhook.secret:}") String signingSecret,
            @Value("${stripe.webhook.tolerance.sec:300}") long toleranceSec
    ) {
        this.signingSecret = (signingSecret == null) ? "" : signingSecret.trim();
        this.toleranceSec = toleranceSec;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> handle(HttpServletRequest request,
                                         @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {
        // 前置校验：缺少必要头或密钥时直接返回
        if (signingSecret.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("missing webhook secret");
        }
        if (sigHeader == null || sigHeader.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("missing Stripe-Signature");
        }

        try {
            // 1) 读取原始 body（字节 → UTF-8 字符串），保持与 Stripe 生成签名的原文一致
            String payload;
            try (InputStream is = request.getInputStream()) {
                payload = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            // 2) 官方 SDK 验签（默认容忍 toleranceSec=300s）
            Event event = Webhook.constructEvent(payload, sigHeader, signingSecret, toleranceSec);

            // 3) 幂等去重（Stripe 有可能重试同一事件）
            String evtKey = "evt:" + event.getId();
            Map<String, Object> seen = idempotencyStore.getIfReady(evtKey);
            if (seen != null) {
                return ResponseEntity.ok("ok"); // 已处理
            }
            boolean begun = idempotencyStore.tryBegin(evtKey);
            if (!begun) {
                // 等待首个处理线程写入 commit，避免并发重复处理
                for (int i = 0; i < 10; i++) {
                    Map<String, Object> c = idempotencyStore.getIfReady(evtKey);
                    if (c != null) return ResponseEntity.ok("ok");
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
                return ResponseEntity.ok("ok");
            }

            // 4) 事件分发（保留你原有的业务逻辑）
            String type = event.getType();

            // SPT 令牌被使用（可能不带 PI）
            if ("shared_payment.granted_token.used".equals(type)) {
                System.out.println("[Stripe Webhook] shared_payment.granted_token.used");
                idempotencyStore.commit(evtKey, Map.of("ok", true));
                return ResponseEntity.ok("ok");
            }

            String piOrObjId = extractPiOrObjId(event);
            System.out.println("[Stripe Webhook] type=" + type + " piOrObjId=" + piOrObjId);

            if (piOrObjId != null) {
                switch (type) {
                    case "payment_intent.processing":
                        applyPiStatusAndPublish(piOrObjId, "processing", null, payload);
                        break;
                    case "payment_intent.succeeded":
                        applyPiStatusAndPublish(piOrObjId, "succeeded", null, payload);
                        break;
                    case "payment_intent.payment_failed":
                        String reason = extractFailureMessage(payload);
                        applyPiStatusAndPublish(piOrObjId, "failed", reason, payload);
                        break;
                    case "charge.refunded":
                        applyRefundAndPublish(payload);
                        break;
                    case "charge.dispute.created":
                        applyDisputeAndPublish(payload, "open");   // 争议开启
                        break;
                    case "charge.dispute.closed":
                        applyDisputeAndPublish(payload, "closed"); // 争议关闭
                        break;
                    default:
                        // 其它事件：不做处理，但仍返回 2xx，避免 Stripe 重试风暴
                }
            }

            // 5) 标记处理完成并返回 2xx
            idempotencyStore.commit(evtKey, Map.of("ok", true));
            return ResponseEntity.ok("ok");

        } catch (SignatureVerificationException e) {
            System.err.println("[Stripe Webhook] signature verification failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("signature verification failed"); // 4xx
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error"); // 5xx
        }
    }

    /** 兜底提取：优先反序列化具体类型；否则从原始 JSON 拿 id/payment_intent/charge */
    private String extractPiOrObjId(Event event) {
        try {
            EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
            if (deser != null && deser.getObject().isPresent()) {
                StripeObject obj = deser.getObject().get();
                if (obj instanceof PaymentIntent) return ((PaymentIntent) obj).getId();
                if (obj instanceof Charge) return ((Charge) obj).getPaymentIntent(); // 直接得到 PI
                if (obj instanceof Dispute) return ((Dispute) obj).getCharge();      // 先拿到 ch_，之后可再查到 PI
            }
            // 原始 JSON 兜底
            if (deser != null && deser.getRawJson() != null) {
                JsonNode root = mapper.readTree(deser.getRawJson());
                // 常见三种：PI 事件有 id，Charge 事件有 payment_intent，Dispute 事件有 charge
                if (root.hasNonNull("payment_intent")) return root.get("payment_intent").asText();
                if (root.hasNonNull("id"))             return root.get("id").asText();
                if (root.hasNonNull("charge"))         return root.get("charge").asText();
            }
            // 再不行就直接从 webhook 原始 payload 的 data.object 里解析一次
            JsonNode fallback = mapper.readTree(event.getData().getObject().toJson());
            if (fallback.hasNonNull("payment_intent")) return fallback.get("payment_intent").asText();
            if (fallback.hasNonNull("id"))             return fallback.get("id").asText();
            if (fallback.hasNonNull("charge"))         return fallback.get("charge").asText();
        } catch (Exception ignore) {}
        return null;
    }

    /** 把 PI 状态合并到会话并发送 order.updated */
    private void applyPiStatusAndPublish(String paymentIntentId, String paymentStatus, String failureMessage, String rawPayload) {
        try {
            Map<String, Object> session = sessionStore.findByPaymentIntentId(paymentIntentId);
            if (session == null) {
                System.out.println("[Stripe Webhook] no session found for PI " + paymentIntentId);
                return; // 找不到就跳过（可能是历史/测试事件）
            }
            session.put("payment_status", paymentStatus);
            if (failureMessage != null && !failureMessage.isBlank()) {
                session.put("failure_message", failureMessage);
            }
            // 你已有的会话键就是 "id"
            sessionStore.put(String.valueOf(session.get("id")), session);

            // 发权威更新
            orderEventPublisher.publishOrderUpdated(session);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 从 payment_intent.payment_failed 的原始 JSON 中抽取失败原因（尽量人类可读） */
    private String extractFailureMessage(String rawPayload) {
        try {
            JsonNode root = mapper.readTree(rawPayload);
            JsonNode obj  = root.path("data").path("object");
            // 尝试多种字段（不同原因落在不同字段）
            if (obj.hasNonNull("last_payment_error") && obj.path("last_payment_error").hasNonNull("message")) {
                return obj.path("last_payment_error").path("message").asText();
            }
            if (obj.hasNonNull("cancellation_reason")) {
                return obj.path("cancellation_reason").asText();
            }
            if (obj.hasNonNull("status")) {
                return "payment_intent status=" + obj.path("status").asText();
            }
        } catch (Exception ignored) {}
        return "payment_failed";
    }

    /** 解析 charge.refunded，把退款状态/金额合并进会话，并发送 order.updated */
    private void applyRefundAndPublish(String rawPayload) {
        try {
            JsonNode root = mapper.readTree(rawPayload);
            JsonNode obj  = root.path("data").path("object");  // 这是 charge 对象
            String chargeId        = obj.path("id").asText(null);
            String paymentIntentId = obj.path("payment_intent").asText(null);

            long amount      = obj.path("amount").asLong(0);             // 原始扣款金额（最小货币单位）
            long refundedAmt = obj.path("amount_refunded").asLong(0);    // 已退款金额
            boolean refunded = obj.path("refunded").asBoolean(false);    // 是否全部退款

            String refundStatus;
            if (refundedAmt <= 0)                          refundStatus = "none";
            else if (!refunded || (refundedAmt < amount))  refundStatus = "partial";
            else                                            refundStatus = "refunded";

            // 用 PI 反查会话（charge.refunded 事件里通常带有 payment_intent）
            Map<String, Object> session = sessionStore.findByPaymentIntentId(paymentIntentId);
            if (session == null) {
                System.out.println("[Stripe Webhook] charge.refunded but no session found, pi=" + paymentIntentId + " ch=" + chargeId);
                return; // 找不到就跳过（可能是与业务无关的测试事件）
            }

            session.put("refund_status", refundStatus);
            session.put("refund_amount", refundedAmt);
            if (chargeId != null) session.put("charge_id", chargeId);

            // 持久化并广播权威更新
            sessionStore.put(String.valueOf(session.get("id")), session);
            orderEventPublisher.publishOrderUpdated(session);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 解析 charge.dispute.{created|closed}，合并争议状态并发送 order.updated */
    private void applyDisputeAndPublish(String rawPayload, String disputeStatus) {
        try {
            JsonNode root = mapper.readTree(rawPayload);
            JsonNode obj  = root.path("data").path("object");   // dispute 对象
            String disputeId = obj.path("id").asText(null);
            String chargeId  = obj.path("charge").asText(null);

            // 取回 charge -> payment_intent（需要已配置 Stripe.apiKey）
            String paymentIntentId = null;
            if (chargeId != null) {
                try {
                    com.stripe.model.Charge ch = com.stripe.model.Charge.retrieve(chargeId);
                    paymentIntentId = ch.getPaymentIntent();
                } catch (Exception e) {
                    System.err.println("[Stripe Webhook] retrieve charge failed: " + e.getMessage());
                }
            }

            if (paymentIntentId == null) {
                System.out.println("[Stripe Webhook] dispute event but no PI (charge=" + chargeId + ")");
                return;
            }

            Map<String, Object> session = sessionStore.findByPaymentIntentId(paymentIntentId);
            if (session == null) {
                System.out.println("[Stripe Webhook] dispute " + disputeStatus + " but no session found, pi=" + paymentIntentId);
                return;
            }

            // 合并争议状态
            session.put("dispute_status", disputeStatus); // open / closed
            if (disputeId != null) session.put("dispute_id", disputeId);
            if (chargeId  != null) session.put("charge_id", chargeId);

            // 持久化并发权威更新
            sessionStore.put(String.valueOf(session.get("id")), session);
            orderEventPublisher.publishOrderUpdated(session);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
