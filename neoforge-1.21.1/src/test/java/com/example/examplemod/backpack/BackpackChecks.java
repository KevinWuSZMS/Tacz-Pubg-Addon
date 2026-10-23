package com.example.examplemod.backpack;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Acquisition conservation, downgrade safety, and exact native injection signatures. */
public final class BackpackChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        check(BackpackRules.capacity(0, false) == 70_000, "ordinary capacity starts at seventy");
        check(BackpackRules.capacity(1, false) == 220_000, "level one adds 150");
        check(BackpackRules.capacity(2, false) == 270_000, "level two adds 200");
        check(BackpackRules.capacity(3, true) == 370_000, "level three and vest bonuses are independent");
        check(BackpackRules.accepted(69_625, 70_000, 375, 30) == 1, "one fractional round fits exactly");
        check(BackpackRules.accepted(69_626, 70_000, 375, 30) == 0, "one thousandth over boundary is rejected");
        check(BackpackRules.accepted(100_000, 70_000, 1_000, 64) == 0, "existing overload accepts no extra items");
        check(BackpackRules.permitsChange(100_000, 90_000, 70_000), "overloaded inventories can unload");
        check(BackpackRules.permitsChange(100_000, 100_000, 70_000), "overloaded inventories can rearrange");
        check(!BackpackRules.permitsChange(100_000, 100_001, 70_000), "overload cannot increase");
        check(!BackpackRules.permitsEquipmentChange(80_000, 0, 10_000, 70_000),
                "removing a pack includes the unequipped pack's own weight and refuses overflow");
        check(BackpackRules.permitsEquipmentChange(60_000, 0, 10_000, 70_000), "exact-fit removal retains everything");
        check(!BackpackRules.permitsEquipmentChange(260_000, 10_000, 10_000, 220_000), "overfull downgrade is refused");
        check(BackpackRules.permitsEquipmentChange(260_000, 10_000, 10_000, 320_000), "upgrade is allowed");
        check(BackpackRules.add(BackpackRules.SATURATED, Long.MAX_VALUE / 2) == BackpackRules.SATURATED,
                "very large nested storage weights saturate, never wrap negative");
        // Multi-stack pickup must preserve rejected remainder while stopping exactly at capacity.
        for (int initial : new int[]{0, 69_625, 69_999, 70_000, 80_000}) {
            for (int weight : new int[]{375, 500, 700, 1_250, 27_000}) {
                int ground = 180, inserted = 0, used = initial;
                while (ground > 0) {
                    int requested = Math.min(64, ground);
                    int accepted = BackpackRules.accepted(used, 70_000, weight, requested);
                    check(accepted >= 0 && accepted <= requested, "partial pickup respects requested count");
                    if (accepted == 0) break;
                    ground -= accepted;
                    inserted += accepted;
                    used += accepted * weight;
                }
                check(ground + inserted == 180, "every unaccepted item remains at its original source");
                check(used <= Math.max(initial, 70_000), "multiple inventory slots cannot bypass capacity");
            }
        }
        target("net/minecraft/world/entity/player/Inventory", "add", "(ILnet/minecraft/world/item/ItemStack;)Z");
        target("net/minecraft/world/entity/player/Inventory", "placeItemBackInInventory", "(Lnet/minecraft/world/item/ItemStack;Z)V");
        target("net/minecraft/world/inventory/AbstractContainerMenu", "clicked",
                "(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V");
        target("net/minecraft/world/inventory/AbstractContainerMenu", "moveItemStackTo",
                "(Lnet/minecraft/world/item/ItemStack;IIZ)Z");
        target("net/minecraft/server/players/PlayerList", "save", "(Lnet/minecraft/server/level/ServerPlayer;)V");
        java.nio.file.Path temporary = java.nio.file.Files.createTempDirectory("pubg-inventory-commit-check-");
        try {
            var target = temporary.resolve("player.dat");
            var original = new net.minecraft.nbt.CompoundTag();
            original.putString("Inventory", "original items");
            AtomicPlayerFile.commit(target, original);
            var round = new net.minecraft.nbt.CompoundTag();
            round.put("OriginalInventory", original.copy());
            round.putString("Inventory", "cleared for round");
            AtomicPlayerFile.commit(target, round);
            var read = net.minecraft.nbt.NbtIo.readCompressed(target, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            check(read.equals(round), "original snapshot and cleared inventory commit together");
            check(net.minecraft.nbt.NbtIo.readCompressed(temporary.resolve("player.dat_old"),
                    net.minecraft.nbt.NbtAccounter.unlimitedHeap()).equals(original), "previous complete record remains recoverable");
            AtomicPlayerFile.commit(target, original);
            check(net.minecraft.nbt.NbtIo.readCompressed(target, net.minecraft.nbt.NbtAccounter.unlimitedHeap()).equals(original),
                    "restore and removal of snapshot commit as one record");
            var blocked = temporary.resolve("blocked.dat");
            java.nio.file.Files.createDirectory(blocked);
            java.nio.file.Files.writeString(blocked.resolve("sentinel"), "safe");
            boolean failed = false;
            try { AtomicPlayerFile.commit(blocked, round); } catch (java.io.IOException expected) { failed = true; }
            check(failed && java.nio.file.Files.readString(blocked.resolve("sentinel")).equals("safe"),
                    "failed replacement reports failure and preserves the prior target");
        } finally {
            try (var paths = java.nio.file.Files.walk(temporary)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
            }
        }
        System.out.println("Backpack checks passed: " + checks);
    }
    private static void target(String owner, String name, String descriptor) throws Exception {
        try (var input = BackpackChecks.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            check(input != null, "installed class exists: " + owner);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE);
            check(node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor)),
                    "required mixin target matches: " + name);
        }
    }
    private static void check(boolean result, String reason) {
        if (!result) throw new AssertionError(reason);
        checks++;
    }
}
