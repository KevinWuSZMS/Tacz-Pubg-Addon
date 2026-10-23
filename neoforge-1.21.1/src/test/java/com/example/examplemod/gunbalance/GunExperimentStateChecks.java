package com.example.examplemod.gunbalance;

import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettingCategory;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettings;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Confirmation ordering and disconnect isolation, without constructing a client or a world. */
public final class GunExperimentStateChecks {
    private static int checks;
    public static void main(String[] args) {
        check(!GunBalanceService.shouldApplySettings(null, GunSettings.defaults()), "initial settings need no reload");
        check(GunBalanceService.shouldApplySettings(null,
                GunSettings.defaults().withValue(GunSettingCategory.MAGAZINE, LootConfigModel.EXPERIMENTAL)),
                "magazine experiment is an implemented resource profile");
        check(!GunBalanceService.shouldApplySettings(null,
                GunSettings.defaults().withValue(GunSettingCategory.GRIP, LootConfigModel.EXPERIMENTAL)),
                "stored but unimplemented grip option does not reload resources");
        GunExperimentState.clearClient();
        check(!GunExperimentState.clientEnabled(), "a new connection starts initial");
        check(!GunExperimentState.clientMagazineEnabled(), "magazines start disabled independently");
        check(!GunExperimentState.enabled(null), "no world has no experiment");
        check(!GunExperimentState.magazineEnabled(null), "no world cannot authorize magazine timing");
        check(GunExperimentState.acceptClient(0, true), "first server confirmation accepted");
        check(GunExperimentState.clientEnabled(), "applied confirmation enables experiment");
        check(!GunExperimentState.clientMagazineEnabled(), "balance-only compatibility entry never enables magazines");
        check(!GunExperimentState.acceptClient(0, false), "duplicate revision rejected");
        check(!GunExperimentState.acceptClient(-1, false), "negative revision rejected");
        check(GunExperimentState.clientEnabled(), "rejected packets cannot turn it off");
        check(GunExperimentState.acceptClient(5, false), "newer initial confirmation accepted");
        check(!GunExperimentState.acceptClient(4, true), "late old experiment cannot reactivate");
        check(!GunExperimentState.clientEnabled(), "initial remains applied");
        check(GunExperimentState.acceptClient(6, false, true), "magazine-only profile accepted");
        check(!GunExperimentState.clientEnabled() && GunExperimentState.clientMagazineEnabled(),
                "magazine experiment does not depend on damage experiment");
        check(!GunExperimentState.acceptClient(6, true, false), "duplicate cannot change either profile bit");
        check(GunExperimentState.clientMagazineEnabled(), "duplicate cannot cancel magazine authorization");
        check(GunExperimentState.acceptClient(7, true, false), "switching back to initial magazine profile accepted");
        check(GunExperimentState.clientEnabled() && !GunExperimentState.clientMagazineEnabled(),
                "disabling magazines does not disable balance");
        check(!GunExperimentState.acceptClient(6, false, true), "late old profile cannot reactivate magazines");
        check(!GunExperimentState.clientMagazineEnabled(), "magazines stay off after an old packet");
        check(new GunExperimentPayloads.State(0, true).equals(new GunExperimentPayloads.State(0, true, false)),
                "legacy construction is explicitly magazine-disabled");
        for (long revision : new long[] {0, 1, 127, 128, 16384, Long.MAX_VALUE}) {
            for (boolean enabled : new boolean[] {false, true}) {
                for (boolean magazineEnabled : new boolean[] {false, true}) {
                    var state = new GunExperimentPayloads.State(revision, enabled, magazineEnabled);
                    var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                    try {
                        GunExperimentPayloads.State.CODEC.encode(buffer, state);
                        check(state.equals(GunExperimentPayloads.State.CODEC.decode(buffer)), "state round-trip");
                        check(buffer.readableBytes() == 0, "exact packet boundary");
                    } finally { buffer.release(); }
                }
            }
        }
        try {
            new GunExperimentPayloads.State(-1, true);
            throw new AssertionError("invalid revision accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        GunExperimentState.clearClient();
        check(!GunExperimentState.clientEnabled(), "disconnect clears experimental state");
        check(!GunExperimentState.clientMagazineEnabled(), "disconnect clears magazine authorization");
        check(GunExperimentState.acceptClient(0, false), "new server can restart revision zero");
        check(!GunExperimentState.clientEnabled(), "new server does not inherit old setting");
        System.out.println("GunExperimentStateChecks: " + checks + " confirmation/codec checks passed.");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
