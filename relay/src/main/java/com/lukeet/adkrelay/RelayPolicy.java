package com.lukeet.adkrelay;

/** Who may connect to which side of the relay. */
final class RelayPolicy {
    private RelayPolicy() {}

    /** The agent side: the tailnet (100.64.0.0/10) or this phone itself. */
    static boolean isAgent(byte[] a) {
        if (a.length != 4) return false;
        int b0 = a[0] & 0xff, b1 = a[1] & 0xff;
        return (b0 == 100 && b1 >= 64 && b1 <= 127) || b0 == 127;
    }

    /** The car side: only the car's own hotspot address. */
    static boolean isCar(byte[] a) {
        return a.length == 4 && (a[0] & 0xff) == 192 && (a[1] & 0xff) == 168 && (a[2] & 0xff) == 33 && (a[3] & 0xff) == 1;
    }
}
