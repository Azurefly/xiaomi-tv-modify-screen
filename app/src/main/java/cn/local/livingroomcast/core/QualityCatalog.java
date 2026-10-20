/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */
package cn.local.livingroomcast.core;

import java.util.*;

/** Only resolutions actually advertised and fully supported become choices. */
public final class QualityCatalog {
    private QualityCatalog() {}
    public record Track(int group, int index, int width, int height, boolean supported, boolean selected) {}
    public record Choice(String key, int width, int height, List<Track> tracks) {
        public String label() {
            int shortSide = Math.min(width, height), longSide = Math.max(width, height);
            String name = shortSide == 2160 && longSide >= 3840 ? "4K" : shortSide + "p";
            return name + " · " + width + "×" + height;
        }
        /** Prefer the active codec group; never mix indices from different groups. */
        public int preferredGroup() {
            for (Track track : tracks) if (track.selected()) return track.group();
            return tracks.get(0).group();
        }
        public List<Integer> indices(int group) {
            List<Integer> values = new ArrayList<>();
            for (Track track : tracks) if (track.group() == group) values.add(track.index());
            return List.copyOf(values);
        }
    }
    public static List<Choice> from(List<Track> tracks) {
        Map<String, List<Track>> bySize = new LinkedHashMap<>();
        for (Track track : tracks) {
            if (!track.supported() || track.width() <= 0 || track.height() <= 0) continue;
            String key = track.width() + "x" + track.height();
            bySize.computeIfAbsent(key, k -> new ArrayList<>()).add(track);
        }
        List<Choice> choices = new ArrayList<>();
        for (var entry : bySize.entrySet()) {
            Track first = entry.getValue().get(0);
            choices.add(new Choice(entry.getKey(), first.width(), first.height(), List.copyOf(entry.getValue())));
        }
        choices.sort(Comparator.<Choice>comparingLong(c -> (long)c.width() * c.height()).reversed());
        return List.copyOf(choices);
    }
}
