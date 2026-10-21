/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package cn.local.livingroomcast;

import android.net.Uri;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.*;
import cn.local.livingroomcast.core.MediaTarget;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Applies the media target policy to every load the player makes — playlists, HLS segments, keys and the
 * final URL after redirects — not just the URI the sender cast. Only HTTP(S) is reachable: a remote
 * playlist must not be able to point the TV at file://, content:// or local-only services.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class GuardedDataSource implements DataSource {
    private static final long ALLOWED_TTL_MS = 60_000;
    /** Hosts whose resolved addresses were all acceptable; avoids a DNS lookup per HLS segment. */
    private static final Map<String, Long> allowed = new ConcurrentHashMap<>();

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;
        private final Supplier<String> self;
        Factory(DataSource.Factory upstream, Supplier<String> self) { this.upstream = upstream; this.self = self; }
        @Override public DataSource createDataSource() { return new GuardedDataSource(upstream.createDataSource(), self); }
    }

    private final DataSource upstream;
    private final Supplier<String> self;
    private GuardedDataSource(DataSource upstream, Supplier<String> self) { this.upstream = upstream; this.self = self; }

    static void check(Uri uri, String self) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        String host = uri.getHost();
        if (!(scheme.equals("http") || scheme.equals("https")) || MediaTarget.blockedHost(host, self)) throw blocked();
        String key = host.toLowerCase(Locale.US) + "|" + self;
        Long until = allowed.get(key);
        if (until != null && until > System.currentTimeMillis()) return;
        for (InetAddress a : InetAddress.getAllByName(host)) if (MediaTarget.blockedAddress(a, self)) throw blocked();
        if (allowed.size() > 256) allowed.clear();
        allowed.put(key, System.currentTimeMillis() + ALLOWED_TTL_MS);
    }
    private static DataSourceException blocked() {
        return new DataSourceException("Blocked local media target", null, PlaybackException.ERROR_CODE_IO_NO_PERMISSION);
    }

    @Override public void addTransferListener(TransferListener listener) { upstream.addTransferListener(listener); }
    @Override public long open(DataSpec spec) throws IOException {
        String me = self.get();
        check(spec.uri, me);
        long length = upstream.open(spec);
        Uri finalUri = upstream.getUri();
        if (finalUri != null && !finalUri.equals(spec.uri)) {
            try { check(finalUri, me); } catch (IOException e) { upstream.close(); throw e; }
        }
        return length;
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException { return upstream.read(buffer, offset, length); }
    @Override public Uri getUri() { return upstream.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }
    @Override public void close() throws IOException { upstream.close(); }
}
