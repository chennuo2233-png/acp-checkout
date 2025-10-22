package com.example.acp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 向 OpenAI 提供的 Webhook 发布订单事件（最小合规版）
 * - 仅发送必须的头：
 *   - Content-Type: application/json
 *   - Signature: Base64( HMAC-SHA256( raw_request_body ) )
 *   - Timestamp: RFC 3339（用于记录时序；是否强校验由对端决定）
 * - 事件类型：order.created / order.updated
 */
@Service
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final String webhookUrl;     // 来自 OPENAI_WEBHOOK_URL
    private final String webhookSecret;  // 来自 OPENAI_WEBHOOK_SECRET
    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public OrderEventPublisher(
            @Value("${openai.webhook.url:}") String webhookUrl,
            @Value("${openai.webhook.secret:}") String webhookSecret
    ) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();      // 由 OPENAI_WEBHOOK_URL 提供
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim(); // 由 OPENAI_WEBHOOK_SECRET 提供
    }

    /** 对外：发送 order.created */
    public void publishOrderCreated(Map<String, Object> session) {
        sendEvent("order.created", session);
    }

    /** 对外：发送 order.updated */
    public void publishOrderUpdated(Map<String, Object> session) {
        sendEvent("order.updated", session);
    }

    /** 核心发送逻辑 */
    private void sendEvent(String eventType, Map<String, Object> session) {
        if (webhookUrl.isEmpty()) {
            log.debug("[OrderEventPublisher] OPENAI_WEBHOOK_URL 未配置，跳过事件 {}。", eventType);
            return; // 未配置则跳过（不影响主流程）
        }

        try {
            Map<String, Object> payload = buildPayload(eventType, session);
            String body = mapper.writeValueAsString(payload);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 生成签名：Base64(HMAC-SHA256(raw_body))
            if (!webhookSecret.isEmpty()) {
                String signature = hmacSha256Base64(body.getBytes(StandardCharsets.UTF_8), webhookSecret);
                headers.set("Signature", signature);
            }

            // 附带时间戳（RFC 3339），便于对端记录/约束时效
            headers.set("Timestamp", Instant.now().toString());

            ResponseEntity<String> resp = http.exchange(
                    webhookUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

            if (log.isDebugEnabled()) {
                log.debug("[OrderEventPublisher] 事件 {} 已发送。HTTP {}，响应体：{}",
                        eventType, resp.getStatusCodeValue(), resp.getBody());
            }
        } catch (Exception e) {
            // 最小实现：仅记录错误。你可以后续在这里接入重试/告警。
            log.error("[OrderEventPublisher] 事件 {} 发送失败：{}", eventType, e.toString(), e);
        }
    }

    /** 事件负载：贴近你当前会话结构，保持“权威状态” */
    private Map<String, Object> buildPayload(String eventType, Map<String, Object> session) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event_type", eventType);
        payload.put("occurred_at", OffsetDateTime.now().toString());
        payload.put("checkout_session_id", session.get("id"));
        payload.put("order", session.get("order"));
        payload.put("currency", session.get("currency"));
        payload.put("totals", session.get("totals"));
        payload.put("line_items", session.get("line_items"));
        payload.put("links", session.get("links"));
        return payload;
    }

    /** HMAC-SHA256 → Base64 */
    private static String hmacSha256Base64(byte[] data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal(data);
        return Base64.getEncoder().encodeToString(out);
    }
}
