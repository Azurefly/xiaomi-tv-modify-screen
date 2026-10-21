/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package cn.local.livingroomcast.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Small HTTP/SOAP/GENA endpoint, bound only to the selected LAN IPv4 address. */
public final class HttpServer implements AutoCloseable {
    public static final String SERVER = "Android/10 UPnP/1.0 LivingRoomCast/0.1.1";
    private final ServerSocket socket;
    private final String uuid, base;
    private final Dlna.Controller controller;
    private final Consumer<String> log;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 6, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(24));
    private final ScheduledExecutorService events = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<InetAddress, Integer> active = new HashMap<>();
    private volatile boolean closed;
    private boolean publishPending;
    private static final class Subscription {
        final String id = "uuid:" + UUID.randomUUID(), service;
        final URI callback;
        final InetAddress peer;
        volatile long expires;
        long sequence;
        int failures;
        Subscription(String service, URI callback, InetAddress peer, int seconds) {
            this.service = service; this.callback = callback; this.peer = peer; renew(seconds);
        }
        void renew(int seconds) { expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds); }
    }
    private static final long REQUEST_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int SUBSCRIPTIONS_PER_PEER = 4;
    /** One LAN host may not occupy every worker, even within the request deadline. */
    private static final int CONNECTIONS_PER_PEER = 3;
    /** State changes within this window share one GENA NOTIFY round (UPnP AV moderates LastChange to ~5 Hz). */
    private static final long PUBLISH_COALESCE_MS = 200;
    /** A subscriber that misses this many NOTIFYs in a row is dropped instead of stalling every later round. */
    private static final int MAX_NOTIFY_FAILURES = 3;
    private record Request(String method, String path, Map<String,String> headers, String body, InetAddress peer) {}
    public HttpServer(InetAddress address, int port, String uuid, Dlna.Controller controller, Consumer<String> log) throws IOException {
        this.uuid = uuid; this.controller = controller; this.log = log;
        socket = new ServerSocket(); socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(address, port), 16);
        base = "http://" + address.getHostAddress() + ":" + socket.getLocalPort();
    }
    public String base() { return base; }
    public void start() {
        new Thread(() -> {
            while (!closed) {
                try {
                    Socket client = socket.accept();
                    InetAddress peer = client.getInetAddress();
                    if (!acquire(peer)) { client.close(); continue; }
                    try { workers.execute(() -> { try { handle(client); } finally { release(peer); } }); }
                    catch (RejectedExecutionException busy) { release(peer); client.close(); }
                } catch (IOException e) { if (!closed) log.accept("接收连接异常"); }
            }
        }, "cast-http").start();
    }
    private boolean acquire(InetAddress peer) {
        synchronized (active) {
            int n = active.getOrDefault(peer, 0);
            if (n >= CONNECTIONS_PER_PEER) return false;
            active.put(peer, n + 1); return true;
        }
    }
    private void release(InetAddress peer) {
        synchronized (active) { int n = active.getOrDefault(peer, 1) - 1; if (n <= 0) active.remove(peer); else active.put(peer, n); }
    }
    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(6000);
            Request r;
            try { r = read(client); } catch (Exception invalid) { send(client, 400, Map.of(), "text/plain", "Bad request"); return; }
            if (r.method.equals("GET") || r.method.equals("HEAD")) {
                if (r.path.equals("/description.xml")) send(client, 200, Map.of(), "text/xml; charset=utf-8", Dlna.description(uuid, base), r.method.equals("HEAD"));
                else if (r.path.startsWith("/service/") && r.path.endsWith(".xml") && Dlna.SERVICES.contains(r.path.substring(9, r.path.length()-4)))
                    send(client, 200, Map.of(), "text/xml; charset=utf-8", Dlna.scpd(r.path.substring(9, r.path.length()-4)), r.method.equals("HEAD"));
                else if (r.path.equals("/")) send(client, 200, Map.of(), "text/html; charset=utf-8", "<!doctype html><meta charset='utf-8'><title>小米电视非官方投屏</title><h1>小米电视非官方投屏</h1><p>接收服务正在运行。在平板的软件中点TV/投屏，选择“小米电视非官方投屏”</p><p>本页可打开只表示网络可达；直播兼容性需要实际投屏验证。</p>", r.method.equals("HEAD"));
                else send(client, 404, Map.of(), "text/plain", "Not found");
            } else if (r.method.equals("POST") && r.path.startsWith("/control/")) {
                String service = r.path.substring(9);
                if (!Dlna.SERVICES.contains(service)) { send(client, 404, Map.of(), "text/plain", "Not found"); return; }
                try {
                    var action = Xml.soapAction(r.body);
                    String name = Xml.local(action);
                    if (!Dlna.type(service).equals(action.getNamespaceURI())) throw new Dlna.Fault(401, "Invalid action namespace");
                    String soapAction = r.headers.getOrDefault("soapaction", "").replace("\"", "");
                    if (!soapAction.equals(Dlna.type(service) + "#" + name)) throw new Dlna.Fault(401, "Invalid SOAPAction");
                    Dlna.Action definition = Dlna.actions(service).stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow(() -> new Dlna.Fault(401, "Invalid Action"));
                    Map<String, String> args = Xml.arguments(action);
                    for (Dlna.Arg arg : definition.args()) if (arg.direction().equals("in") && !args.containsKey(arg.name())) throw new Dlna.Fault(402, "Invalid Args");
                    Map<String, String> response = controller.action(service, name, args, r.peer);
                    send(client, 200, Map.of("EXT", ""), "text/xml; charset=utf-8", Dlna.response(service, name, response));
                    if (name.startsWith("Set") || List.of("Play","Pause","Stop","Seek").contains(name)) publish();
                } catch (Dlna.Fault f) { send(client, 500, Map.of(), "text/xml; charset=utf-8", Dlna.fault(f.code, f.getMessage())); }
                catch (Exception e) { log.accept("指令处理失败：" + e.getClass().getSimpleName()); send(client, 500, Map.of(), "text/xml; charset=utf-8", Dlna.fault(501, "Action Failed")); }
            } else if ((r.method.equals("SUBSCRIBE") || r.method.equals("UNSUBSCRIBE")) && r.path.startsWith("/event/")) subscribe(client, r);
            else send(client, 405, Map.of("Allow", "GET, HEAD, POST, SUBSCRIBE, UNSUBSCRIBE"), "text/plain", "Method not allowed");
        } catch (Exception ignored) { /* Peer can disconnect while sending a command. */ }
    }
    private void subscribe(Socket client, Request r) throws Exception {
        String service = r.path.substring(7), sid = r.headers.get("sid");
        if (!Dlna.SERVICES.contains(service)) { send(client, 404, Map.of(), "text/plain", "Not found"); return; }
        subscriptions.values().removeIf(s -> s.expires < System.nanoTime());
        if (r.method.equals("UNSUBSCRIBE")) {
            Subscription s = sid == null ? null : subscriptions.get(sid);
            if (s == null || !s.peer.equals(r.peer) || !s.service.equals(service)) { send(client, 412, Map.of(), "text/plain", "Invalid subscription"); return; }
            subscriptions.remove(sid); send(client, 200, Map.of(), "text/plain", ""); return;
        }
        int seconds = 1800;
        try { seconds = Math.max(30, Math.min(1800, Integer.parseInt(r.headers.getOrDefault("timeout", "Second-1800").replaceFirst("(?i)^Second-", "")))); } catch (NumberFormatException ignored) { }
        Subscription s;
        if (sid != null) {
            s = subscriptions.get(sid);
            if (s == null || !s.peer.equals(r.peer) || !s.service.equals(service) || r.headers.containsKey("callback") || r.headers.containsKey("nt")) { send(client, 412, Map.of(), "text/plain", "Invalid subscription"); return; }
            s.renew(seconds);
        } else {
            try {
                // A single LAN host must not be able to exhaust the shared subscription table.
                long own = subscriptions.values().stream().filter(x -> x.peer.equals(r.peer)).count();
                if (subscriptions.size() >= 16 || own >= SUBSCRIPTIONS_PER_PEER || !"upnp:event".equalsIgnoreCase(r.headers.get("nt"))) throw new IllegalArgumentException();
                String raw = r.headers.getOrDefault("callback", "");
                if (!raw.startsWith("<") || !raw.endsWith(">")) throw new IllegalArgumentException();
                URI uri = URI.create(raw.substring(1, raw.length()-1));
                // Numeric callback must point back to the sender; no arbitrary URL fetching or DNS rebinding.
                if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null || !r.peer.getHostAddress().equals(uri.getHost()) || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) throw new IllegalArgumentException();
                s = new Subscription(service, uri, r.peer, seconds); subscriptions.put(s.id, s);
            } catch (Exception e) { send(client, 412, Map.of(), "text/plain", "Invalid callback"); return; }
        }
        send(client, 200, Map.of("SID", s.id, "TIMEOUT", "Second-" + seconds), "text/plain", "");
        if (sid == null) events.schedule(() -> notifyOne(s), 150, TimeUnit.MILLISECONDS);
    }
    public void publish() {
        if (closed || subscriptions.isEmpty()) return;
        synchronized (this) { if (publishPending) return; publishPending = true; }
        try {
            events.schedule(() -> {
                synchronized (this) { publishPending = false; }
                subscriptions.values().forEach(this::notifyOne);
            }, PUBLISH_COALESCE_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) { }
    }
    private void notifyOne(Subscription s) {
        if (closed || !subscriptions.containsKey(s.id)) return;
        if (s.expires < System.nanoTime()) { subscriptions.remove(s.id); return; }
        try {
            byte[] body = Dlna.eventXml(s.service, controller.event(s.service, s.peer)).getBytes(StandardCharsets.UTF_8);
            int port = s.callback.getPort() < 0 ? 80 : s.callback.getPort();
            try (Socket connection = new Socket()) {
                connection.connect(new InetSocketAddress(s.peer, port), 2000); connection.setSoTimeout(2000);
                String path = s.callback.getRawPath().isEmpty() ? "/" : s.callback.getRawPath();
                if (s.callback.getRawQuery() != null) path += "?" + s.callback.getRawQuery();
                long sequence = s.sequence; s.sequence = sequence >= 4294967295L ? 1 : sequence + 1;
                String header = "NOTIFY " + path + " HTTP/1.1\r\nHOST: " + s.peer.getHostAddress() + ":" + port + "\r\nCONTENT-TYPE: text/xml; charset=utf-8\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: " + s.id + "\r\nSEQ: " + sequence + "\r\nCONTENT-LENGTH: " + body.length + "\r\nConnection: close\r\n\r\n";
                connection.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII)); connection.getOutputStream().write(body); connection.getOutputStream().flush();
                String status = line(connection.getInputStream(), 1024, Long.MAX_VALUE);
                if (status == null || !status.matches("HTTP/1\\.[01] 2\\d\\d.*")) subscriptions.remove(s.id);
                else s.failures = 0;
            }
        } catch (Exception unreachable) {
            // A sleeping sender may miss a few events; a gone one would otherwise cost 2 s per round until expiry.
            if (++s.failures >= MAX_NOTIFY_FAILURES) subscriptions.remove(s.id);
        }
    }
    private static Request read(Socket client) throws Exception {
        InputStream input = new BufferedInputStream(client.getInputStream());
        // Socket timeout is per read; a whole-request deadline stops slow-drip clients holding the few workers.
        long deadline = System.nanoTime() + REQUEST_DEADLINE_NANOS;
        String start = line(input, 8192, deadline);
        if (start == null) throw new IOException();
        String[] pieces = start.split(" ");
        if (pieces.length != 3 || !pieces[1].startsWith("/") || !pieces[2].startsWith("HTTP/1.")) throw new IOException();
        Map<String,String> headers = new LinkedHashMap<>();
        int total = start.length();
        for (String h; (h = line(input, 8192, deadline)) != null && !h.isEmpty();) {
            total += h.length(); if (total > 16384) throw new IOException();
            int colon = h.indexOf(':'); if (colon <= 0) throw new IOException();
            String key = h.substring(0, colon).trim().toLowerCase(Locale.US);
            if (headers.containsKey(key)) throw new IOException();
            headers.put(key, h.substring(colon+1).trim());
        }
        if (headers.containsKey("transfer-encoding")) throw new IOException();
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        if (length < 0 || length > 131072) throw new IOException();
        byte[] bytes = new byte[length]; int offset = 0;
        while (offset < length) { if (System.nanoTime() - deadline > 0) throw new SocketTimeoutException(); int n = input.read(bytes, offset, length-offset); if (n < 0) throw new EOFException(); offset += n; }
        return new Request(pieces[0], pieces[1].split("\\?",2)[0], headers, new String(bytes, StandardCharsets.UTF_8), client.getInetAddress());
    }
    private static String line(InputStream input, int limit, long deadline) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = input.read()) != -1;) { if (deadline != Long.MAX_VALUE && System.nanoTime() - deadline > 0) throw new SocketTimeoutException(); if (c == '\n') return new String(b.toByteArray(), StandardCharsets.US_ASCII).replace("\r", ""); if (b.size() >= limit) throw new IOException(); b.write(c); }
        return null;
    }
    private static void send(Socket s, int code, Map<String,String> headers, String mime, String body) throws IOException { send(s, code, headers, mime, body, false); }
    private static void send(Socket s, int code, Map<String,String> headers, String mime, String body, boolean head) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = switch (code) { case 200 -> "OK"; case 400 -> "Bad Request"; case 404 -> "Not Found"; case 405 -> "Method Not Allowed"; case 412 -> "Precondition Failed"; default -> "Internal Server Error"; };
        StringBuilder h = new StringBuilder("HTTP/1.1 " + code + " " + reason + "\r\nSERVER: " + SERVER + "\r\nDATE: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atZone(ZoneOffset.UTC)) + "\r\nCONTENT-TYPE: " + mime + "\r\nCONTENT-LENGTH: " + bytes.length + "\r\nConnection: close\r\n");
        headers.forEach((k,v) -> h.append(k).append(": ").append(v).append("\r\n"));
        OutputStream output = s.getOutputStream(); output.write(h.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII)); if (!head) output.write(bytes); output.flush();
    }
    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow(); events.shutdownNow(); subscriptions.clear();
    }
}
