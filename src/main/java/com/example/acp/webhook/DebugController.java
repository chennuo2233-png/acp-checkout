package com.example.acp.webhook;

import com.example.acp.store.SessionStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 临时调试：把 Stripe 的 payment_intent_id 绑定到某个 checkout_session
 * 仅开发/测试使用；生产请关闭（debug.bind.enabled=false）。
 */
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    @Autowired private SessionStore sessionStore;

    @Value("${debug.bind.enabled:true}")
    private boolean enabled;

    @PostMapping("/bind_pi")
    public ResponseEntity<Map<String, Object>> bindPaymentIntent(
            @RequestBody Map<String, Object> req) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "debug_binding_disabled"));
        }
        String csId = String.valueOf(req.get("checkout_session_id"));
        String piId = String.valueOf(req.get("payment_intent_id"));
        if (csId == null || csId.isBlank() || piId == null || piId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "missing_fields",
                                 "message", "need checkout_session_id and payment_intent_id"));
        }
        Map<String, Object> session = sessionStore.get(csId);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "session_not_found", "checkout_session_id", csId));
        }
        session.put("payment_intent_id", piId);
        sessionStore.put(csId, session);
        return ResponseEntity.ok(session);
    }
}
