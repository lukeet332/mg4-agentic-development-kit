package com.lukeet.adkrelay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DirectForwarderTest {
    @Test
    public void onlyTheCarHotspotNetworkIsUsed() {
        assertTrue(DirectForwarder.isCarHotspotAddress(new byte[]{(byte) 192, (byte) 168, 33, (byte) 223}));
        assertFalse(DirectForwarder.isCarHotspotAddress(new byte[]{(byte) 192, (byte) 168, 1, (byte) 214}));
        assertFalse(DirectForwarder.isCarHotspotAddress(new byte[16]));
    }
}
