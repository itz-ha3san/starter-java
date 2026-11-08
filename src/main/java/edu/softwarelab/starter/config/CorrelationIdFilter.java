package edu.softwarelab.starter.config;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Propagates the {@code X-Correlation-ID} header across the request lifecycle.
 *
 * <p>On every inbound HTTP request:
 * <ol>
 *   <li>If the request carries an {@code X-Correlation-ID} header, use it.</li>
 *   <li>Otherwise generate a fresh UUID.</li>
 *   <li>Place the value in the SLF4J {@link MDC} under the key
 *       {@code correlation_id} so every log line for this request carries the
 *       same id (per GEN-11 structured logging).</li>
 *   <li>Echo it back on the response as {@code X-Correlation-ID}.</li>
 * </ol>
 *
 * <p>The MDC value is also read by {@code GlobalExceptionHandler} so that
 * problem+json responses include the correlation id.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlation_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String correlationId = request.getHeader(HEADER_NAME);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        } else {
            correlationId = correlationId.trim();
            // Normalize: if it's not a valid UUID, fall back to a fresh one so
            // downstream code can safely parse the value.
            try {
                UUID.fromString(correlationId);
            } catch (IllegalArgumentException ex) {
                log.debug("Malformed X-Correlation-ID header '{}'; generating fresh id",
                        correlationId);
                correlationId = UUID.randomUUID().toString();
            }
        }

        try {
            MDC.put(MDC_KEY, correlationId);
            response.setHeader(HEADER_NAME, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
