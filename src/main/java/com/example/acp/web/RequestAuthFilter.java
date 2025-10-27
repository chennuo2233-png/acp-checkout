package com.example.acp.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

/**
 * 最小必需的入站认证与验签过滤器（单商家版）
 * - Authorization: Bearer <OPENAI_AUTH_TOKEN>
 * - Signature: Base64( HMAC-SHA256( OPENAI_SIGNING_SECRET, raw_request_body_bytes ) )
 *
 * 只做这两项校验；失败直接 401，成功放行。
 */
@Component
public class RequestAuthFilter extends OncePerRequestFilter {

    private static final String H_AUTH = "Authorization";
    private static final String H_SIGNATURE = "Signature";

    private final String expectedBearer;   // 形如 api_key_******
    private final byte[] signingSecret;    // HMAC 密钥原始字节

    public RequestAuthFilter() {
        String token = System.getenv("OPENAI_AUTH_TOKEN");
        String secret = System.getenv("OPENAI_SIGNING_SECRET");

        // 不中断启动，但若缺失将拒绝所有请求（返回 401）
        this.expectedBearer = safeTrim(token);
        this.signingSecret = safeBytes(secret);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // 放过 CORS 预检（若你完全不需要可删掉这段）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // ======= 新增：路径白名单放行（Stripe Webhook + Product Feed）======
        // 说明：
        //  - /webhook/stripe 与 /webhook/stripe/** ：给 Stripe 回调使用，采用 Stripe-Signature 验签，不走本过滤器的 Bearer/HMAC。
        //  - /product_feed ：你选择“拉取模式”，OpenAI 拉取时不携带你自定义签名头，这里直接放行。
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        // 去掉查询串，避免 /product_feed?x=1 的情况
        int q = uri.indexOf('?');
        if (q >= 0) uri = uri.substring(0, q);
        
        if ("/product_feed".equals(uri)
        || "/webhook/stripe".equals(uri)
        || uri.startsWith("/webhook/stripe/")) {
            chain.doFilter(request, response);
            return;
        }
        // =============================================================

        // 包一层可重复读取 body 的 wrapper（我们要先读原始字节验签，再让后续链路照常读）
        CachedBodyRequestWrapper wrapped = new CachedBodyRequestWrapper(request);

        // 1) 校验 Authorization
        String auth = header(wrapped, H_AUTH);
        if (!validAuth(auth)) {
            writeUnauthorized(response, "invalid_authorization", "Authorization header missing or invalid");
            return;
        }

        // 2) 校验 Signature（对原始 body 做 HMAC）
        String sigHeader = header(wrapped, H_SIGNATURE);
        if (sigHeader == null) {
            writeUnauthorized(response, "signature_missing", "Signature header is required");
            return;
        }

        byte[] rawBody = wrapped.getCachedBody();
        if (!validSignature(sigHeader, rawBody)) {
            writeUnauthorized(response, "signature_invalid", "Request body signature does not match");
            return;
        }

        // 都通过，放行（下游还能正常读取 body）
        chain.doFilter(wrapped, response);
    }

    // ===== 工具方法 =====

    private boolean validAuth(String authHeader) {
        if (isBlank(expectedBearer)) return false; // 未配置密钥 -> 一律拒绝
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        String token = authHeader.substring("Bearer ".length()).trim();
        return Objects.equals(token, expectedBearer);
    }

    private boolean validSignature(String signatureHeader, byte[] body) {
        if (signingSecret == null || signingSecret.length == 0) return false; // 未配置密钥 -> 一律拒绝
        try {
            // 计算 HMAC-SHA256
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            byte[] expected = mac.doFinal(body == null ? new byte[0] : body);

            // 解析来访签名（Base64）
            byte[] provided = Base64.getDecoder().decode(signatureHeader.trim());

            // 常量时间比较（防时序攻击）
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception e) {
            return false;
        }
    }

    private static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String safeTrim(String s) {
        return s == null ? null : s.trim();
    }

    private static byte[] safeBytes(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 可重复读取请求体的简单包装器：
     * - 在构造时把原始字节读进缓存
     * - 下游每次 getInputStream()/getReader() 都从缓存里读取
     */
    private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            cachedBody = request.getInputStream().readAllBytes();
        }

        byte[] getCachedBody() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            final ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override public int read() { return bais.read(); }
                @Override public boolean isFinished() { return bais.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) { /* no-op */ }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

        private static void writeUnauthorized(HttpServletResponse res, String code, String message) throws IOException {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json; charset=UTF-8");
            // 可选：防止缓存
            res.setHeader("Cache-Control", "no-store");
            res.setHeader("Pragma", "no-cache");
            
            // 统一错误体，便于前端/ChatGPT 端识别
            String body = String.format(
        "{\"error\":{\"code\":\"%s\",\"message\":\"%s\"}}",
        escapeJson(code),
        escapeJson(message)
        );
        res.getWriter().write(body);
        res.getWriter().flush();
        }

        private static String escapeJson(String s) {
            if (s == null) return "";
            // 极简转义：只处理最常见字符即可
            return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
        }

}
