package com.clue.net;

import com.limelight.nvstream.http.ComputerDetails.AddressTuple;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * CLUE: shortest-path host address selection.
 *
 * A multi-homed PC (for example on the home LAN and, over USB-Ethernet, on a travel router)
 * is often reachable through several addresses. Upstream Moonlight keeps a single "local"
 * address, overwrites it with whatever LocalIP the host reports for the address it was just
 * reached on, and picks the first responder in a fixed order. When a router NATs between the
 * two networks, the routed address keeps answering and the direct one is never tried.
 *
 * This class holds the pure logic: remembered LAN addresses, ranking on-link candidates first,
 * and the list of hosts to probe when no known address is on-link. Android-specific parts
 * (LinkProperties, sockets) live in ComputerManagerService.
 */
public final class LanPath {
    /** Max remembered LAN addresses per PC. */
    public static final int MAX_LAN_ADDRESSES = 8;

    private LanPath() {}

    /** An IPv4 subnet the client is directly attached to. */
    public static final class Subnet {
        final int base;
        final int prefix;
        final int self;

        private Subnet(int self, int prefix) {
            this.self = self;
            this.prefix = prefix;
            this.base = self & mask(prefix);
        }

        /** Returns null if {@code address} is not a dotted IPv4 literal or the prefix is invalid. */
        public static Subnet of(String address, int prefix) {
            Integer a = parseIpv4(address);
            if (a == null || prefix < 0 || prefix > 32) {
                return null;
            }
            return new Subnet(a, prefix);
        }

        /** The client's own address on this subnet. */
        public String address() {
            return format(self);
        }

        /** RFC 1918 ranges only: we never probe carrier-grade NAT or public networks. */
        public boolean isPrivate() {
            int a = (self >>> 24) & 0xff;
            int b = (self >>> 16) & 0xff;
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
        }

        public boolean contains(String address) {
            Integer a = parseIpv4(address);
            return a != null && (a & mask(prefix)) == base;
        }

        @Override
        public String toString() {
            return format(base) + "/" + prefix;
        }
    }

    /**
     * Candidate addresses in the order they should be preferred: on-link addresses first
     * (local, remembered LAN, manual), then the upstream order (local, remembered LAN, manual,
     * remote, IPv6). Nulls and duplicates are dropped.
     */
    public static List<AddressTuple> rank(AddressTuple local, Collection<AddressTuple> lan,
                                          AddressTuple manual, AddressTuple remote, AddressTuple ipv6,
                                          List<Subnet> onLink) {
        List<AddressTuple> upstreamOrder = new ArrayList<>();
        upstreamOrder.add(local);
        if (lan != null) {
            upstreamOrder.addAll(lan);
        }
        upstreamOrder.add(manual);
        upstreamOrder.add(remote);
        upstreamOrder.add(ipv6);

        LinkedHashSet<AddressTuple> out = new LinkedHashSet<>();
        for (AddressTuple t : upstreamOrder) {
            if (t != null && isOnLink(t, onLink)) {
                out.add(t);
            }
        }
        for (AddressTuple t : upstreamOrder) {
            if (t != null) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    public static boolean isOnLink(AddressTuple t, List<Subnet> onLink) {
        if (t == null || onLink == null) {
            return false;
        }
        for (Subnet s : onLink) {
            if (s.contains(t.address)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasOnLink(Collection<AddressTuple> candidates, List<Subnet> onLink) {
        for (AddressTuple t : candidates) {
            if (isOnLink(t, onLink)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Most-recent-first list of LAN addresses with {@code add} at the front, capped at {@code max}.
     * Only non-loopback IPv4 literals are remembered.
     */
    public static List<AddressTuple> remember(List<AddressTuple> known, AddressTuple add, int max) {
        Integer a = add == null ? null : parseIpv4(add.address);
        if (a == null || (a >>> 24) == 127) {
            return known;
        }
        List<AddressTuple> out = new ArrayList<>();
        out.add(add);
        if (known != null) {
            for (AddressTuple t : known) {
                if (out.size() >= max) {
                    break;
                }
                if (!t.equals(add)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /**
     * Hosts to probe on {@code subnet}, excluding {@code self}, network and broadcast addresses.
     * Subnets wider than /24 are limited to the client's own /24 to keep the probe short.
     */
    public static List<String> probeTargets(Subnet subnet, String self) {
        Integer selfAddr = parseIpv4(self);
        int prefix = Math.max(subnet.prefix, 24);
        int base = subnet.self & mask(prefix);
        int size = 1 << (32 - prefix);
        List<String> out = new ArrayList<>();
        for (int i = 1; i < size - 1; i++) {
            int host = base + i;
            if (selfAddr != null && host == selfAddr) {
                continue;
            }
            out.add(format(host));
        }
        return out;
    }

    private static int mask(int prefix) {
        return prefix == 0 ? 0 : -1 << (32 - prefix);
    }

    static Integer parseIpv4(String s) {
        if (s == null) {
            return null;
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int v = 0;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            int n = 0;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') {
                    return null;
                }
                n = n * 10 + (c - '0');
            }
            if (n > 255) {
                return null;
            }
            v = (v << 8) | n;
        }
        return v;
    }

    private static String format(int a) {
        return ((a >>> 24) & 0xff) + "." + ((a >>> 16) & 0xff) + "." + ((a >>> 8) & 0xff) + "." + (a & 0xff);
    }
}
