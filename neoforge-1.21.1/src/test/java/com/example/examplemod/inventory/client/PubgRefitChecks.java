package com.example.examplemod.inventory.client;

import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Exercise the wrapper's real target/exit decisions and its compiled native UI delegation. */
public final class PubgRefitChecks {
    private static int checks;
    private static final String CLIENT = "com/example/examplemod/inventory/client/";
    private static final String NATIVE = "com/tacz/guns/client/gui/GunRefitScreen";

    public static void main(String[] args) throws Exception {
        for (int slot = 0; slot < 9; slot++) {
            var session = new PubgRefitSession(slot);
            check(session.matches(true, slot, true, true), "target hotbar gun " + slot + " is eligible");
            check(!session.matches(true, (slot + 1) % 9, true, true), "switching to another slot rejects even the same gun type");
            check(!session.matches(false, slot, true, true), "player/world replacement invalidates the target");
            check(!session.matches(true, slot, false, true), "replaced, missing or locked gun invalidates the target");
            check(!session.matches(true, slot, true, false), "leaving the match invalidates the target");
        }
        invalidSlot(-1); invalidSlot(9); invalidSlot(35);

        var escape = new PubgRefitSession(2);
        var back = escape.close(false, true, true);
        check(back.flushNative() && back.destination() == PubgRefitSession.Destination.INVENTORY,
                "Escape saves native laser state then returns to the same inventory screen");
        check(!escape.matches(true, 2, true, true), "closed refit cannot issue more operations");
        var duplicate = escape.close(true, true, true);
        check(!duplicate.flushNative() && duplicate.destination() == PubgRefitSession.Destination.KEEP,
                "a repeated close cannot save twice or overwrite the return destination");

        var inventoryKey = new PubgRefitSession(1).close(true, true, true);
        check(inventoryKey.flushNative() && inventoryKey.destination() == PubgRefitSession.Destination.GAME,
                "inventory key saves native laser state and closes both screens");
        var changedGun = new PubgRefitSession(1).close(false, true, false);
        check(!changedGun.flushNative() && changedGun.destination() == PubgRefitSession.Destination.INVENTORY,
                "changed gun returns to inventory without uploading another gun's laser state");
        var departed = new PubgRefitSession(1).close(false, false, false);
        check(!departed.flushNative() && departed.destination() == PubgRefitSession.Destination.GAME,
                "leaving the player/world cannot reopen a stale inventory");
        wiring();
        attachmentSlots();
        System.out.println("PubgRefitChecks: " + checks + " target, exit, native attachment resources and UI delegation checks passed; no game launched.");
    }

    private static void attachmentSlots() throws Exception {
        var slots = read(CLIENT + "PubgAttachmentSlots");
        String gun = "com/tacz/guns/api/item/IGun";
        var draw = method(slots, "draw");
        check(calls(method(slots, "supports"), gun, "allowAttachmentType"), "unsupported attachment types are filtered by the actual TaCZ API");
        check(slots.methods.stream().anyMatch(m -> m.name.startsWith("lambda$supportedTypes$") && calls(m, gun, "allowAttachmentType")),
                "visible type enumeration uses TaCZ support rather than a hardcoded gun table");
        check(calls(method(slots, "accepts"), CLIENT + "PubgAttachmentSlots", "isLocked")
                        && calls(method(slots, "accepts"), CLIENT + "PubgAttachmentSlots", "isBuiltIn")
                        && calls(method(slots, "accepts"), gun, "allowAttachment"),
                "drag targets check lock, fixed parts and native item compatibility");
        check(calls(method(slots, "canRemove"), CLIENT + "PubgAttachmentSlots", "isBuiltIn")
                        && calls(method(slots, "canRemove"), CLIENT + "PubgAttachmentSlots", "isLocked"),
                "fixed and locked parts cannot be offered as unload sources");
        check(calls(method(slots, "installed"), gun, "getAttachment")
                        && !calls(method(slots, "installed"), gun, "getBuiltinAttachment"),
                "installed stacks never substitute synthetic built-in items into inventory transactions");
        check(calls(method(slots, "displayed"), gun, "getBuiltinAttachment"), "fixed parts can be shown without becoming an unload source");
        check(calls(method(slots, "emptyIconU"), NATIVE, "getSlotTextureXOffset"), "empty-slot UV mapping delegates to native TaCZ");
        boolean frame = false, icons = false;
        for (var instruction : draw.instructions) if (instruction instanceof FieldInsnNode field && field.owner.equals(NATIVE)) {
            frame |= field.name.equals("SLOT_TEXTURE");
            icons |= field.name.equals("ICONS_TEXTURE");
        }
        check(frame && icons && calls(draw, "net/minecraft/client/gui/GuiGraphics", "renderItem"),
                "slot drawing uses the original TaCZ frame/type atlas and native item rendering");
        check(!calls(draw, "net/minecraft/client/gui/GuiGraphics", "drawString"),
                "native attachment artwork is retained underneath the target highlight, without text placeholders");
        check(!calls(draw, "net/neoforged/neoforge/network/PacketDistributor", "sendToServer"),
                "native slot presentation cannot trigger native automatic attachment mutations");
        imageSize("assets/tacz/textures/gui/refit_slot.png", 18, 18);
        imageSize("assets/tacz/textures/gui/refit_slot_icons.png", 224, 32);
    }

    private static void imageSize(String resource, int width, int height) throws Exception {
        try (var stream = PubgRefitChecks.class.getClassLoader().getResourceAsStream(resource)) {
            check(stream != null, "native TaCZ texture is available: " + resource);
            var image = javax.imageio.ImageIO.read(stream);
            check(image != null && image.getWidth() == width && image.getHeight() == height,
                    "native texture dimensions match the actual refit frame/atlas crop: " + resource);
        }
    }

    private static void wiring() throws Exception {
        var wrapper = read(CLIENT + "PubgGunRefitScreen");
        check(wrapper.superName.equals(NATIVE), "wrapper remains a real TaCZ GunRefitScreen");
        for (String method : new String[]{"init", "render", "mouseClicked", "mouseReleased", "mouseDragged", "mouseScrolled"}) {
            var callback = method(wrapper, method);
            check(calls(callback, CLIENT + "PubgGunRefitScreen", "checkTarget"), "guard precedes native " + method);
            check(calls(callback, NATIVE, method), "native " + method + " retains original attachment widgets and interaction");
        }
        var finish = method(wrapper, "finish");
        check(calls(finish, CLIENT + "PubgRefitSession", "close"), "wrapper uses exercised once-only exit decision");
        check(calls(finish, NATIVE, "onClose"), "valid close delegates laser upload to actual TaCZ");
        check(calls(method(wrapper, "targetValid"), CLIENT + "PubgRefitSession", "matches"), "wrapper uses exercised target guard");

        var input = read(CLIENT + "PubgInventoryInput");
        var open = input.methods.stream().filter(m -> m.name.equals("openRefit") && m.desc.equals("(I)Z")).findFirst().orElseThrow();
        int select = -1, packet = -1, sent = -1, wrapperCreated = -1, index = 0;
        for (var instruction : open.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals("net/minecraft/world/entity/player/Inventory") && field.name.equals("selected")) select = index;
            if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/network/protocol/game/ServerboundSetCarriedItemPacket")
                    && call.name.equals("<init>")) packet = index;
            if (instruction instanceof MethodInsnNode call && call.name.equals("send")
                    && call.desc.equals("(Lnet/minecraft/network/protocol/Packet;)V")) sent = index;
            if (instruction instanceof MethodInsnNode call && call.owner.equals(CLIENT + "PubgGunRefitScreen")
                    && call.name.equals("<init>")) wrapperCreated = index;
            index++;
        }
        check(select >= 0 && packet > select && sent > packet && wrapperCreated > sent,
                "exact selected slot is synchronized before native screen construction");
        var vanilla = method(input, "vanillaInventory");
        boolean survival = false, creative = false;
        for (var instruction : vanilla.instructions) if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF) {
            survival |= type.desc.equals("net/minecraft/client/gui/screens/inventory/InventoryScreen");
            creative |= type.desc.equals("net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen");
        }
        check(survival && creative, "both vanilla inventory variants share routing and return button eligibility");
    }

    private static void invalidSlot(int slot) {
        try { new PubgRefitSession(slot); throw new AssertionError("Invalid refit slot accepted: " + slot); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static ClassNode read(String name) throws Exception {
        try (InputStream stream = PubgRefitChecks.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (stream == null) throw new AssertionError("Missing compiled class " + name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }
    private static MethodNode method(ClassNode node, String name) {
        var matches = node.methods.stream().filter(m -> m.name.equals(name)).toList();
        if (matches.size() != 1) throw new AssertionError("Expected unique method " + node.name + "." + name);
        return matches.getFirst();
    }
    private static boolean calls(MethodNode method, String owner, String name) {
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
        return false;
    }
    private static void check(boolean pass, String scenario) {
        checks++;
        if (!pass) throw new AssertionError(scenario);
    }
}
