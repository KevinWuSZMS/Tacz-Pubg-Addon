package com.example.examplemod.vitals.client;

import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Real shared eligibility decisions plus compiled client wiring; no client/GL initialization. */
public final class MedicineInventoryInputChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        boolean inventoryScreen = MedicineInputRules.allowsScreen(false, true);
        check(inventoryScreen, "PUBG inventory permits medicine without requiring a nonempty main hand");
        check(MedicineInputRules.allowsScreen(true, false), "ordinary gameplay remains permitted");
        check(!MedicineInputRules.allowsScreen(false, false), "vanilla inventory and other screens still cancel medicine");

        // Client finishes its last held bandage before the authoritative inventory refill arrives.
        boolean gap = MedicineInputRules.hasChainIntent(inventoryScreen, 100, false, true);
        check(gap, "cross-stack hand-empty gap retains the existing chain intent");
        check(!MedicineInputRules.acceptsContinuation(gap, true, true, false, false, false, true, false),
                "intent alone cannot authorize a cast before the replacement stack arrives");
        check(MedicineInputRules.acceptsContinuation(gap, true, true, false, true, false, true, false),
                "authoritative refill and matching continuation can resume the preserved chain");
        boolean canceledGap = MedicineInputRules.hasChainIntent(false, 0, false, true);
        check(!MedicineInputRules.acceptsContinuation(canceledGap, true, true, false, true, false, true, false),
                "cancel in the hand-empty gap rejects a delayed otherwise valid refill reply");
        check(!MedicineInputRules.hasChainIntent(true, 0, true, true), "expired intent cannot restart from remaining bandages");
        check(!MedicineInputRules.hasChainIntent(true, 50, false, false), "exhausted inventory does not retain a chain");

        // Vanilla keys are released when a screen opens; physical inventory actions must still interrupt.
        boolean inventoryJump = MedicineInputRules.movementKeyPressed(true, false, false, true, false);
        boolean inventorySprint = MedicineInputRules.movementKeyPressed(true, false, false, false, true);
        check(inventoryJump, "held inventory jump interrupts despite cleared native key state");
        check(inventorySprint, "held custom match sprint interrupts despite cleared native key state");
        check(!MedicineInputRules.acceptsContinuation(gap, true, true, false, true, inventoryJump, true, false),
                "delayed continuation cannot restart while inventory jump is held");
        check(!MedicineInputRules.acceptsContinuation(gap, true, true, false, true, inventorySprint, true, false),
                "delayed continuation cannot restart while inventory sprint is held");
        check(!MedicineInputRules.movementKeyPressed(true, true, true, false, false),
                "stale vanilla toggles do not invent movement in an inventory with released physical keys");
        check(MedicineInputRules.movementKeyPressed(false, false, true, false, false),
                "ordinary gameplay keeps the original native sprint interrupt");
        check(MedicineInputRules.movementKeyPressed(false, true, false, false, false),
                "ordinary gameplay keeps the original native jump interrupt");
        check(!MedicineInputRules.movementKeyPressed(false, false, false, true, true),
                "inventory physical polling does not change ordinary gameplay key semantics");
        check(!MedicineInputRules.acceptsContinuation(gap, false, true, false, true, false, true, false), "old-session reply is rejected");
        check(!MedicineInputRules.acceptsContinuation(gap, true, false, false, true, false, true, false), "switched slot/hand reply is rejected");
        check(!MedicineInputRules.acceptsContinuation(gap, true, true, true, true, false, true, false), "paused reply is rejected");
        check(!MedicineInputRules.acceptsContinuation(gap, true, true, false, true, false, false, false), "75 percent cap stops the chain");
        check(!MedicineInputRules.acceptsContinuation(gap, true, true, false, true, false, true, true), "another item use cannot be replaced");
        wiring();
        hudWiring();
        System.out.println("MedicineInventoryInputChecks: " + checks + " chain-gap, cancellation, movement and foreground HUD checks passed; no game launched.");
    }

    private static void hudWiring() throws Exception {
        String vitals = "com/example/examplemod/vitals/client/";
        String throwable = "com/example/examplemod/throwables/client/ThrowableClient";
        String foreground = vitals + "InventoryActionHud";
        gatedDraw(method(read(vitals + "PubgMedicineHud"), "render"), foreground, vitals + "PubgMedicineHud", "renderContents", false);
        gatedDraw(method(read(vitals + "MedicineUseToast"), "render"), foreground, vitals + "MedicineUseToast", "renderContents", false);
        gatedDraw(method(read(throwable), "render"), foreground, throwable, "renderStatus", false);
        var pass = method(read(foreground), "renderForeground");
        gatedDraw(pass, foreground, vitals + "PubgMedicineHud", "renderContents", true);
        check(callCount(pass, vitals + "PubgMedicineHud", "renderContents") == 1
                        && callCount(pass, throwable, "renderInventoryStatus") == 1
                        && callCount(pass, vitals + "MedicineUseToast", "renderContents") == 1,
                "inventory foreground draws medicine/rescue, throwable status and use messages exactly once");
        check(!calls(pass, "net/minecraft/client/gui/Gui", "render")
                        && !calls(pass, vitals + "WeaponStackRenderer", "render"),
                "foreground does not redraw the whole HUD or weapon thumbnails");
        boolean explicitDepth = false;
        for (var instruction : pass.instructions)
            if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Number number && number.doubleValue() == 1000)
                explicitDepth = true;
        check(explicitDepth && calls(pass, "com/mojang/blaze3d/vertex/PoseStack", "translate"),
                "foreground has explicit depth above the inventory model");
        int medicine = callIndex(pass, vitals + "PubgMedicineHud", "renderContents");
        int toast = callIndex(pass, vitals + "MedicineUseToast", "renderContents");
        int firstFlush = callIndex(pass, "net/minecraft/client/gui/GuiGraphics", "flush");
        int lastFlush = lastCallIndex(pass, "net/minecraft/client/gui/GuiGraphics", "flush");
        check(firstFlush >= 0 && firstFlush < medicine && lastFlush > toast,
                "foreground flushes before and after drawing so buffered translucent geometry is not reordered");
        var screen = method(read("com/example/examplemod/inventory/client/PubgInventoryScreen"), "render");
        int foregroundCall = callIndex(screen, foreground, "renderForeground");
        int tooltip = callIndex(screen, "net/minecraft/client/gui/GuiGraphics", "renderTooltip");
        check(callCount(screen, foreground, "renderForeground") == 1 && tooltip > foregroundCall,
                "inventory calls the foreground pass once before item/action tooltips");
        check(callIndex(screen, "com/mojang/blaze3d/vertex/PoseStack", "popPose") < foregroundCall,
                "inventory restores a pose before entering the HUD pass");
    }

    /** Check the compiled branch, not a source-text marker: the other render pass must return. */
    private static void gatedDraw(MethodNode method, String gateOwner, String drawOwner, String drawName, boolean foreground) {
        int gate = callIndex(method, gateOwner, "usesInventoryForeground");
        int draw = callIndex(method, drawOwner, drawName);
        check(gate >= 0 && draw > gate, method.name + " checks foreground ownership before drawing");
        var cursor = method.instructions.get(gate).getNext();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        check(cursor instanceof JumpInsnNode branch && branch.getOpcode() == (foreground ? Opcodes.IFNE : Opcodes.IFEQ),
                method.name + " selects the exclusive " + (foreground ? "inventory" : "ordinary HUD") + " pass");
        cursor = cursor.getNext();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        check(cursor != null && cursor.getOpcode() == Opcodes.RETURN, method.name + " skips the other pass without drawing");
    }

    private static void wiring() throws Exception {
        String vitals = "com/example/examplemod/vitals/client/";
        var chain = read(vitals + "BandageChainClient");
        var owner = method(chain, "validOwner");
        check(calls(owner, vitals + "MedicineClient", "screenAllowed"), "chain owner uses item-independent screen eligibility");
        check(!calls(owner, vitals + "MedicineClient", "active"), "chain owner does not depend on an emptying held stack");
        check(calls(method(chain, "accept"), vitals + "MedicineInputRules", "acceptsContinuation"), "continuation handler uses the exercised eligibility rules");
        check(calls(method(chain, "accept"), vitals + "MedicineClient", "movementKeyPressed"), "continuation checks inventory-aware physical movement");
        check(calls(method(chain, "tick"), vitals + "MedicineClient", "movementKeyPressed"), "chain ticking checks the same movement rule");
        check(calls(method(read(vitals + "PubgMedicineHud"), "tick"), vitals + "MedicineClient", "movementKeyPressed"),
                "active medicine cancellation uses the same movement rule");
        var input = method(read("com/example/examplemod/inventory/client/PubgInventoryInput"), "keyPressed");
        check(calls(input, vitals + "BandageChainClient", "hasIntent") && calls(input, vitals + "MedicineClient", "tryUseHeld"),
                "the distinct pickup binding can reach cancellation during the hand-empty gap");
        var throwable = read("com/example/examplemod/throwables/client/ThrowableClient");
        check(calls(method(throwable, "startAutomaticMedicineUse"), vitals + "BandageChainClient", "started"),
                "inventory QuickUse still registers the new main chain intent");
        var cancel = method(throwable, "requestCancel");
        int localCancel = callIndex(cancel, vitals + "BandageChainClient", "cancel");
        int sendCancel = callIndex(cancel, "net/neoforged/neoforge/network/PacketDistributor", "sendToServer");
        check(localCancel >= 0 && sendCancel >= 0 && localCancel < sendCancel,
                "local intent clears before the cancellation packet, rejecting late continuation replies");
    }
    private static ClassNode read(String name) throws Exception {
        try (InputStream input = MedicineInventoryInputChecks.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new AssertionError("Missing compiled class " + name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
    private static MethodNode method(ClassNode node, String name) {
        var methods = node.methods.stream().filter(m -> m.name.equals(name)).toList();
        if (methods.size() != 1) throw new AssertionError("Expected unique method " + node.name + "." + name);
        return methods.getFirst();
    }
    private static boolean calls(MethodNode method, String owner, String name) {
        return callIndex(method, owner, name) >= 0;
    }
    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return index;
            index++;
        }
        return -1;
    }
    private static int lastCallIndex(MethodNode method, String owner, String name) {
        int result = -1, index = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) result = index;
            index++;
        }
        return result;
    }
    private static int callCount(MethodNode method, String owner, String name) {
        int result = 0;
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) result++;
        return result;
    }
    private static void check(boolean pass, String scenario) {
        checks++;
        if (!pass) throw new AssertionError(scenario);
    }
}
