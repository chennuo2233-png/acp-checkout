package com.example.acp.webhook;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


@RestController
@RequestMapping("/api/stripe/webhook")
public class StripeWebhookController {

    // 从 Railway 变量注入（测试与生产会有不同的 whsec_...）
    private final String signingSecret;
    // 可选：容差秒数（Stripe-Signature 里的时间戳容忍度）
    private final long toleranceSec;
    private final ObjectMapper mapper = new ObjectMapper();


    public StripeWebhookController(
            @Value("${stripe.webhook.secret:}") String signingSecret,
            @Value("${stripe.webhook.tolerance.sec:300}") long toleranceSec
    ) {
        this.signingSecret = signingSecret == null ? "" : signingSecret.trim();
        this.toleranceSec = toleranceSec;
    }

    @PostMapping
public ResponseEntity<String> handle(@RequestBody String payload,
                                     @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {
    if (signingSecret.isEmpty()) return ResponseEntity.status(HttpStatus.FORBIDDEN).body("missing webhook secret");
    if (sigHeader == null || sigHeader.isBlank()) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("missing Stripe-Signature");

    try {
        Event event = Webhook.constructEvent(payload, sigHeader, signingSecret, toleranceSec);

        String type = event.getType();
        String piOrObjId = extractPiOrObjId(event);  // ← 改这里
        System.out.println("[Stripe Webhook] type=" + type + " piOrObjId=" + piOrObjId);

        return ResponseEntity.ok("ok");
    } catch (SignatureVerificationException e) {
        System.err.println("[Stripe Webhook] signature verification failed: " + e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("signature verification failed");
    } catch (Exception e) {
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error");
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
            if (root.hasNonNull("id")) return root.get("id").asText();
            if (root.hasNonNull("charge")) return root.get("charge").asText();
        }
        // 再不行就直接从 webhook 原始 payload 的 data.object 里解析一次
        JsonNode fallback = mapper.readTree(event.getData().getObject().toJson());
        if (fallback.hasNonNull("payment_intent")) return fallback.get("payment_intent").asText();
        if (fallback.hasNonNull("id")) return fallback.get("id").asText();
        if (fallback.hasNonNull("charge")) return fallback.get("charge").asText();
    } catch (Exception ignore) {}
    return null;
}

}
