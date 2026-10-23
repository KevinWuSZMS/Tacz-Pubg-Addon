package com.example.examplemod.inventory;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Checks native transfer ABI and the callback/ownership contracts behind acquisition conservation. */
public final class InventoryTransferChecks {
    private static final String SLOT = "net/minecraft/world/inventory/Slot";
    private static final String SERVICE = "com/example/examplemod/inventory/MatchInventoryService";
    private static int checks;

    public static void main(String[] args) throws Exception {
        MethodNode pickup = method(SERVICE, "permitsClick");
        var take = calls(pickup, SLOT, "safeTake");
        check(take.size() == 1, "manual weapon acquisition uses one real slot extraction transaction");
        check(take.getFirst().desc.equals("(IILnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/item/ItemStack;"),
                "installed safeTake signature matches");
        AbstractInsnNode store = nextCode(take.getFirst());
        check(store instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE,
                "extracted stack is retained independently of the output preview");
        int takenLocal = ((VarInsnNode) store).var;
        var inserts = calls(pickup, SERVICE, "insert");
        check(!inserts.isEmpty(), "manual weapon extraction routes to fixed equipment slots");
        for (var insert : inserts) {
            // The final boolean argument may be a constant or a conditional !owned branch.
            // Its bytecode does not produce a reference, so find the preceding reference producer.
            AbstractInsnNode source = previousReference(insert);
            check(source instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == takenLocal,
                    "never acquire a mutable recipe preview directly; insert only the safeTake result");
        }
        check(index(pickup, calls(pickup, SLOT, "mayPickup").getFirst()) < index(pickup, take.getFirst()),
                "source permissions checked before extraction");
        check(index(pickup, calls(pickup, "com/example/examplemod/inventory/InventoryConfig", "active").getFirst())
                        < index(pickup, take.getFirst()), "manual routing remains match gated");

        ClassNode nativeSlot = type(SLOT);
        MethodNode safeTake = find(nativeSlot, "safeTake");
        check(!calls(safeTake, SLOT, "tryRemove").isEmpty(), "native safeTake performs guarded removal");
        boolean onTake = false;
        for (AbstractInsnNode instruction : safeTake.instructions) {
            if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                for (Object argument : dynamic.bsmArgs) if (argument instanceof Handle handle
                        && handle.getOwner().equals(SLOT)) {
                    var callback = find(nativeSlot, handle.getName());
                    onTake |= !calls(callback, SLOT, "onTake").isEmpty();
                }
            }
        }
        check(onTake, "safeTake calls onTake so crafting/anvil/trading inputs are consumed");
        check(!calls(method("net/minecraft/world/inventory/CraftingMenu", "quickMoveStack"),
                "net/minecraft/world/inventory/Slot", "onTake").isEmpty(),
                "vanilla shift transfer retains the recipe consumption callback");

        MethodNode routing = method("com/example/examplemod/inventory/mixin/MatchInventoryMenuMixin", "pubg$routeTransfer");
        check(!calls(routing, "com/example/examplemod/backpack/BackpackOwnership", "isTemporaryInputStack").isEmpty(),
                "returning owned temporary inputs is distinguished from acquiring recipe outputs");
        check(!calls(method("com/example/examplemod/backpack/BackpackService", "used"),
                "com/example/examplemod/backpack/BackpackOwnership", "temporaryInputs").isEmpty(),
                "all inputs returned on close remain included in capacity");
        check(!calls(method("com/example/examplemod/backpack/MatchInventorySnapshots", "capture"),
                "com/example/examplemod/backpack/BackpackOwnership", "temporaryInputs").isEmpty(),
                "snapshot and capacity share the same temporary input ownership");

        for (String operation : List.of("begin", "restoreIfStale")) {
            MethodNode transaction = method("com/example/examplemod/backpack/MatchInventorySnapshots", operation);
            check(index(transaction, calls(transaction, "com/example/examplemod/backpack/MatchInventorySnapshots", "persist").getFirst())
                            < index(transaction, calls(transaction, "net/minecraft/server/level/ServerPlayer", "closeContainer").getFirst()),
                    operation + " persists originals before a container close can return/drop inputs");
        }
        System.out.println("Inventory transfer checks passed: " + checks);
    }

    private static ClassNode type(String owner) throws Exception {
        try (var input = InventoryTransferChecks.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            check(input != null, "class exists: " + owner);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
    private static MethodNode method(String owner, String name) throws Exception { return find(type(owner), name); }
    private static MethodNode find(ClassNode type, String name) {
        return type.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow(
                () -> new AssertionError("Missing method " + type.name + "." + name));
    }
    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) result.add(call);
        return result;
    }
    private static int index(MethodNode method, AbstractInsnNode instruction) { return method.instructions.indexOf(instruction); }
    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static AbstractInsnNode previousReference(AbstractInsnNode instruction) {
        while ((instruction = previousCode(instruction)) != null) {
            if (instruction.getOpcode() == Opcodes.ALOAD || instruction.getOpcode() == Opcodes.ACONST_NULL
                    || instruction.getOpcode() == Opcodes.NEW || instruction.getOpcode() == Opcodes.AALOAD) return instruction;
            if (instruction instanceof MethodInsnNode call) {
                int kind = org.objectweb.asm.Type.getReturnType(call.desc).getSort();
                if (kind == org.objectweb.asm.Type.OBJECT || kind == org.objectweb.asm.Type.ARRAY) return instruction;
            }
            if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field
                    && (field.desc.startsWith("L") || field.desc.startsWith("["))) return instruction;
        }
        return null;
    }
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
}
