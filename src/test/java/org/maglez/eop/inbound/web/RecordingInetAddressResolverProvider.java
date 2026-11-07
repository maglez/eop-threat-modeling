package org.maglez.eop.inbound.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Test-only {@link InetAddressResolverProvider} that records every name-lookup attempt
 * made while {@link #ACTIVE} is {@code true}.
 *
 * <p>Registered via {@code META-INF/services} so the JVM loads it at startup. The flag
 * is {@code false} by default, so every test that does not explicitly arm it sees no
 * change in behaviour. Only {@link IpLiteralsTest.DnsFreeLiteralGuard} arms the flag,
 * and it disarms it in a {@code finally} block so the flag cannot leak between tests.
 *
 * <p>When armed, any call to the resolver's {@code lookupByName} increments
 * {@link #LOOKUP_COUNT}. The test asserts the count stays at zero, which proves that
 * {@code IpLiterals} never reached {@link java.net.InetAddress#getByName(String)} for
 * the inputs under test. A value-only assertion ({@code assertThat(...).isEmpty()}) is
 * insufficient here: {@code parseIpv6} catches {@link UnknownHostException} and returns
 * empty, so deleting {@code isLiteralStart} from {@code IpLiterals} would still return
 * empty while silently paying the resolver cost. This spy catches that mutation.
 */
public final class RecordingInetAddressResolverProvider extends InetAddressResolverProvider {

    /** Arms the spy. Set to {@code true} only inside a try/finally in the test. */
    static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

    /** Incremented once per {@code lookupByName} call while {@link #ACTIVE} is true. */
    static final AtomicInteger LOOKUP_COUNT = new AtomicInteger(0);

    @Override
    public InetAddressResolver get(final Configuration configuration) {
        return new RecordingResolver(configuration.builtinResolver());
    }

    @Override
    public String name() {
        return "RecordingInetAddressResolverProvider";
    }

    private static final class RecordingResolver implements InetAddressResolver {

        private final InetAddressResolver delegate;

        RecordingResolver(final InetAddressResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Stream<InetAddress> lookupByName(
                final String host,
                final LookupPolicy lookupPolicy) throws UnknownHostException {
            if (ACTIVE.get()) {
                LOOKUP_COUNT.incrementAndGet();
            }
            return delegate.lookupByName(host, lookupPolicy);
        }

        @Override
        public String lookupByAddress(final byte[] addr) throws UnknownHostException {
            return delegate.lookupByAddress(addr);
        }
    }
}
