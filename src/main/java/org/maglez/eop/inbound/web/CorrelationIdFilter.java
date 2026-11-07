package org.maglez.eop.inbound.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Servlet filter that establishes a correlation ID for every HTTP request (EOP-117).
 *
 * <p>On each request the filter:
 * <ol>
 *   <li>Reads the {@value #HEADER_NAME} request header.</li>
 *   <li>If the header is absent or blank, generates a fresh {@link UUID} as the ID.</li>
 *   <li>Puts the ID into the SLF4J {@link MDC} under the key {@value #MDC_KEY} so that every
 *       log line emitted during the request carries it automatically — in the plain-text pattern
 *       via {@code %X{correlationId}} and in the JSON encoder as an MDC field.</li>
 *   <li>Echoes the ID back to the caller in the {@value #HEADER_NAME} response header so that
 *       clients can correlate their own logs with server-side traces.</li>
  *   <li>Clears the MDC entry via a try-with-resources {@code MdcCleaner} so that no ID leaks
 *       into a subsequent request on the same thread (thread-pool reuse).</li>
 * </ol>
 *
 * <p><strong>Security note:</strong> the filter reads {@value #HEADER_NAME} directly from the
 * request and never inspects {@code X-Forwarded-For} or any other proxy header. This is
 * consistent with {@code server.forward-headers-strategy: none} (ADR-021) and the principle
 * that the application must not trust headers it cannot verify. A caller-supplied correlation ID
 * is accepted as-is for traceability convenience; it is never used for authentication,
 * authorisation or rate-limiting decisions.
 *
 * <p>The filter is registered by {@code ObservabilityConfiguration} at
 * {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE} so the MDC key is available to
 * every other filter, interceptor and controller in the chain.
 */
public class CorrelationIdFilter implements Filter {

    /**
     * The HTTP header name used to carry the correlation ID in both directions.
     *
     * <p>Inbound: read from the request; if absent a UUID is generated.
     * Outbound: echoed back in the response so callers can correlate their own logs.
     */
    public static final String HEADER_NAME = "X-Correlation-Id";

    /**
     * The MDC key under which the correlation ID is stored for the duration of the request.
     *
     * <p>Referenced in {@code logback-spring.xml} via {@code %X{correlationId}} in the
     * plain-text pattern and emitted automatically as an MDC field by the JSON encoder.
     */
    public static final String MDC_KEY = "correlationId";

    /**
     * Processes the request: resolves or generates the correlation ID, populates the MDC,
     * delegates to the rest of the filter chain, and clears the MDC on completion.
     *
     * @param request  the incoming servlet request
     * @param response the outgoing servlet response
     * @param chain    the remainder of the filter chain
     * @throws IOException      if an I/O error occurs during filtering
     * @throws ServletException if a servlet error occurs during filtering
     */
    @Override
    public void doFilter(final ServletRequest request,
                         final ServletResponse response,
                         final FilterChain chain) throws IOException, ServletException {

        final String correlationId = resolveCorrelationId(request);
        MDC.put(MDC_KEY, correlationId);

        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader(HEADER_NAME, correlationId);
        }

        try (MdcCleaner ignored = new MdcCleaner(MDC_KEY)) {
            chain.doFilter(request, response);
        }
    }

    /**
     * Resolves the correlation ID for the current request.
     *
     * <p>If the request carries a non-blank {@value #HEADER_NAME} header that value is used
     * unchanged. Otherwise a fresh random UUID is generated. The result is never null or blank.
     *
     * @param request the current servlet request
     * @return the correlation ID to use for this request; never null or blank
     */
    private static String resolveCorrelationId(final ServletRequest request) {
        if (request instanceof HttpServletRequest httpRequest) {
            final String header = httpRequest.getHeader(HEADER_NAME);
            if (header != null && !header.isBlank()) {
                return header;
            }
        }
        return UUID.randomUUID().toString();
    }

    /**
     * An {@link AutoCloseable} that removes a single MDC key when closed.
     *
     * <p>Used in a try-with-resources block so that the MDC entry is guaranteed to be
     * cleared after the filter chain completes, whether normally or by exception, without
     * requiring a {@code try-finally} construct (which the project's Checkstyle
     * {@code RightCurly: alone_or_singleline} rule does not permit).
     */
    private static final class MdcCleaner implements AutoCloseable {

        private final String key;

        /**
         * Creates a cleaner for the given MDC key.
         *
         * @param key the MDC key to remove on {@link #close()}; must not be null
         */
        MdcCleaner(final String key) {
            this.key = key;
        }

        /**
         * Removes the MDC key supplied at construction time.
         */
        @Override
        public void close() {
            MDC.remove(key);
        }
    }
}
