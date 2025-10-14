package com.example.acp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class OrderEventPublisher {

    private final String webhookUrl;        // 临时用 webhook.site，未来换成 OpenAI 提供的正式 URL
    private final String webhookSecret;     // 可选：用于签名；没有就不签
    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public OrderEventPublisher(
            @Value("${openai.webhook.url:}") String webhookUrl,
            @Value("${openai.webhook.secret:}") String webhookSecret) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
    }

    /** 对外：发送 order.created */
    public void publishOrderCreated(Map<String, Object> session) {
        sendEvent("order.created", session);
    }

    /** 对外：发送 order.updated（留作扩展：退款/物流更新等） */
    public void publishOrderUpdated(Map<String, Object> session) {
        sendEvent("order.updated", session);
    }

    /** 核心发送 */
    private void sendEvent(String eventType, Map<String, Object> session) {
        if (webhookUrl.isEmpty()) return; // 未配置就直接跳过

        try {
            Map<String, Object> payload = buildPayload(eventType, session);
            String body = mapper.writeValueAsString(payload);

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);

            // 可选签名：等 OpenAI 提供正式 secret/头名后，再把头名改为官方要求
            if (!webhookSecret.isEmpty()) {
                String ts = String.valueOf(System.currentTimeMillis() / 1000);
                String sig = hmacSha256Hex(ts + "." + body, webhookSecret);
                h.set("X-Acp-Timestamp", ts);
                h.set("X-Acp-Signature", "v1=" + sig);
            }

            http.exchange(webhookUrl, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        } catch (Exception e) {
            // 生产可加重试/告警
            e.printStackTrace();
        }
    }

    /** 事件载荷：贴近你当前的富状态结构 */
    @SuppressWarnings("unchecked")
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

    /** HMAC-SHA256 → hex */
    private static String hmacSha256Hex(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
