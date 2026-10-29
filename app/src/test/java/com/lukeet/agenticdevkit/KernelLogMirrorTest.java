package com.lukeet.agenticdevkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class KernelLogMirrorTest {

    private static final String AUDIT = "4,9634,2353384500,-;audit: audit_lost=52105 audit_rate_limit=5 audit_backlog_limit=64";
    private static final String USB = "3,9640,2360000000,-;usb 1-2.2: device descriptor read/64, error -71";

    @Test
    public void onlyRecordsNewerThanTheLastSeenAreTaken() {
        KernelLogMirror m = new KernelLogMirror();
        assertEquals(2, m.take(Arrays.asList(AUDIT, USB)).size());
        // The vendor daemon rewrites its rolling file, so the same records come round again.
        assertEquals(0, m.take(Arrays.asList(AUDIT, USB)).size());
        assertEquals(1, m.take(Arrays.asList(USB, "6,9641,2361000000,-;usb 1-2.2: new full-speed USB device number 4 using xhci-mtk")).size());
    }

    @Test
    public void recordsAreStampedWithKernelUptime() {
        List<String> out = new KernelLogMirror().take(Arrays.asList(USB));
        assertEquals("[2360.000000] <3> usb 1-2.2: device descriptor read/64, error -71", out.get(0));
    }

    @Test
    public void usbLinesAreRecognisedForTheTunerFile() {
        assertTrue(KernelLogMirror.isUsb("[2360.000000] <3> usb 1-2.2: device descriptor read/64, error -71"));
        assertTrue(KernelLogMirror.isUsb("[1.0] <6> hub 1-2:1.0: 4 ports detected"));
        assertTrue(KernelLogMirror.isUsb("[1.0] <6> xhci-mtk 11270000.usb: xHCI Host Controller"));
        assertTrue(KernelLogMirror.isUsb("[1.0] <6> cdc_acm 1-2.2:1.0: ttyACM0: USB ACM device"));
        assertFalse(KernelLogMirror.isUsb("[2353.384500] <4> audit: audit_lost=52105"));
    }

    @Test
    public void continuationAndMalformedLinesAreKeptVerbatim() {
        List<String> out = new KernelLogMirror().take(Arrays.asList(" SUBSYSTEM=usb", "garbage"));
        assertEquals(Arrays.asList(" SUBSYSTEM=usb", "garbage"), out);
    }
}
