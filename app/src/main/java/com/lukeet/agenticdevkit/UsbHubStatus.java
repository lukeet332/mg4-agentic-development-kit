package com.lukeet.agenticdevkit;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only status of every USB hub's ports (USB 2.0 spec 11.24.2.7): which ports have something
 * electrically attached, enabled, powered or in over-current. Only GET_DESCRIPTOR and GET_STATUS are
 * sent; nothing is written to a hub.
 */
final class UsbHubStatus {

    private static final int GET_STATUS = 0;
    private static final int GET_DESCRIPTOR = 6;
    private static final int HUB_DESCRIPTOR = 0x29;
    private static final int CLASS_DEVICE_IN = 0xA0;
    private static final int CLASS_OTHER_IN = 0xA3;
    private static final int TIMEOUT_MS = 1000;

    private UsbHubStatus() {}

    static Json.Obj read(Context ctx) {
        UsbManager usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        List<String> hubs = new ArrayList<>();
        for (UsbDevice device : usb.getDeviceList().values()) {
            if (device.getDeviceClass() != UsbConstants.USB_CLASS_HUB) continue;
            hubs.add(hub(usb, device).toString());
        }
        return new Json.Obj().put("ok", true).putRawArray("hubs", hubs);
    }

    private static Json.Obj hub(UsbManager usb, UsbDevice device) {
        String sysfs = sysfsName(device.getDeviceName());
        Json.Obj out = new Json.Obj()
                .put("device", device.getDeviceName())
                .put("sysfs", sysfs == null ? "?" : sysfs)
                .put("id", String.format("%04x:%04x", device.getVendorId(), device.getProductId()));
        if (!usb.hasPermission(device)) grant(usb, device);
        UsbDeviceConnection conn = usb.openDevice(device);
        if (conn == null) return out.put("error", "openDevice failed (permission " + usb.hasPermission(device) + ")");
        try {
            byte[] desc = new byte[16];
            int n = conn.controlTransfer(CLASS_DEVICE_IN, GET_DESCRIPTOR, HUB_DESCRIPTOR << 8, 0, desc, desc.length, TIMEOUT_MS);
            if (n < 7) return out.put("error", "hub descriptor read returned " + n);
            int ports = desc[2] & 0xff;
            int characteristics = (desc[3] & 0xff) | ((desc[4] & 0xff) << 8);
            out.put("ports", ports)
               .put("power_switching", powerSwitching(characteristics))
               .put("over_current_protection", (characteristics & 0x18) == 0 ? "global" : (characteristics & 0x10) != 0 ? "none" : "per-port")
               .put("power_on_to_good_ms", (desc[5] & 0xff) * 2);
            // DeviceRemovable bitmap: bit N set = port N is wired to a non-removable (internal) device.
            List<String> fixed = new ArrayList<>();
            for (int port = 1; port <= ports && 7 + port / 8 < n; port++) {
                if ((desc[7 + port / 8] & (1 << (port % 8))) != 0) fixed.add(String.valueOf(port));
            }
            out.putRawArray("non_removable_ports", fixed);
            List<String> list = new ArrayList<>();
            for (int port = 1; port <= ports; port++) {
                byte[] st = new byte[4];
                int r = conn.controlTransfer(CLASS_OTHER_IN, GET_STATUS, 0, port, st, st.length, TIMEOUT_MS);
                Json.Obj p = new Json.Obj().put("port", port);
                if (r != 4) {
                    p.put("error", "GET_STATUS returned " + r);
                } else {
                    int status = (st[0] & 0xff) | ((st[1] & 0xff) << 8);
                    int change = (st[2] & 0xff) | ((st[3] & 0xff) << 8);
                    p.put("status_hex", String.format("0x%04x", status))
                     .put("change_hex", String.format("0x%04x", change))
                     .put("connected", (status & 0x0001) != 0)
                     .put("enabled", (status & 0x0002) != 0)
                     .put("suspended", (status & 0x0004) != 0)
                     .put("over_current", (status & 0x0008) != 0)
                     .put("in_reset", (status & 0x0010) != 0)
                     .put("powered", (status & 0x0100) != 0)
                     .put("speed", (status & 0x0200) != 0 ? "low" : (status & 0x0400) != 0 ? "high" : "full")
                     .put("connect_changed", (change & 0x0001) != 0)
                     .put("over_current_changed", (change & 0x0008) != 0);
                    String child = sysfs == null ? null : childId(sysfs, port);
                    p.put("child", child == null ? "none" : child);
                }
                list.add(p.toString());
            }
            out.putRawArray("port_status", list);
        } finally {
            conn.close();
        }
        return out;
    }

    private static final int SET_FEATURE = 3;
    private static final int CLEAR_FEATURE = 1;
    private static final int CLASS_OTHER_OUT = 0x23;
    private static final int PORT_RESET = 4;
    private static final int PORT_POWER = 8;
    /** The MG4's internal hub; port 1 is the TBox modem and is never touched. */
    private static final String INTERNAL_HUB = "0424:2514";
    private static final int MODEM_PORT = 1;

    /** action "reset" (data lines only) or "power-cycle" (port power off, wait, on) on one internal hub port. */
    static Json.Obj portAction(Context ctx, String action, int port, int offMs) {
        if (port == MODEM_PORT) return new Json.Obj().put("ok", false).put("error", "port 1 is the TBox modem; refused");
        UsbManager usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        for (UsbDevice device : usb.getDeviceList().values()) {
            if (!INTERNAL_HUB.equals(String.format("%04x:%04x", device.getVendorId(), device.getProductId()))) continue;
            if (!usb.hasPermission(device)) grant(usb, device);
            UsbDeviceConnection conn = usb.openDevice(device);
            if (conn == null) return new Json.Obj().put("ok", false).put("error", "openDevice failed");
            try {
                int a, b = 0;
                if ("reset".equals(action)) {
                    a = conn.controlTransfer(CLASS_OTHER_OUT, SET_FEATURE, PORT_RESET, port, null, 0, TIMEOUT_MS);
                } else if ("power-cycle".equals(action)) {
                    a = conn.controlTransfer(CLASS_OTHER_OUT, CLEAR_FEATURE, PORT_POWER, port, null, 0, TIMEOUT_MS);
                    try { Thread.sleep(Math.max(500, Math.min(offMs, 10_000))); } catch (InterruptedException ignored) {}
                    b = conn.controlTransfer(CLASS_OTHER_OUT, SET_FEATURE, PORT_POWER, port, null, 0, TIMEOUT_MS);
                } else {
                    return new Json.Obj().put("ok", false).put("error", "action must be reset or power-cycle");
                }
                return new Json.Obj().put("ok", a >= 0 && b >= 0).put("action", action).put("port", port).put("result", a).put("power_on_result", b);
            } finally {
                conn.close();
            }
        }
        return new Json.Obj().put("ok", false).put("error", "internal hub " + INTERNAL_HUB + " not found");
    }

    private static String powerSwitching(int characteristics) {
        switch (characteristics & 0x3) {
            case 0: return "ganged";
            case 1: return "per-port";
            default: return "none";
        }
    }

    /** UsbManager grants itself to the system uid; reflection covers firmwares that don't. */
    private static void grant(UsbManager usb, UsbDevice device) {
        try {
            usb.getClass().getMethod("grantPermission", UsbDevice.class).invoke(usb, device);
        } catch (Throwable ignored) {
        }
    }

    /** /dev/bus/usb/001/003 -> the sysfs device name with busnum 1, devnum 3 (e.g. "1-2"). */
    private static String sysfsName(String devPath) {
        String[] parts = devPath.split("/");
        if (parts.length < 2) return null;
        int bus, dev;
        try {
            bus = Integer.parseInt(parts[parts.length - 2]);
            dev = Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return null;
        }
        File[] all = new File("/sys/bus/usb/devices").listFiles();
        if (all == null) return null;
        for (File d : all) {
            if (String.valueOf(bus).equals(readLine(new File(d, "busnum"))) && String.valueOf(dev).equals(readLine(new File(d, "devnum")))) return d.getName();
        }
        return null;
    }

    /** Root hub "usb1" ports are "1-N"; hub "1-2" ports are "1-2.N". */
    private static String childId(String hub, int port) {
        String name = hub.startsWith("usb") ? hub.substring(3) + "-" + port : hub + "." + port;
        File d = new File("/sys/bus/usb/devices", name);
        String vid = readLine(new File(d, "idVendor"));
        return vid == null ? null : name + " " + vid + ":" + readLine(new File(d, "idProduct"));
    }

    private static String readLine(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }
}
