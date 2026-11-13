package com.clue.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.http.ComputerDetails.AddressTuple;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LanPathTest {
    private static AddressTuple t(String a) {
        return new AddressTuple(a, 47989);
    }

    // ---- Subnet ----

    @Test
    public void subnetContainsAddressInsidePrefix() {
        LanPath.Subnet s = LanPath.Subnet.of("192.168.137.100", 24);
        assertTrue(s.contains("192.168.137.102"));
        assertFalse(s.contains("192.168.0.137"));
    }

    @Test
    public void subnetHandlesNonByteAlignedPrefix() {
        LanPath.Subnet s = LanPath.Subnet.of("10.0.0.130", 25);
        assertTrue(s.contains("10.0.0.200"));
        assertFalse(s.contains("10.0.0.100"));
    }

    @Test
    public void subnetRejectsHostnamesAndIpv6() {
        LanPath.Subnet s = LanPath.Subnet.of("192.168.137.100", 24);
        assertFalse(s.contains("danielspc.local"));
        assertFalse(s.contains("fe80::1"));
    }

    @Test
    public void subnetKnowsClientAddressAndPrivateRanges() {
        assertEquals("192.168.137.100", LanPath.Subnet.of("192.168.137.100", 24).address());
        assertTrue(LanPath.Subnet.of("192.168.137.100", 24).isPrivate());
        assertTrue(LanPath.Subnet.of("10.1.2.3", 8).isPrivate());
        assertTrue(LanPath.Subnet.of("172.20.0.4", 16).isPrivate());
        assertFalse(LanPath.Subnet.of("172.32.0.4", 16).isPrivate());
        assertFalse(LanPath.Subnet.of("100.64.0.1", 10).isPrivate());
        assertFalse(LanPath.Subnet.of("203.0.113.9", 24).isPrivate());
    }

    @Test
    public void subnetOfReturnsNullForNonIpv4() {
        assertNull(LanPath.Subnet.of("fe80::1", 64));
    }

    // ---- rank ----

    @Test
    public void onLinkLanAddressBeatsRoutedLocalAddress() {
        // The bug: the PC is reachable at its home-LAN IP through the Puppis NAT,
        // and that routed path was chosen over the direct USB-Ethernet one.
        List<LanPath.Subnet> onLink = Collections.singletonList(LanPath.Subnet.of("192.168.137.100", 24));
        List<AddressTuple> ranked = LanPath.rank(
                t("192.168.0.137"),
                Arrays.asList(t("192.168.0.137"), t("192.168.137.102")),
                null, null, null, onLink);
        assertEquals(t("192.168.137.102"), ranked.get(0));
        assertEquals(t("192.168.0.137"), ranked.get(1));
        assertEquals(2, ranked.size());
    }

    @Test
    public void withoutOnLinkCandidateUpstreamOrderIsKept() {
        List<LanPath.Subnet> onLink = Collections.singletonList(LanPath.Subnet.of("172.16.5.9", 24));
        List<AddressTuple> ranked = LanPath.rank(
                t("192.168.0.137"),
                Collections.<AddressTuple>emptyList(),
                t("10.1.1.1"), t("203.0.113.7"), t("2001:db8::1"), onLink);
        assertEquals(Arrays.asList(t("192.168.0.137"), t("10.1.1.1"), t("203.0.113.7"), t("2001:db8::1")), ranked);
    }

    @Test
    public void onLinkManualAddressIsPromoted() {
        List<LanPath.Subnet> onLink = Collections.singletonList(LanPath.Subnet.of("192.168.137.100", 24));
        List<AddressTuple> ranked = LanPath.rank(
                t("192.168.0.137"), Collections.<AddressTuple>emptyList(),
                t("192.168.137.102"), null, null, onLink);
        assertEquals(t("192.168.137.102"), ranked.get(0));
    }

    @Test
    public void rankSkipsNullsAndDuplicates() {
        List<AddressTuple> ranked = LanPath.rank(
                null, Arrays.asList(t("192.168.0.137"), t("192.168.0.137")),
                t("192.168.0.137"), null, null, Collections.<LanPath.Subnet>emptyList());
        assertEquals(Collections.singletonList(t("192.168.0.137")), ranked);
    }

    @Test
    public void hasOnLinkDetectsDirectCandidate() {
        List<LanPath.Subnet> onLink = Collections.singletonList(LanPath.Subnet.of("192.168.137.100", 24));
        assertTrue(LanPath.hasOnLink(Arrays.asList(t("192.168.0.137"), t("192.168.137.102")), onLink));
        assertFalse(LanPath.hasOnLink(Collections.singletonList(t("192.168.0.137")), onLink));
    }

    // ---- remember ----

    @Test
    public void rememberPutsNewestFirstWithoutDuplicates() {
        List<AddressTuple> known = Arrays.asList(t("192.168.0.137"), t("192.168.137.102"));
        List<AddressTuple> out = LanPath.remember(known, t("192.168.137.102"), 8);
        assertEquals(Arrays.asList(t("192.168.137.102"), t("192.168.0.137")), out);
    }

    @Test
    public void rememberCapsListSize() {
        List<AddressTuple> known = Arrays.asList(t("10.0.0.1"), t("10.0.0.2"), t("10.0.0.3"));
        List<AddressTuple> out = LanPath.remember(known, t("10.0.0.4"), 3);
        assertEquals(Arrays.asList(t("10.0.0.4"), t("10.0.0.1"), t("10.0.0.2")), out);
    }

    @Test
    public void rememberIgnoresLoopbackAndNonIpv4() {
        List<AddressTuple> known = Collections.singletonList(t("10.0.0.1"));
        assertEquals(known, LanPath.remember(known, t("127.0.0.1"), 8));
        assertEquals(known, LanPath.remember(known, t("fe80::1"), 8));
        assertEquals(known, LanPath.remember(known, null, 8));
    }

    // ---- probeTargets ----

    @Test
    public void probeTargetsCoverSlash24ExceptSelf() {
        List<String> targets = LanPath.probeTargets(LanPath.Subnet.of("192.168.137.100", 24), "192.168.137.100");
        assertEquals(253, targets.size());
        assertTrue(targets.contains("192.168.137.1"));
        assertTrue(targets.contains("192.168.137.254"));
        assertFalse(targets.contains("192.168.137.100"));
        assertFalse(targets.contains("192.168.137.0"));
        assertFalse(targets.contains("192.168.137.255"));
    }

    @Test
    public void probeTargetsLimitWideSubnetsToOwnSlash24() {
        List<String> targets = LanPath.probeTargets(LanPath.Subnet.of("10.20.30.40", 16), "10.20.30.40");
        assertEquals(253, targets.size());
        assertTrue(targets.contains("10.20.30.1"));
        assertFalse(targets.contains("10.20.31.1"));
    }

    @Test
    public void probeTargetsRespectNarrowSubnets() {
        List<String> targets = LanPath.probeTargets(LanPath.Subnet.of("10.0.0.130", 30), "10.0.0.130");
        // /30 at .128: hosts .129 and .130; self excluded
        assertEquals(Collections.singletonList("10.0.0.129"), targets);
    }
}
