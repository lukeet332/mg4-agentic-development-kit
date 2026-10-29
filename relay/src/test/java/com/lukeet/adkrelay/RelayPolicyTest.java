package com.lukeet.adkrelay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RelayPolicyTest {

    @Test
    public void onlyTheTailnetMayUseTheAgentSide() {
        assertTrue(RelayPolicy.isAgent(new byte[] {100, 72, (byte) 183, 72}));
        assertTrue(RelayPolicy.isAgent(new byte[] {100, 127, 0, 1}));
        assertTrue(RelayPolicy.isAgent(new byte[] {127, 0, 0, 1}));
        assertFalse(RelayPolicy.isAgent(new byte[] {100, (byte) 128, 0, 1}));
        assertFalse(RelayPolicy.isAgent(new byte[] {(byte) 192, (byte) 168, 33, 1}));
    }

    @Test
    public void onlyTheCarsHotspotAddressMayUseTheCarSide() {
        assertTrue(RelayPolicy.isCar(new byte[] {(byte) 192, (byte) 168, 33, 1}));
        assertFalse(RelayPolicy.isCar(new byte[] {(byte) 192, (byte) 168, 33, 50}));
        assertFalse(RelayPolicy.isCar(new byte[] {100, 72, (byte) 183, 72}));
    }
}
