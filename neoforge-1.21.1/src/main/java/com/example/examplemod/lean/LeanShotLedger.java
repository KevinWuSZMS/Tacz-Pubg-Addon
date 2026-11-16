package com.example.examplemod.lean;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-player, bounded rendezvous between optional pose packets and native TaCZ rounds. */
public final class LeanShotLedger {
    public static final int MAX_ROUNDS = 64;
    public static final int MAX_GROUPS = 64;
    public static final long LIFETIME_NANOS = 2_000_000_000L;
    private final Map<Long, Group> groups = new LinkedHashMap<>();

    public record Sample(int sequence, double inputAgeSeconds, float pitch, float yaw) {
        public boolean valid() {
            return sequence >= 0 && Double.isFinite(inputAgeSeconds) && inputAgeSeconds >= 0
                    && Float.isFinite(pitch) && pitch >= -90 && pitch <= 90
                    && Float.isFinite(yaw) && Math.abs(yaw) <= 360_000;
        }
    }

    private static final class Group {
        final long created;
        final Map<Integer, Sample> samples = new LinkedHashMap<>();
        int consumed = -1;
        Group(long created) { this.created = created; }
    }

    public boolean offer(long timestamp, int round, Sample sample, long now) {
        if (timestamp < 0 || round < 0 || round >= MAX_ROUNDS || !sample.valid()) return false;
        Group group = group(timestamp, now);
        if (round <= group.consumed || group.samples.containsKey(round)) return false;
        group.samples.put(round, sample);
        return true;
    }

    /** A missing packet is final for this round: later arrivals cannot relocate live bullets. */
    public Sample claim(long timestamp, int round, long now) {
        Group group = group(timestamp, now);
        if (round <= group.consumed) return null;
        group.consumed = round;
        Sample sample = group.samples.remove(round);
        group.samples.keySet().removeIf(index -> index < round);
        return sample;
    }

    private Group group(long timestamp, long now) {
        groups.values().removeIf(group -> now - group.created > LIFETIME_NANOS);
        Group existing = groups.get(timestamp);
        if (existing != null) return existing;
        if (groups.size() >= MAX_GROUPS) groups.remove(groups.keySet().iterator().next());
        Group group = new Group(now);
        groups.put(timestamp, group);
        return group;
    }
}
