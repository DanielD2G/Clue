package com.clue.net;

import static org.junit.Assert.assertEquals;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.ComputerDetails.AddressTuple;

import org.junit.Test;

import java.util.Arrays;

public class ComputerDetailsLanTest {
    private static AddressTuple t(String a) {
        return new AddressTuple(a, 47989);
    }

    @Test
    public void updateRemembersEveryLocalAddressSeen() {
        // Each poll overwrites localAddress with the host's LocalIP; the previous one
        // must not be forgotten, or the direct path is lost as soon as a routed poll succeeds.
        ComputerDetails saved = new ComputerDetails();
        ComputerDetails viaUsb = new ComputerDetails();
        viaUsb.localAddress = t("192.168.137.102");
        saved.update(viaUsb);

        ComputerDetails viaHome = new ComputerDetails();
        viaHome.localAddress = t("192.168.0.137");
        saved.update(viaHome);

        assertEquals(t("192.168.0.137"), saved.localAddress);
        assertEquals(Arrays.asList(t("192.168.0.137"), t("192.168.137.102")), saved.lanAddresses);
    }

    @Test
    public void updateMergesIncomingLanAddresses() {
        ComputerDetails saved = new ComputerDetails();
        saved.lanAddresses = Arrays.asList(t("10.0.0.5"));

        ComputerDetails polled = new ComputerDetails();
        polled.lanAddresses = Arrays.asList(t("192.168.137.102"));
        saved.update(polled);

        assertEquals(Arrays.asList(t("192.168.137.102"), t("10.0.0.5")), saved.lanAddresses);
    }
}
