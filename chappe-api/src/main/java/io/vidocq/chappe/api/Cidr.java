package io.vidocq.chappe.api;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/** An IPv4/IPv6 network in CIDR notation, matched against peer addresses. Package-private helper. */
record Cidr(InetAddress network, int prefixBits) {

    static Cidr parse(String s) {
        Objects.requireNonNull(s, "cidr");
        int slash = s.indexOf('/');
        String addrPart = slash < 0 ? s : s.substring(0, slash);
        InetAddress addr;
        try {
            addr = InetAddress.getByName(addrPart);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid CIDR address: " + s, e);
        }
        int maxBits = addr.getAddress().length * 8;
        int bits = slash < 0 ? maxBits : Integer.parseInt(s.substring(slash + 1));
        if (bits < 0 || bits > maxBits) {
            throw new IllegalArgumentException("invalid CIDR prefix length: " + s);
        }
        return new Cidr(addr, bits);
    }

    boolean matches(InetAddress other) {
        byte[] a = network.getAddress();
        byte[] b = other.getAddress();
        if (a.length != b.length) return false;
        int fullBytes = prefixBits / 8;
        int remBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (a[i] != b[i]) return false;
        }
        if (remBits > 0) {
            int mask = 0xFF << (8 - remBits);
            if ((a[fullBytes] & mask) != (b[fullBytes] & mask)) return false;
        }
        return true;
    }
}
