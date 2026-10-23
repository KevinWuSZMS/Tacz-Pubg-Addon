package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel.MapDefinition;

import java.util.Objects;
import java.util.UUID;

/** A running match owns an immutable snapshot, never a live editor draft. */
public record MatchSession(UUID sessionId, long startedTick, MapDefinition map) {
    public MatchSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(map, "map");
    }
}
