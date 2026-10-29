package com.lukeet.agenticdevkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class TunerHealthTest {

    private static final String POLL = "10-20 20:38:49.023  2355  2402 I radio_hw_comm: Request#f6: 0d 0a f6 00 01 0e ";
    private static final String REPLY = "10-20 20:38:49.333  2355  2435 I radio_hw_resp: Respond#89: 0d 0a 89 00 03 00 00 a3 ";

    private final TunerHealth health = new TunerHealth();

    private List<String> pollOnly(long fromMs, long toMs) {
        List<String> out = new ArrayList<>();
        for (long t = fromMs; t <= toMs; t += 1000) {
            out.addAll(health.onLine(POLL, t));
            out.addAll(health.onTick(t, true, -1));
        }
        return out;
    }

    private static int count(List<String> lines, String prefix) {
        int n = 0;
        for (String l : lines) if (l.startsWith(prefix)) n++;
        return n;
    }

    @Test
    public void aTunerAnsweringItsPollsRaisesNothing() {
        List<String> out = new ArrayList<>();
        for (long t = 0; t < 30_000; t += 1000) {
            out.addAll(health.onLine(POLL, t));
            out.addAll(health.onLine(REPLY, t + 10));
            out.addAll(health.onTick(t + 20, true, -1));
        }
        assertEquals(0, count(out, "EVENT"));
        assertFalse(health.consumeSnapshotRequest());
    }

    @Test
    public void pollsWithNoReplyFor5sAreReportedOnceWithASnapshot() {
        health.onLine(REPLY, 0);
        List<String> out = pollOnly(1000, 20_000);
        assertEquals(1, count(out, "EVENT TUNER_SILENT"));
        assertTrue(health.consumeSnapshotRequest());
        assertFalse(health.consumeSnapshotRequest());
    }

    @Test
    public void aReplyAfterSilenceIsReportedAsRecovered() {
        health.onLine(REPLY, 0);
        pollOnly(1000, 10_000);
        List<String> out = health.onLine(REPLY, 11_000);
        assertEquals(1, count(out, "EVENT TUNER_ANSWERING"));
    }

    @Test
    public void noPollsMeansTheDriverIsIdleNotTheTunerSilent() {
        health.onLine(REPLY, 0);
        List<String> out = new ArrayList<>();
        for (long t = 1000; t < 30_000; t += 1000) out.addAll(health.onTick(t, true, -1));
        assertEquals(0, count(out, "EVENT"));
    }

    @Test
    public void everyCommandOtherThanThePollIsRecordedVerbatim() {
        List<String> out = health.onLine("10-20 20:38:40.000  2355  2402 I radio_hw_comm: Request#17: 0d 0a 17 00 05 01 02 03 04 2e ", 0);
        assertEquals(1, out.size());
        assertTrue(out.get(0).startsWith("CMD "));
        assertTrue(out.get(0).contains("Request#17: 0d 0a 17"));
        assertEquals(0, health.onLine(POLL, 10).size());
    }

    @Test
    public void powerModeIsRecordedOnlyWhenItChanges() {
        String run = "10-20 21:20:00.000  2881  2927 I CAR.POWER: Received PMS_PWR_MODE=3";
        String standby = "10-20 21:23:26.384  2881  2927 I CAR.POWER: Received PMS_PWR_MODE=2";
        assertEquals(1, count(health.onLine(run, 0), "EVENT POWER_MODE"));
        assertEquals(0, health.onLine(run, 10).size());
        List<String> out = health.onLine(standby, 20);
        assertEquals(1, count(out, "EVENT POWER_MODE"));
        assertTrue(out.get(0).contains("3 -> 2"));
    }

    @Test
    public void theTunerLeavingAndReturningToUsbAreBothReported() {
        assertEquals(0, health.onTick(0, true, -1).size());
        List<String> gone = health.onTick(1000, false, -1);
        assertEquals(1, count(gone, "EVENT TUNER_USB_GONE"));
        assertTrue(health.consumeSnapshotRequest());
        assertEquals(0, health.onTick(2000, false, -1).size());
        assertEquals(1, count(health.onTick(3000, true, -1), "EVENT TUNER_USB_BACK"));
    }

    @Test
    public void aTunerMissingAtTheFirstCheckIsReported() {
        assertEquals(1, count(health.onTick(0, false, -1), "EVENT TUNER_USB_GONE"));
    }

    @Test
    public void diagnosticValuesAreRecordedWhenTheyChange() {
        String a = "10-20 20:30:00.000  2355  2435 I radio_hw: currentDiag, LineOut:0, DABAnt:0, FMAMAnt:0, Battery:0; recordDiag recordDiag.DABAnt:0,recordDiag.FMAMAnt=0";
        String b = "10-20 20:31:00.000  2355  2435 I radio_hw: currentDiag, LineOut:0, DABAnt:2, FMAMAnt:0, Battery:0; recordDiag recordDiag.DABAnt:2,recordDiag.FMAMAnt=0";
        assertEquals(1, count(health.onLine(a, 0), "DIAG"));
        assertEquals(0, health.onLine(a, 10).size());
        assertEquals(1, count(health.onLine(b, 20), "DIAG"));
    }

    @Test
    public void driverErrorsAreRecordedOnceAndCountedInTheSummary() {
        String err = "10-20 21:26:40.000  2355  2402 E radio_hw_comm: g_Device == NULL || g_ep_out_desc == NULL";
        assertEquals(1, count(health.onLine(err, 0), "DRIVER"));
        assertEquals(0, health.onLine(err, 1000).size());
        String summary = health.summary(2000, true);
        assertTrue(summary, summary.contains("driverErrors=2"));
    }

    @Test
    public void theSummaryCountsTheWindowAndResetsIt() {
        health.onLine(POLL, 0);
        health.onLine(REPLY, 10);
        health.onLine(REPLY, 20);
        String first = health.summary(1000, true);
        assertTrue(first, first.contains("polls=1") && first.contains("replies=2") && first.contains("usb=yes"));
        String second = health.summary(2000, false);
        assertTrue(second, second.contains("polls=0") && second.contains("replies=0") && second.contains("usb=no"));
    }

    @Test
    public void recentRadioLinesAreKeptForTheSnapshot() {
        for (int i = 0; i < TunerHealth.RECENT_LINES + 50; i++) health.onLine(POLL + i, i);
        List<String> recent = health.recentLines();
        assertEquals(TunerHealth.RECENT_LINES, recent.size());
        assertTrue(recent.get(recent.size() - 1).endsWith(String.valueOf(TunerHealth.RECENT_LINES + 49)));
    }

    @Test
    public void pollsSpreadingTo4sMeanTheBoxStoppedTakingDataAndAreReportedOnce() {
        // 20 Oct: 1 s polls until 20:39:04, then ~4 s apart, as each send blocked for its retries.
        long t = 0;
        for (int i = 0; i < 5; i++, t += 1000) health.onLine(POLL, t);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 4; i++, t += 4050) out.addAll(health.onLine(POLL, t));
        assertEquals(1, count(out, "EVENT TUNER_NOT_ACCEPTING"));
        for (int i = 0; i < 3; i++, t += 1000) out.addAll(health.onLine(POLL, t));
        assertEquals(1, count(out, "EVENT TUNER_ACCEPTING"));
    }

    @Test
    public void aUsbRequestStormIsReported() {
        health.onTick(0, true, 1000);
        assertEquals(0, count(health.onTick(1000, true, 1010), "EVENT"));
        assertEquals(1, count(health.onTick(2000, true, 1300), "EVENT TUNER_URB_STORM"));
        assertEquals(0, count(health.onTick(3000, true, 1600), "EVENT"));
        String summary = health.summary(3000, true);
        assertTrue(summary, summary.contains("urbPerS="));
    }

    @Test
    public void upgradeThreadStatusIsRecordedWhenItChanges() {
        String a = "01-01 00:00:08.508  2355  2435 I radio_hw: upgrade_signal_thread_by_type,upgrade_signal.status=5";
        String b = "01-01 00:00:09.508  2355  2435 I radio_hw: upgrade_signal_thread_by_type,upgrade_signal.status=2";
        assertEquals(1, count(health.onLine(a, 0), "UPGRADE"));
        assertEquals(0, health.onLine(a, 10).size());
        assertEquals(1, count(health.onLine(b, 20), "UPGRADE"));
    }
}
