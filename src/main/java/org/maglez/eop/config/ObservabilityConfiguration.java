package org.maglez.eop.config;

import org.maglez.eop.inbound.web.CorrelationIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers cross-cutting observability infrastructure for the application (EOP-117).
 *
 * <p>Currently this class registers a single bean: the {@link CorrelationIdFilter}, which
 * populates the SLF4J MDC with a per-request correlation ID so that every log line emitted
 * during a request is traceable. The filter is placed at
 * {@link Ordered#HIGHEST_PRECEDENCE} so the MDC key is available to every other filter,
 * interceptor and controller in the chain.
 *
 * <p>This class lives in the configuration package rather than beside the filter because
 * wiring is configuration's job; the filter itself is an interface adapter and knows nothing
 * about its registration order or URL mapping.
 */
@Configuration
public class ObservabilityConfiguration {

    /**
     * Registers the {@link CorrelationIdFilter} as a servlet filter with the highest possible
     * precedence, ensuring the MDC correlation ID is set before any other filter or interceptor
     * runs.
     *
     * <p>The filter is mapped to all URLs ({@code /*}) so that every request — including
     * actuator, static resources and API routes — carries a correlation ID.
     *
     * @return a configured {@link FilterRegistrationBean} wrapping the correlation filter
     */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        final FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("correlationIdFilter");
        return registration;
    }
}
