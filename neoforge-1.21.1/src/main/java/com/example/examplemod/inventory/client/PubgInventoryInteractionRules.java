package com.example.examplemod.inventory.client;

import com.example.examplemod.inventory.PubgInventoryTargets;
import java.util.function.IntPredicate;

/** Cursor destinations, independent of rendering and server-side item mutation. */
public final class PubgInventoryInteractionRules {
    public enum Operation { INVALID, RETURN, STORE, DROP, EQUIP, ATTACH, USE }
    public enum Badge { INVALID, NONE, BACKPACK, MINUS, EQUIP, SWAP }
    public record Target(Operation operation, int slot, boolean replacement) {
        public boolean valid() { return operation != Operation.INVALID; }
        public Badge badge() {
            return switch (operation) {
                case INVALID -> Badge.INVALID;
                case RETURN, USE -> Badge.NONE;
                case STORE -> Badge.BACKPACK;
                case DROP -> Badge.MINUS;
                case EQUIP, ATTACH -> replacement ? Badge.SWAP : Badge.EQUIP;
            };
        }
    }
    public record Source(boolean ground, boolean mounted, int slot, boolean attachment, boolean storable) { }
    public record Destination(boolean nearby, boolean inventory, int slot,
                              boolean acceptsAttachment, boolean acceptsItem, boolean occupied) { }
    public static final Target INVALID = new Target(Operation.INVALID, -1, false);
    public static final Target RETURN = new Target(Operation.RETURN, -1, false);
    public static final Target STORE = new Target(Operation.STORE, -1, false);
    private PubgInventoryInteractionRules() { }

    public static Target attachmentDefault(int selected, boolean ground, boolean directEquip,
                                           IntPredicate accepts, IntPredicate occupied) {
        if (ground && !directEquip) return STORE;
        int slot = PubgInventoryTargets.firstCandidate(selected, accepts);
        return slot >= 0 ? new Target(Operation.ATTACH, slot, occupied.test(slot)) : ground ? STORE : INVALID;
    }

    public static Target groundWeaponDefault(int selected, IntPredicate accepts, IntPredicate empty) {
        int slot = PubgInventoryTargets.firstCandidate(selected, candidate -> accepts.test(candidate) && empty.test(candidate));
        if (slot < 0 && selected >= 0 && selected < 3 && accepts.test(selected)) slot = selected;
        return slot >= 0 ? new Target(Operation.EQUIP, slot, !empty.test(slot)) : INVALID;
    }

    public static Target drag(Source source, Destination destination) {
        if (destination.nearby()) return source.ground() ? RETURN : new Target(Operation.DROP, -2, false);
        if (destination.inventory()) {
            if (!source.storable()) return INVALID;
            return !source.ground() && !source.mounted() && source.slot() < 36 ? RETURN : STORE;
        }
        int slot = destination.slot();
        if (slot < 0) return INVALID;
        if (source.attachment() && slot < 3) {
            if (source.mounted() && source.slot() == slot) return RETURN;
            // The attachment's own type decides the slot, including when crossing a different small slot.
            return destination.acceptsAttachment() ? new Target(Operation.ATTACH, slot, destination.occupied()) : INVALID;
        }
        if (source.mounted()) return INVALID;
        if (!source.ground() && source.slot() == slot) return RETURN;
        return destination.acceptsItem() ? new Target(Operation.EQUIP, slot, destination.occupied()) : INVALID;
    }
}
