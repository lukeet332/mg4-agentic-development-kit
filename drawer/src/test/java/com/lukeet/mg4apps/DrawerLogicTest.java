package com.lukeet.mg4apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DrawerLogicTest {

    @Test
    public void onlyTheCarPlayTileOpensTheDrawer() {
        assertTrue(DrawerTriggerService.isCarPlayTile("com.saicmotor.hmi.launcher:id/iv_apple_carplay"));
        assertTrue(DrawerTriggerService.isCarPlayTile("com.saicmotor.hmi.launcher:id/tv_apple_carplay"));
        assertFalse(DrawerTriggerService.isCarPlayTile("com.saicmotor.hmi.launcher:id/iv_android_auto"));
        assertFalse(DrawerTriggerService.isCarPlayTile(null));
    }

    @Test
    public void enablingKeepsEveryOtherAccessibilityService() {
        String adk = "com.lukeet.agenticdevkit/com.lukeet.agenticdevkit.AdkAccessibilityService";
        String me = "com.lukeet.mg4apps/com.lukeet.mg4apps.DrawerTriggerService";
        assertEquals(adk, AccessibilityEnabler.without(adk + ":" + me, me));
        assertEquals(adk, AccessibilityEnabler.without(adk, me));
        assertEquals("", AccessibilityEnabler.without(null, me));
    }
}
