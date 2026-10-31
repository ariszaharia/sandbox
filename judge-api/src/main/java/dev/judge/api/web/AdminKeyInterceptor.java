package dev.judge.api.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Admin endpoints take a shared key. It fails closed: with no key configured they refuse everyone.
 * Runs before the body is parsed, so an unauthenticated caller costs nothing.
 */
final class AdminKeyInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Admin-Key";

    private final byte[] key;

    AdminKeyInterceptor(String key) {
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String presented = request.getHeader(HEADER);
        if (key.length > 0 && presented != null && MessageDigest.isEqual(key, presented.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.sendError(HttpStatus.UNAUTHORIZED.value(), "Missing or wrong " + HEADER);
        return false;
    }
}
