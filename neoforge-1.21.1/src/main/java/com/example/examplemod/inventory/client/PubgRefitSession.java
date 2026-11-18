package com.example.examplemod.inventory.client;

/** Target and exit decisions shared by the native refit wrapper and offline regression checks. */
final class PubgRefitSession {
    enum Destination { KEEP, INVENTORY, GAME }
    record Exit(boolean flushNative, Destination destination) { }
    private final int slot;
    private boolean closed;

    PubgRefitSession(int slot) {
        if (slot < 0 || slot >= 9) throw new IllegalArgumentException("Refit requires a hotbar slot");
        this.slot = slot;
    }

    boolean matches(boolean sameOwnerAndWorld, int selected, boolean sameGun, boolean inMatch) {
        return !closed && sameOwnerAndWorld && selected == slot && sameGun && inMatch;
    }

    Exit close(boolean closeAll, boolean canReturn, boolean targetValid) {
        if (closed) return new Exit(false, Destination.KEEP);
        closed = true;
        return new Exit(targetValid, !closeAll && canReturn ? Destination.INVENTORY : Destination.GAME);
    }
}
