/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package cn.local.livingroomcast.core;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class SsdpServer implements AutoCloseable {
    private final MulticastSocket socket;
    private final InetAddress group;
    private final String uuid, location;
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private final Consumer<String> log;
    private volatile boolean closed;
    private final List<String> types;
    private int pendingSearches;
    private long lastSearchLog;
    public SsdpServer(InetAddress address, String uuid, String location, Consumer<String> log) throws IOException {
        this.uuid = "uuid:" + uuid; this.location = location; this.log = log;
        types = new ArrayList<>(List.of("upnp:rootdevice", this.uuid, Dlna.DEVICE));
        for (String s : Dlna.SERVICES) types.add(Dlna.type(s));
        group = InetAddress.getByName("239.255.255.250");
        socket = new MulticastSocket(null);
        try {
            socket.setReuseAddress(true); socket.bind(new InetSocketAddress(1900));
            NetworkInterface net = NetworkInterface.getByInetAddress(address);
            if (net == null) throw new IOException("No LAN interface");
            socket.setNetworkInterface(net); socket.setTimeToLive(2);
            socket.joinGroup(new InetSocketAddress(group, 1900), net);
        } catch (IOException e) { socket.close(); throw e; }
        scheduler.setRemoveOnCancelPolicy(true);
    }
    public void start() {
        scheduler.scheduleWithFixedDelay(() -> announce("ssdp:alive"), 0, 120, TimeUnit.SECONDS);
        scheduler.schedule(() -> announce("ssdp:alive"), 2, TimeUnit.SECONDS);
        new Thread(() -> {
            byte[] buffer = new byte[8192];
            while (!closed) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length); socket.receive(packet);
                    if (packet.getAddress().isMulticastAddress() || packet.getAddress().isAnyLocalAddress()) continue;
                    String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.US_ASCII);
                    Search search = parseSearch(text);
                    if (search == null) continue;
                    List<String> matched = search.st.equals("ssdp:all") ? types : (types.contains(search.st) ? List.of(search.st) : List.of());
                    if (matched.isEmpty()) continue;
                    if (System.nanoTime() - lastSearchLog > TimeUnit.SECONDS.toNanos(5)) { lastSearchLog = System.nanoTime(); log.accept("已收到局域网设备搜索"); }
                    synchronized (this) { if (pendingSearches >= 64) continue; pendingSearches++; }
                    InetAddress peer = packet.getAddress(); int port = packet.getPort();
                    scheduler.schedule(() -> {
                        try { for (String t : matched) reply(peer, port, t); }
                        finally { synchronized (this) { pendingSearches--; } }
                    }, ThreadLocalRandom.current().nextInt(20, search.mx * 1000 + 1), TimeUnit.MILLISECONDS);
                } catch (IOException e) { if (!closed) log.accept("设备发现连接异常"); }
                catch (RejectedExecutionException ignored) { }
            }
        }, "cast-ssdp").start();
    }
    public record Search(String st, int mx) {}
    public static Search parseSearch(String text) {
        String[] lines = text.split("\r?\n");
        if (lines.length < 2 || !lines[0].equalsIgnoreCase("M-SEARCH * HTTP/1.1")) return null;
        Map<String,String> h = new HashMap<>();
        for (int i=1;i<lines.length;i++) { int c=lines[i].indexOf(':'); if(c>0) h.put(lines[i].substring(0,c).trim().toLowerCase(Locale.US), lines[i].substring(c+1).trim()); }
        if (!"\"ssdp:discover\"".equalsIgnoreCase(h.get("man")) || !h.containsKey("st")) return null;
        int mx = 1;
        try { mx = Math.min(5, Math.max(1, Integer.parseInt(h.getOrDefault("mx", "1")))); } catch (NumberFormatException ignored) { }
        return new Search(h.get("st"), mx);
    }
    private String usn(String type) { return type.equals(uuid) ? uuid : uuid + "::" + type; }
    private void reply(InetAddress peer, int port, String type) {
        send("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=300\r\nEXT:\r\nLOCATION: " + location + "\r\nSERVER: " + HttpServer.SERVER + "\r\nST: " + type + "\r\nUSN: " + usn(type) + "\r\n\r\n", peer, port);
    }
    private void announce(String nts) {
        for (String type : types) send("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=300\r\nLOCATION: " + location + "\r\nNT: " + type + "\r\nNTS: " + nts + "\r\nSERVER: " + HttpServer.SERVER + "\r\nUSN: " + usn(type) + "\r\n\r\n", group, 1900);
    }
    private void send(String value, InetAddress peer, int port) {
        try { byte[] bytes=value.getBytes(StandardCharsets.US_ASCII); socket.send(new DatagramPacket(bytes,bytes.length,peer,port)); }
        catch (IOException ignored) { }
    }
    @Override public void close() { if (closed) return; announce("ssdp:byebye"); closed=true; scheduler.shutdownNow(); socket.close(); }
}
