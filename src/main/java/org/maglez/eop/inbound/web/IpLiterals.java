package org.maglez.eop.inbound.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.OptionalInt;
import org.maglez.eop.outbound.web.InMemoryJoinAttemptLimiter;

/**
 * Parses IP addresses written as text, without consulting DNS.
 *
 * <p>The obvious implementation, {@link InetAddress#getByName(String)} on its own, is not
 * safe here. Handed something that is not a literal it performs a name lookup, so a
 * malformed entry in the trusted-proxy allow-list would become a network call during
 * startup, and a hostile {@code X-Forwarded-For} value would become a network call on the
 * request path. An allow-list whose meaning depends on what a resolver says today is also
 * not an allow-list. Every method here therefore refuses anything that is not a literal.
 *
 * <p>Two shapes are recognised. Text containing a colon is delegated to the platform, but
 * only after {@link #isAddressText(String)} has checked it character by character, and that
 * check is the whole of what keeps this class off the resolver. The platform offers no such
 * guarantee, and the belief that a colon is itself protection is false: measured on Java
 * 21.0.12, {@code getByName} attempts literal parsing only when the first character is a
 * hex digit or a colon, so {@code zzz:80}, {@code localhost:80}, {@code _:_} and even
 * {@code .a:b} are all handed to {@code getaddrinfo}, taking up to a quarter of a second to
 * fail with a resolver error rather than a parse error. Nothing may reach that call unless
 * its own spelling has already ruled a hostname out. Everything else is parsed as a strict
 * dotted quad by hand, because the platform has historically accepted abbreviated and octal
 * forms in which {@code 010.1.1.1} and {@code 8.1.1.1} name the same host. Two spellings of
 * one address are two rate-limiter buckets, which is the very defect this class exists to
 * help close, so the loose forms are rejected instead.
 *
 * <p>{@link #canonical(String)} exists for the same reason. An address is used as a key in
 * {@link InMemoryJoinAttemptLimiter}, and {@code 10.0.0.1} and {@code ::ffff:10.0.0.1} are
 * the same client. Reducing both to one spelling before counting means an attacker cannot
 * be handed a fresh, empty bucket merely by rewriting the address it already controls.
 *
 * <p>{@code InetAddress.ofLiteral} would replace most of this, but it arrived in Java 22
 * and this project targets 21.
 */
final class IpLiterals {

    private static final int IPV4_BYTES = 4;

    private static final int MAX_OCTET = 255;

    private static final int MAX_OCTET_DIGITS = 3;

    private IpLiterals() {
        throw new AssertionError("no instances");
    }

    /**
     * Parses text as an IPv4 or IPv6 literal, yielding its raw bytes.
     *
     * @param text the candidate literal, which may be {@code null}
     * @return the four or sixteen address bytes, or empty when the text is not a literal
     */
    static Optional<byte[]> parse(final String text) {
        if (text == null) {
            return Optional.empty();
        }
        final var candidate = text.strip();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        return candidate.indexOf(':') >= 0 ? parseIpv6(candidate) : parseIpv4(candidate);
    }

    /**
     * Reduces text to the one spelling the platform uses for that address, so that
     * equivalent spellings compare and count as equal.
     *
     * @param text the candidate literal, which may be {@code null}
     * @return the canonical form, or empty when the text is not a literal
     */
    static Optional<String> canonical(final String text) {
        return parse(text).map(IpLiterals::format);
    }

    /**
     * Bracketed forms such as {@code [::1]} appear in URLs and are tolerated. The scope
     * suffix of a link-local address is deliberately not stripped before parsing: the
     * platform understands it, and the bytes it produces carry no scope, so two scopes of
     * one link-local address collapse into a single bucket rather than two.
     *
     * <p>Delegation happens only for text that {@link #isAddressText(String)} has already
     * accepted, which is what makes this method DNS-free. Brackets are absent by then, so
     * the alphabet need not admit them.
     */
    private static Optional<byte[]> parseIpv6(final String candidate) {
        var text = candidate;
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        if (!isAddressText(text)) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(text).getAddress());
        }
        catch (final UnknownHostException notALiteral) {
            return Optional.empty();
        }
    }

    /**
     * Decides whether text is spelled like an IPv6 literal, and so may be handed to the
     * platform without any risk of a name lookup.
     *
     * <p>Three independent conditions must hold, and each one alone would close the resolver
     * path for most input. The address part must contain a colon; every one of its characters
     * must be a hex digit, a colon or a dot; and its first character must be a hex digit or a
     * colon, which is the same condition the JDK uses to choose literal parsing over
     * resolution. The first character is checked separately rather than left to the alphabet
     * because a dot is a legal address character but a leading dot is not a legal literal, and
     * {@code .a:b} passes an alphabet check yet still reaches {@code getaddrinfo}.
     *
     * <p>A scope suffix is permitted and may be named, not merely numeric. Names such as
     * {@code eth0.100} and {@code br-lan} carry digits, dots, hyphens and underscores, so those
     * characters are admitted. Being admitted by this guard is not the same as parsing, though,
     * and the platform can refuse for two distinct reasons. It rejects a name no interface
     * carries, with {@code no such interface}. It also rejects a name whose interface exists but
     * has no IPv6 link-local address and therefore no scope id, with {@code no scope_id found} —
     * measured on {@code fe80::1%en0}. So {@code %lo0} parses on a host with an interface of that
     * name carrying a link-local address, and fails both on a host that calls its loopback
     * {@code lo} and on one whose loopback carries {@code ::1/128} alone. This guard governs
     * which spellings may reach the platform; the platform decides whether they resolve. Either
     * refusal is against the interface table and never against DNS, and both are instant — which
     * is why widening the scope alphabet cannot reopen the resolver path. The address part is
     * validated on its own.
     *
     * @param text the candidate literal, with any surrounding brackets already removed
     * @return whether the text may be delegated to {@link InetAddress#getByName(String)}
     */
    private static boolean isAddressText(final String text) {
        final var separator = text.indexOf('%');
        final var address = separator < 0 ? text : text.substring(0, separator);
        if (address.indexOf(':') < 0 || !isLiteralStart(address.charAt(0))) {
            return false;
        }
        for (int index = 0; index < address.length(); index++) {
            if (!isAddressCharacter(address.charAt(index))) {
                return false;
            }
        }
        return separator < 0 || isScopeText(text.substring(separator + 1));
    }

    private static boolean isLiteralStart(final char character) {
        return character == ':' || isHexDigit(character);
    }

    private static boolean isAddressCharacter(final char character) {
        return isHexDigit(character) || character == ':' || character == '.';
    }

    private static boolean isHexDigit(final char character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F');
    }

    private static boolean isScopeText(final String scope) {
        if (scope.isEmpty()) {
            return false;
        }
        for (int index = 0; index < scope.length(); index++) {
            if (!isScopeCharacter(scope.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isScopeCharacter(final char character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || character == '.' || character == '-' || character == '_';
    }

    /**
     * Exactly four decimal octets, each one to three digits, none with a leading zero, and
     * none above 255. Anything else is not an address as far as this application is
     * concerned.
     */
    private static Optional<byte[]> parseIpv4(final String candidate) {
        final var parts = candidate.split("\\.", -1);
        if (parts.length != IPV4_BYTES) {
            return Optional.empty();
        }
        final var address = new byte[IPV4_BYTES];
        for (int index = 0; index < IPV4_BYTES; index++) {
            final var octet = parseOctet(parts[index]);
            if (octet.isEmpty()) {
                return Optional.empty();
            }
            address[index] = (byte) octet.getAsInt();
        }
        return Optional.of(address);
    }

    private static OptionalInt parseOctet(final String part) {
        if (part.isEmpty() || part.length() > MAX_OCTET_DIGITS) {
            return OptionalInt.empty();
        }
        if (part.length() > 1 && part.charAt(0) == '0') {
            return OptionalInt.empty();
        }
        int value = 0;
        for (int position = 0; position < part.length(); position++) {
            final var digit = part.charAt(position);
            if (digit < '0' || digit > '9') {
                return OptionalInt.empty();
            }
            value = value * 10 + (digit - '0');
        }
        return value > MAX_OCTET ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static String format(final byte[] address) {
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        }
        catch (final UnknownHostException impossible) {
            throw new IllegalStateException("address of " + address.length + " bytes came from this class", impossible);
        }
    }
}
