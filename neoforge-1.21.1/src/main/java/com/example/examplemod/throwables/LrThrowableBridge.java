package com.example.examplemod.throwables;

import me.xjqsh.lrtactical.item.throwable.ThrowableData;
import me.xjqsh.lrtactical.item.throwable.flash.StunThrowableData;
import net.minecraft.resources.ResourceLocation;

/** Stable PUBG item identities use LR's existing display, animation, sound and effect assets. */
public final class LrThrowableBridge {
    private LrThrowableBridge() {}
    public static ResourceLocation id(ThrowableKind kind) {
        return ResourceLocation.fromNamespaceAndPath("lrtactical", switch (kind) {
            case FRAG, BLUE_ZONE -> "m67";
            case STUN -> "flash_grenade";
            case SMOKE -> "smoke_grenade";
            case MOLOTOV -> "molotov";
            case STICKY -> "rgn";
            case C4 -> "c4";
        });
    }
    public static ThrowableData data(ThrowableKind kind) {
        return ThrowableSettingsService.nativeData(kind);
    }
    public static StunThrowableData.StunData stunData() {
        ThrowableData data = data(ThrowableKind.STUN);
        return data instanceof StunThrowableData stun ? stun.getStunData() : new StunThrowableData.StunData();
    }
}
