package com.example.examplemod.vitals.client;

/** Shared screen/input rules; these decisions do not require a live client. */
public final class MedicineInputRules {
    private MedicineInputRules() { }

    public static boolean allowsScreen(boolean noScreen, boolean pubgInventory) {
        return noScreen || pubgInventory;
    }

    public static boolean movementKeyPressed(boolean pubgInventory, boolean nativeJump, boolean nativeSprint,
                                             boolean inventoryJump, boolean inventorySprint) {
        // Ordinary gameplay retains its existing native key semantics.
        return pubgInventory ? inventoryJump || inventorySprint : nativeJump || nativeSprint;
    }

    public static boolean hasChainIntent(boolean ownerValid, int intentTicks, boolean heldBandage, boolean reserveBandage) {
        return ownerValid && intentTicks > 0 && (heldBandage || reserveBandage);
    }

    public static boolean acceptsContinuation(boolean intent, boolean session, boolean handAndSlot, boolean paused,
                                               boolean heldBandage, boolean moving, boolean canUse, boolean usingOtherItem) {
        return intent && session && handAndSlot && !paused && heldBandage && !moving && canUse && !usingOtherItem;
    }
}
