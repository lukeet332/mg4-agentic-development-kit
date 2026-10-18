package com.lukeet.agenticdevkit;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Small network helpers used by the UI and the notification. */
public final class Net {

    private Net() {}

    /** All non-loopback IPv4 addresses, "iface=ip" form. */
    public static List<String> addresses() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address) out.add(ni.getName() + "=" + a.getHostAddress());
                }
            }
        } catch (Throwable ignored) { }
        Collections.sort(out);
        return out;
    }

    /** Best guess at the address the Mac should use: prefer wlan0, then anything. */
    public static String primaryAddress() {
        List<String> all = addresses();
        for (String a : all) if (a.startsWith("wlan")) return a.substring(a.indexOf('=') + 1);
        for (String a : all) if (!a.startsWith("usb") && !a.startsWith("rndis")) return a.substring(a.indexOf('=') + 1);
        return all.isEmpty() ? "no network" : all.get(0).substring(all.get(0).indexOf('=') + 1);
    }
}
