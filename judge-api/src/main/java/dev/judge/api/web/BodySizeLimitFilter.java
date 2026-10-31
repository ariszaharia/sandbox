package dev.judge.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The source size check happens after the JSON has been parsed into memory, which is too late for a
 * huge body. Tomcat will not cap a JSON body on its own, so this does, before anything reads it.
 * Requiring a length also rules out a chunked body that never declares its size.
 */
final class BodySizeLimitFilter extends OncePerRequestFilter {

    private final long limitBytes;

    BodySizeLimitFilter(DataSize limit) {
        this.limitBytes = limit.toBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length < 0) {
            response.sendError(HttpStatus.LENGTH_REQUIRED.value(), "A Content-Length is required");
        } else if (length > limitBytes) {
            response.sendError(HttpStatus.CONTENT_TOO_LARGE.value(), "The body is larger than " + limitBytes + " bytes");
        } else {
            chain.doFilter(request, response);
        }
    }
}
