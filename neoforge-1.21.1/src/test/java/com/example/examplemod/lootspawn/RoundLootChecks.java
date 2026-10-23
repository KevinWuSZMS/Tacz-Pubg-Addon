package com.example.examplemod.lootspawn;

import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.spawn.MapSpawnBounds;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.util.List;

/** Small offline lifecycle and boundary regression; no world or game startup. */
public final class RoundLootChecks {
    static int checks;
    public static void main(String[] args) throws Exception {
        var dimension = ResourceLocation.parse("minecraft:overworld");
        var a = new MapDefinition(1, "A", dimension, 0, 0, 16, 16, null, 1, List.of());
        var b = new MapDefinition(2, "B", dimension, 16, 0, 32, 16, null, 1, List.of());
        var inside = new BlockPos(15, 64, 1); var outside = new BlockPos(16, 64, 1);
        check(MapSpawnBounds.contains(a, dimension, inside), "last full block belongs to A");
        check(!MapSpawnBounds.contains(a, dimension, outside), "exclusive border belongs to B");
        check(!MapSpawnBounds.containedByOneMap(List.of(a, b), dimension, inside, outside), "one selection cannot bridge maps");
        check(!MapSpawnBounds.contains(a, ResourceLocation.parse("minecraft:the_nether"), inside), "dimension isolated");
        var fractional = new MapDefinition(3, "C", dimension, .5, .5, 15.5, 15.5, null, 1, List.of());
        check(!MapSpawnBounds.contains(fractional, dimension, new BlockPos(15, 64, 1)), "whole floor must fit fractional border");
        var data = new RoundLootData();
        var first = data.begin(a); var other = data.begin(b);
        var loaded = RoundLootData.load(data.save(new CompoundTag(), null), null);
        check(loaded.active.isEmpty() && loaded.retired.containsAll(List.of(first.token(), other.token())), "restart retires both interrupted rounds");
        check(loaded.cleanups.size() == 2, "restart retains unloaded ground cleanup ranges");
        data.end(a);
        check(data.retired.contains(first.token()) && !data.retired.contains(other.token()) && data.active.containsKey(2L), "ending A cannot expire B");
        var next = data.begin(a);
        check(!next.token().equals(first.token()) && !data.retired.contains(next.token()), "next round uses fresh identity");
        loaded = RoundLootData.load(data.save(new CompoundTag(), null), null);
        check(loaded.retired.contains(first.token()) && loaded.retired.contains(next.token()), "offline tombstones survive subsequent rounds");
        var saved = new LootRespawnSavedData();
        saved.begin(LootConfigModel.defaultDocument(), List.of(
                new LootRespawnSavedData.Job(1, dimension, List.of(inside, outside), 1, ""),
                new LootRespawnSavedData.Job(2, dimension, List.of(new BlockPos(20, 64, 2)), 2, "")), null);
        long epoch = saved.epoch(); saved.cancel(a);
        check(saved.epoch() != epoch, "cancellation invalidates an in-progress global runner");
        check(saved.job(1).floors().equals(List.of(outside)) && saved.job(2) != null, "cancellation preserves other-map floors");
        saved.cancel(b); check(saved.jobs().isEmpty(), "all remaining B work is cancelled");
        // Mixin methods target the installed Minecraft ABI, not a guessed version.
        target("net/minecraft/world/item/ItemStack", "isEmpty", "()Z");
        target("net/minecraft/world/inventory/ResultContainer", "setItem", "(ILnet/minecraft/world/item/ItemStack;)V");
        target("net/minecraft/world/item/alchemy/PotionBrewing", "mix", "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;");
        System.out.println("Round loot checks passed: " + checks);
    }
    static void target(String owner, String name, String descriptor) throws Exception {
        var node = new ClassNode();
        try (var stream = RoundLootChecks.class.getResourceAsStream("/" + owner + ".class")) {
            if (stream == null) throw new AssertionError(owner);
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE);
        }
        check(node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)), owner + "." + name);
    }
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
