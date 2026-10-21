/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package cn.local.livingroomcast.core;

import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keeps an unauthenticated LAN sender from pointing the TV at services only the TV itself can reach.
 * LAN addresses stay allowed: phones commonly serve casts from their own private IP.
 */
public final class MediaTarget {
    private static final Pattern DOTTED_QUAD = Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern NUMERIC_LIKE = Pattern.compile("(0x[0-9a-f]*|[0-9]+)(\\.(0x[0-9a-f]*|[0-9]+))*\\.?");
    private MediaTarget() {}

    /** Literal check, no DNS: safe on the main thread. {@code self} may be null. */
    public static boolean blockedHost(String host, String self) {
        if (host == null || host.isEmpty()) return true;
        String h = host.toLowerCase(Locale.US);
        if (h.endsWith(".")) h = h.substring(0, h.length()-1);
        if (h.equals("localhost") || h.endsWith(".localhost")) return true;
        if (h.startsWith("[") && h.endsWith("]")) return blockedAddress(literal(h.substring(1, h.length()-1)), self);
        if (DOTTED_QUAD.matcher(h).matches()) return blockedAddress(literal(h), self);
        // Shorthand forms such as 2130706433 or 0x7f.1 are rejected rather than interpreted.
        return NUMERIC_LIKE.matcher(h).matches();
    }

    public static boolean blockedAddress(InetAddress a, String self) {
        if (a == null) return true;
        return a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()
                || (self != null && !self.isEmpty() && a.getHostAddress().equals(self));
    }

    private static InetAddress literal(String value) {
        try { return InetAddress.getByName(value); } catch (Exception e) { return null; }
    }
}
