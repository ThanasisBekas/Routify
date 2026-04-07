package io.routify.gateway.filter.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable CIDR range matcher — compiles a CIDR string (e.g. {@code 10.0.0.0/8},
 * {@code 2001:db8::/32}) once at construction time and provides an efficient
 * bit-prefix {@link #matches(InetAddress)} check.
 *
 * <p>Supports both IPv4 and IPv6 addresses. IPv4-mapped IPv6 addresses
 * ({@code ::ffff:192.168.1.1}) are automatically normalized to plain IPv4.
 *
 * <p>Thread-safe — all state is immutable.
 */
public final class CidrMatcher {

    private final byte[] networkAddress;
    private final int prefixLength;

    private CidrMatcher(byte[] networkAddress, int prefixLength) {
        this.networkAddress = networkAddress;
        this.prefixLength = prefixLength;
    }

    /**
     * Parses a CIDR string into a {@link CidrMatcher}.
     *
     * <p>Accepted formats:
     * <ul>
     *   <li>Single IP: {@code 192.168.1.1}, {@code ::1}</li>
     *   <li>CIDR range: {@code 10.0.0.0/8}, {@code 2001:db8::/32}</li>
     * </ul>
     *
     * @param cidr IP address or CIDR range
     * @return a compiled matcher
     * @throws IllegalArgumentException if the string is not a valid IP or CIDR
     */
    public static CidrMatcher parse(String cidr) {
        if (cidr == null || cidr.isBlank()) {
            throw new IllegalArgumentException("CIDR string must not be null or blank");
        }

        String trimmed = cidr.strip();
        String addressPart;
        int prefix;

        int slashIdx = trimmed.indexOf('/');
        if (slashIdx >= 0) {
            addressPart = trimmed.substring(0, slashIdx);
            try {
                prefix = Integer.parseInt(trimmed.substring(slashIdx + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR prefix length: " + trimmed, e);
            }
            if (prefix < 0) {
                throw new IllegalArgumentException(
                        "Prefix length must not be negative: " + prefix);
            }
        } else {
            addressPart = trimmed;
            prefix = -1; // sentinel — will be set after parsing the address
        }

        InetAddress addr;
        try {
            addr = InetAddress.getByName(addressPart);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP address in CIDR: " + addressPart, e);
        }

        byte[] rawAddr = normalize(addr).getAddress();
        int maxPrefix = rawAddr.length * 8;

        if (prefix < 0) {
            prefix = maxPrefix; // single host
        }
        if (prefix < 0 || prefix > maxPrefix) {
            throw new IllegalArgumentException(
                    "Prefix length %d out of range for %s (max %d)".formatted(prefix, addressPart, maxPrefix));
        }

        // Mask out bits beyond the prefix length to normalize the network address
        byte[] masked = applyMask(rawAddr, prefix);

        return new CidrMatcher(masked, prefix);
    }

    /**
     * Returns {@code true} if the given address falls within this CIDR range.
     */
    public boolean matches(InetAddress address) {
        if (address == null) return false;

        byte[] rawAddr = normalize(address).getAddress();

        // IPv4 vs IPv6 length mismatch → cannot match
        if (rawAddr.length != networkAddress.length) return false;

        byte[] masked = applyMask(rawAddr, prefixLength);
        return Arrays.equals(masked, networkAddress);
    }

    /**
     * Returns {@code true} if the given address matches any of the provided matchers.
     */
    public static boolean matchesAny(InetAddress address, List<CidrMatcher> matchers) {
        if (matchers == null || matchers.isEmpty()) return false;
        for (CidrMatcher matcher : matchers) {
            if (matcher.matches(address)) return true;
        }
        return false;
    }

    /**
     * Normalizes an {@link InetAddress} — converts IPv4-mapped IPv6 addresses
     * ({@code ::ffff:x.x.x.x}) back to plain IPv4 for consistent matching.
     */
    static InetAddress normalize(InetAddress addr) {
        byte[] raw = addr.getAddress();
        if (raw.length == 16) {
            // Check for IPv4-mapped IPv6: first 10 bytes 0, next 2 bytes 0xFF
            boolean isV4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) { isV4Mapped = false; break; }
            }
            if (isV4Mapped && raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF) {
                byte[] v4 = new byte[4];
                System.arraycopy(raw, 12, v4, 0, 4);
                try {
                    return InetAddress.getByAddress(v4);
                } catch (UnknownHostException e) {
                    // Should never happen for a 4-byte address
                    return addr;
                }
            }
        }
        return addr;
    }

    private static byte[] applyMask(byte[] address, int prefixLength) {
        byte[] result = address.clone();
        int fullBytes = prefixLength / 8;
        int remainBits = prefixLength % 8;

        // Zero out bytes beyond the prefix
        for (int i = fullBytes + (remainBits > 0 ? 1 : 0); i < result.length; i++) {
            result[i] = 0;
        }

        // Mask partial byte
        if (remainBits > 0 && fullBytes < result.length) {
            int mask = 0xFF << (8 - remainBits);
            result[fullBytes] = (byte) (result[fullBytes] & mask);
        }

        return result;
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(networkAddress).getHostAddress() + "/" + prefixLength;
        } catch (UnknownHostException e) {
            return Arrays.toString(networkAddress) + "/" + prefixLength;
        }
    }
}


