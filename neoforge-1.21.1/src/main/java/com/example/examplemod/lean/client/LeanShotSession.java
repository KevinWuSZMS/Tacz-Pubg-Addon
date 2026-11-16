package com.example.examplemod.lean.client;

import java.util.Objects;

/** Client-thread ownership of queued shots. Ammo/component synchronization is not a new equip session. */
public final class LeanShotSession {
    public record Identity(Object player, Object level, Object connection, int slot, Object gun) {
        @Override
        public boolean equals(Object other) {
            // Entity.equals compares numeric IDs; a replacement LocalPlayer can reuse its predecessor's ID.
            return other instanceof Identity identity && player == identity.player && level == identity.level
                    && connection == identity.connection && slot == identity.slot && Objects.equals(gun, identity.gun);
        }

        @Override
        public int hashCode() {
            int hash = System.identityHashCode(player);
            hash = 31 * hash + System.identityHashCode(level);
            hash = 31 * hash + System.identityHashCode(connection);
            hash = 31 * hash + slot;
            return 31 * hash + Objects.hashCode(gun);
        }
    }
    public record Ticket(Identity identity, long generation) { }
    private Identity current;
    private long generation;

    public Ticket capture(Identity identity) {
        observe(identity);
        return new Ticket(identity, generation);
    }

    public void observe(Identity identity) {
        if (!Objects.equals(current, identity)) {
            current = identity;
            generation++;
        }
    }

    public void invalidate() {
        current = null;
        generation++;
    }

    public boolean valid(Ticket ticket, Identity identity) {
        observe(identity);
        return identity != null && ticket != null && ticket.generation() == generation
                && identity.equals(ticket.identity());
    }
}
