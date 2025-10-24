package com.example.acp.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Component
public class ResponseHeaderEchoFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // —— 从请求读取 —— //
        String idem = header(req, "Idempotency-Key");
        String requestId = header(req, "Request-Id");

        // 如果对方没传 Request-Id，给自己日志用生成一个（规范通常会传，这里只是保底）
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        // —— 在响应回显（规范要求）—— //
        if (idem != null && !idem.isBlank()) {
            res.setHeader("Idempotency-Key", idem);
        }
        res.setHeader("Request-Id", requestId);

        // （可选，但推荐）一起回：时间戳 & API 版本，方便排错/对齐
        // Timestamp 要用 RFC3339；示例见规范的“Common features”
        res.setHeader("Timestamp", Instant.now().toString());
        String apiVersion = System.getenv("OPENAI_COMMERCE_API_VERSION");
        if (apiVersion != null && !apiVersion.isBlank()) {
            res.setHeader("API-Version", apiVersion);
        }

        chain.doFilter(req, res);
    }

    private static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
