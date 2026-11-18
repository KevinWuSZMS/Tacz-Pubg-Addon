package com.example.examplemod.inventory.client;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Offline preview bounds, depth regression, and restoration structure; no Minecraft initialization. */
public final class PubgInventoryCharacterChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        double oldPitch = Math.atan(140 / 40.0) * Math.toRadians(20);
        check(50 - 150 * 1.9 * Math.sin(oldPitch) < 0,
                "old enlarged native preview puts the head behind the Z=0 inventory background");
        for (int[] size : new int[][] {{640,400}, {960,600}, {1920,1080}, {3840,2160}}) {
            var layout = PubgInventoryLayout.of(size[0], size[1]);
            int left = layout.equipmentX() + layout.equipmentSize() + 7;
            int right = layout.weaponX() - layout.margin() * 4;
            int top = layout.listTop() - 8, bottom = size[1] - 42;
            for (double mouseX : new double[] {-10000, 0, left, (left + right) / 2.0, right, size[0], 10000})
                for (double mouseY : new double[] {-10000, 0, top, (top + bottom) / 2.0, bottom, size[1], 10000}) {
                    var pose = PubgInventoryCharacterPose.of(left, top, right, bottom, mouseX, mouseY, 1.8f);
                    check(Math.abs(pose.angleX()) <= .5 && Math.abs(pose.angleY()) <= .35f,
                            "whole-screen mouse positions retain a gentle yaw and pitch");
                    for (double viewScale : new double[] {.4, .75, 1})
                        for (double x : new double[] {-.55, .55})
                            for (double y : new double[] {0, 1.9})
                                for (double z : new double[] {-.35, .35}) {
                                    // Conservative skin/armor envelope, including the head and hat.
                                    double yaw = Math.toRadians(pose.angleX() * 20), pitch = Math.toRadians(pose.angleY() * 20);
                                    double rx = x * Math.cos(yaw) + z * Math.sin(yaw);
                                    double rz = -x * Math.sin(yaw) + z * Math.cos(yaw);
                                    double ry = y * Math.cos(pitch) - rz * Math.sin(pitch);
                                    rz = y * Math.sin(pitch) + rz * Math.cos(pitch);
                                    double guiX = ((left + right) / 2.0 + pose.scale() * rx) * viewScale;
                                    double guiY = ((top + bottom) / 2.0 + pose.scale() * (.9 - ry)) * viewScale;
                                    double depth = PubgInventoryCharacterPose.DEPTH_OFFSET + 50 - pose.scale() * viewScale * rz;
                                    check(guiX >= left * viewScale && guiX <= right * viewScale
                                                    && guiY >= top * viewScale && guiY <= bottom * viewScale,
                                            "skin and armor envelope remains inside the actual GUI scissor");
                                    check(depth > 0 && depth < 1000, "character stays in front of background and behind action HUD");
                                }
                }
        }
        var neutral = PubgInventoryCharacterPose.of(0, 0, 200, 300, 100, 150, 1.8f);
        check(neutral.angleX() == 0 && neutral.angleY() == 0, "centered mouse restores neutral pose");
        var badMouse = PubgInventoryCharacterPose.of(0, 0, 200, 300, Double.NaN, Double.POSITIVE_INFINITY, 1.8f);
        check(badMouse.angleX() == 0 && badMouse.angleY() == 0, "nonfinite mouse input cannot corrupt player angles");
        restorationAbi();
        System.out.println("PubgInventoryCharacterChecks: " + checks + " offline bounds/depth/restoration checks passed; no game launched.");
    }

    private static void restorationAbi() throws Exception {
        String renderer = "com/example/examplemod/inventory/client/PubgInventoryCharacterRenderer";
        var state = read(renderer + "$PlayerAngles");
        var restore = state.methods.stream().filter(m -> m.name.equals("restore")).findFirst().orElseThrow();
        Set<String> fields = new HashSet<>(), methods = new HashSet<>();
        for (var instruction : restore.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) fields.add(field.name);
            if (instruction instanceof MethodInsnNode method) methods.add(method.name);
        }
        check(fields.containsAll(Set.of("yBodyRot", "yBodyRotO", "yRotO", "xRotO", "yHeadRot", "yHeadRotO"))
                        && methods.containsAll(Set.of("setXRot", "setYRot")), "restore writes all eight current and previous player angles");
        var render = read(renderer).methods.stream().filter(m -> m.name.equals("render")).findFirst().orElseThrow();
        check(render.tryCatchBlocks.stream().anyMatch(block -> block.type == null), "preview restores state through a finally handler");
        var dispatcher = read("net/minecraft/client/renderer/entity/EntityRenderDispatcher");
        check(dispatcher.fields.stream().anyMatch(f -> f.name.equals("shouldRenderShadow") && f.desc.equals("Z")),
                "shadow accessor matches the mapped Minecraft field");
    }

    private static ClassNode read(String name) throws Exception {
        try (InputStream in = PubgInventoryCharacterChecks.class.getClassLoader().getResourceAsStream(name + ".class")) {
            check(in != null, "compiled class resource " + name);
            var node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
    private static void check(boolean pass, String reason) {
        checks++;
        if (!pass) throw new AssertionError(reason);
    }
}
