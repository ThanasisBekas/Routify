package io.routify.gateway.filter.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CidrMatcher} — covers IPv4, IPv6, CIDR ranges,
 * IPv4-mapped IPv6 normalization, and edge cases.
 */
class CidrMatcherTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // IPv4 single IP matching
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Single IPv4 address matches exactly")
    void singleIpv4_matchesExactly() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("192.168.1.100");
        assertThat(matcher.matches(InetAddress.getByName("192.168.1.100"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("192.168.1.101"))).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IPv4 CIDR range matching
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IPv4 /8 CIDR matches entire class A")
    void ipv4Cidr8_matchesClassA() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("10.0.0.0/8");
        assertThat(matcher.matches(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("10.1.2.3"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("10.255.255.255"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("11.0.0.1"))).isFalse();
        assertThat(matcher.matches(InetAddress.getByName("192.168.1.1"))).isFalse();
    }

    @Test
    @DisplayName("IPv4 /24 CIDR matches subnet")
    void ipv4Cidr24_matchesSubnet() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("192.168.1.0/24");
        assertThat(matcher.matches(InetAddress.getByName("192.168.1.0"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("192.168.1.255"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("192.168.1.42"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("192.168.2.1"))).isFalse();
    }

    @Test
    @DisplayName("IPv4 /16 CIDR matches class B")
    void ipv4Cidr16_matchesClassB() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("172.16.0.0/12");
        assertThat(matcher.matches(InetAddress.getByName("172.16.0.1"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("172.31.255.255"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("172.32.0.1"))).isFalse();
    }

    @Test
    @DisplayName("IPv4 /32 matches single host")
    void ipv4Cidr32_matchesSingleHost() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("10.0.0.5/32");
        assertThat(matcher.matches(InetAddress.getByName("10.0.0.5"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("10.0.0.6"))).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IPv6 matching
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Single IPv6 loopback matches exactly")
    void singleIpv6Loopback_matchesExactly() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("::1");
        assertThat(matcher.matches(InetAddress.getByName("::1"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("::2"))).isFalse();
    }

    @Test
    @DisplayName("IPv6 CIDR range matches correctly")
    void ipv6CidrRange_matchesCorrectly() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("2001:db8::/32");
        assertThat(matcher.matches(InetAddress.getByName("2001:db8::1"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("2001:db8:1234::abcd"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("2001:db9::1"))).isFalse();
    }

    @Test
    @DisplayName("IPv6 /128 matches single host")
    void ipv6Cidr128_matchesSingleHost() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("fe80::1/128");
        assertThat(matcher.matches(InetAddress.getByName("fe80::1"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("fe80::2"))).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IPv4-mapped IPv6 normalization
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IPv4-mapped IPv6 normalized to IPv4 for matching")
    void ipv4MappedIpv6_normalizedToIpv4() throws UnknownHostException {
        // An IPv4 matcher should match an IPv4-mapped IPv6 address
        CidrMatcher matcher = CidrMatcher.parse("192.168.1.0/24");
        // ::ffff:192.168.1.50 should be normalized to 192.168.1.50
        InetAddress mapped = InetAddress.getByName("::ffff:192.168.1.50");
        InetAddress normalized = CidrMatcher.normalize(mapped);
        assertThat(normalized.getAddress()).hasSize(4);
        assertThat(matcher.matches(mapped)).isTrue();
    }

    @Test
    @DisplayName("normalize() does not alter native IPv4")
    void normalize_nativeIpv4Unaltered() throws UnknownHostException {
        InetAddress ipv4 = InetAddress.getByName("10.0.0.1");
        InetAddress result = CidrMatcher.normalize(ipv4);
        assertThat(result.getAddress()).hasSize(4);
        assertThat(result).isEqualTo(ipv4);
    }

    @Test
    @DisplayName("normalize() does not alter native IPv6 (non-mapped)")
    void normalize_nativeIpv6Unaltered() throws UnknownHostException {
        InetAddress ipv6 = InetAddress.getByName("2001:db8::1");
        InetAddress result = CidrMatcher.normalize(ipv6);
        assertThat(result.getAddress()).hasSize(16);
        assertThat(result).isEqualTo(ipv6);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // matchesAny helper
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("matchesAny returns true when any matcher matches")
    void matchesAny_trueWhenAnyMatches() throws UnknownHostException {
        List<CidrMatcher> matchers = List.of(
                CidrMatcher.parse("10.0.0.0/8"),
                CidrMatcher.parse("192.168.1.0/24")
        );
        assertThat(CidrMatcher.matchesAny(InetAddress.getByName("192.168.1.50"), matchers)).isTrue();
        assertThat(CidrMatcher.matchesAny(InetAddress.getByName("10.5.5.5"), matchers)).isTrue();
        assertThat(CidrMatcher.matchesAny(InetAddress.getByName("172.16.0.1"), matchers)).isFalse();
    }

    @Test
    @DisplayName("matchesAny returns false for empty matcher list")
    void matchesAny_falseForEmptyList() throws UnknownHostException {
        assertThat(CidrMatcher.matchesAny(InetAddress.getByName("10.0.0.1"), List.of())).isFalse();
    }

    @Test
    @DisplayName("matchesAny returns false for null list")
    void matchesAny_falseForNullList() throws UnknownHostException {
        assertThat(CidrMatcher.matchesAny(InetAddress.getByName("10.0.0.1"), null)).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge cases and error handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parse throws on null input")
    void parse_throwsOnNull() {
        assertThatThrownBy(() -> CidrMatcher.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse throws on blank input")
    void parse_throwsOnBlank() {
        assertThatThrownBy(() -> CidrMatcher.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse throws on invalid IP")
    void parse_throwsOnInvalidIp() {
        assertThatThrownBy(() -> CidrMatcher.parse("not.an.ip.address"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse throws on invalid prefix length")
    void parse_throwsOnInvalidPrefixLength() {
        assertThatThrownBy(() -> CidrMatcher.parse("10.0.0.0/33"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse throws on negative prefix length")
    void parse_throwsOnNegativePrefixLength() {
        assertThatThrownBy(() -> CidrMatcher.parse("10.0.0.0/-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("matches returns false for null address")
    void matches_falseForNull() {
        CidrMatcher matcher = CidrMatcher.parse("10.0.0.0/8");
        assertThat(matcher.matches(null)).isFalse();
    }

    @Test
    @DisplayName("IPv4 matcher does not match IPv6 address")
    void ipv4MatcherDoesNotMatchIpv6() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("10.0.0.0/8");
        assertThat(matcher.matches(InetAddress.getByName("2001:db8::1"))).isFalse();
    }

    @Test
    @DisplayName("IPv6 matcher does not match IPv4 address")
    void ipv6MatcherDoesNotMatchIpv4() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("2001:db8::/32");
        assertThat(matcher.matches(InetAddress.getByName("10.0.0.1"))).isFalse();
    }

    @Test
    @DisplayName("/0 CIDR matches everything (IPv4)")
    void ipv4Cidr0_matchesEverything() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("0.0.0.0/0");
        assertThat(matcher.matches(InetAddress.getByName("1.2.3.4"))).isTrue();
        assertThat(matcher.matches(InetAddress.getByName("255.255.255.255"))).isTrue();
    }

    @Test
    @DisplayName("parse handles whitespace around input")
    void parse_handlesWhitespace() throws UnknownHostException {
        CidrMatcher matcher = CidrMatcher.parse("  10.0.0.0/8  ");
        assertThat(matcher.matches(InetAddress.getByName("10.1.2.3"))).isTrue();
    }

    @Test
    @DisplayName("toString returns human-readable CIDR string")
    void toString_returnsReadableString() {
        CidrMatcher matcher = CidrMatcher.parse("192.168.1.0/24");
        assertThat(matcher.toString()).contains("/24");
    }
}

