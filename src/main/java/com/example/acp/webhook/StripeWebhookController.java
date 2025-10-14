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

@RestController
@RequestMapping("/api/stripe/webhook")
public class StripeWebhookController {

    // 从 Railway 变量注入（测试与生产会有不同的 whsec_...）
    private final String signingSecret;
    // 可选：容差秒数（Stripe-Signature 里的时间戳容忍度）
    private final long toleranceSec;

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
        if (signingSecret.isEmpty()) {
            // 未配置秘钥时直接拒绝（生产必须配置）
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("missing webhook secret");
        }
        if (sigHeader == null || sigHeader.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("missing Stripe-Signature");
        }

        try {
            // 验签：官方要求用端点秘钥校验 Stripe-Signature
            Event event = Webhook.constructEvent(payload, sigHeader, signingSecret, toleranceSec);

            // 先只打印关键字段，下一步再做业务映射
            String type = event.getType();
            String objId = extractObjectId(event);

            System.out.println("[Stripe Webhook] type=" + type + " objId=" + objId);

            // 只确认已接收
            return ResponseEntity.ok("ok");
        } catch (SignatureVerificationException e) {
            // 验签失败必须 400，Stripe 会重试
            System.err.println("[Stripe Webhook] signature verification failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("signature verification failed");
        } catch (Exception e) {
            // 其他异常建议 500，Stripe 会按退避策略重试
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error");
        }
    }
    private String extractObjectId(Event event) {
    EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
    if (deser == null || !deser.getObject().isPresent()) {
        return null; // 有些测试事件可能不给具体对象
    }
    StripeObject obj = deser.getObject().get();
    if (obj instanceof PaymentIntent) {
        return ((PaymentIntent) obj).getId();
    } else if (obj instanceof Charge) {
        return ((Charge) obj).getId();
    } else if (obj instanceof Dispute) {
        return ((Dispute) obj).getId();
    }
    // 其它类型暂不关心
    return null;
}

}
