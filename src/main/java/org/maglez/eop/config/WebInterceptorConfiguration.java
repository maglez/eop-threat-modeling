package org.maglez.eop.config;

import org.maglez.eop.inbound.web.ReadRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the application's MVC interceptors.
 *
 * <p>Added by EOP-88 (ADR-051) for the read-route rate limiter. It is the first {@link WebMvcConfigurer} in the
 * application, and it deliberately configures nothing else: it does not replace Spring Boot's MVC
 * auto-configuration, because it is annotated {@code @Configuration} rather than {@code @EnableWebMvc}.
 *
 * <p>This class lives in the configuration package rather than beside the interceptor because wiring is
 * configuration's job; the interceptor itself is an interface adapter and knows nothing about which paths it
 * guards.
 */
@Configuration
public class WebInterceptorConfiguration implements WebMvcConfigurer {

    /**
     * The path the limiter guards. A pattern rather than a list of the routes that exist today, so that a read
     * route added later is limited without anyone remembering to come back here — the ticket asks for a
     * cross-cutting control, and a hand-maintained list is the opposite of one.
     */
    private static final String API_PATTERN = "/api/v1/**";

    /**
     * The server-sent-event stream, excluded from the limiter.
     *
     * <p>Not an oversight. An SSE request is asynchronous, so Spring runs {@code preHandle} a second time on the
     * ASYNC dispatch and a per-request counter would charge every stream twice. The stream's cost is already
     * bounded by two other controls: the per-session subscriber cap (EOP-20, ADR-034) and the ten-minute
     * {@code spring.mvc.async.request-timeout}. Limiting the rate at which a client may <em>reconnect</em> to the
     * stream is a real remaining gap, recorded as such in ADR-051 rather than half-solved here.
     */
    private static final String EVENT_STREAM_PATTERN = "/api/v1/sessions/*/events";

    private final ReadRateLimitInterceptor readRateLimitInterceptor;

    /**
     * Creates the configurer.
     *
     * @param readRateLimitInterceptor the read-route rate limiter to register; must not be null
     */
    public WebInterceptorConfiguration(final ReadRateLimitInterceptor readRateLimitInterceptor) {
        this.readRateLimitInterceptor = readRateLimitInterceptor;
    }

    /**
     * Registers the read-route rate limiter on the API surface.
     *
     * @param registry the registry to add interceptors to
     */
    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(readRateLimitInterceptor)
                .addPathPatterns(API_PATTERN)
                .excludePathPatterns(EVENT_STREAM_PATTERN);
    }
}
