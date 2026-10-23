package com.example.examplemod.visibility;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Reads real classfile metadata without initializing the game, TaCZ, Sodium or a mixin runtime. */
public final class VisibilityAbiChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        ClassInfo tracker = nativeClass("net/minecraft/server/level/ChunkMap$TrackedEntity");
        field(tracker, "entity", "Lnet/minecraft/world/entity/Entity;");
        field(tracker, "serverEntity", "Lnet/minecraft/server/level/ServerEntity;");
        field(tracker, "seenBy", "Ljava/util/Set;");
        method(tracker, "updatePlayer", "(Lnet/minecraft/server/level/ServerPlayer;)V");
        ClassInfo chunks = nativeClass("net/minecraft/server/level/ChunkMap");
        field(chunks, "entityMap", "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;");
        field(chunks, "level", "Lnet/minecraft/server/level/ServerLevel;");
        method(chunks, "tick", "()V");
        ClassInfo pairing = nativeClass("net/minecraft/server/level/ServerEntity");
        method(pairing, "addPairing", "(Lnet/minecraft/server/level/ServerPlayer;)V");
        method(pairing, "removePairing", "(Lnet/minecraft/server/level/ServerPlayer;)V");
        method(nativeClass("net/minecraft/client/player/RemotePlayer"), "shouldRenderAtSqrDistance", "(D)Z");
        method(nativeClass("net/minecraft/world/entity/Entity"), "shouldRenderAtSqrDistance", "(D)Z");
        method(nativeClass("net/minecraft/world/entity/Entity"), "isAlwaysTicking", "()Z");
        method(nativeClass("net/minecraft/world/entity/player/Player"), "isAlwaysTicking", "()Z");
        method(nativeClass("net/minecraft/client/renderer/GameRenderer"), "getDepthFar", "()F");
        method(nativeClass("net/minecraft/client/renderer/LevelRenderer"), "isSectionCompiled", "(Lnet/minecraft/core/BlockPos;)Z");
        method(nativeClass("net/minecraft/client/renderer/LevelRenderer"), "renderLevel",
                "(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V");
        method(nativeClass("net/minecraft/client/multiplayer/ClientLevel"), "entitiesForRendering", "()Ljava/lang/Iterable;");
        method(nativeClass("net/minecraft/client/multiplayer/ClientLevel"), "tickNonPassenger", "(Lnet/minecraft/world/entity/Entity;)V");
        method(nativeClass("net/minecraft/client/multiplayer/ClientLevel"), "tickEntities", "()V");
        field(nativeClass("net/minecraft/client/multiplayer/ClientLevel"), "tickingEntities", "Lnet/minecraft/world/level/entity/EntityTickList;");
        method(nativeClass("net/minecraft/client/multiplayer/ClientLevel"), "tickPassenger", "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)V");
        method(nativeClass("net/minecraft/world/level/entity/EntityTickList"), "contains", "(Lnet/minecraft/world/entity/Entity;)Z");
        method(nativeClass("net/minecraft/world/entity/Entity"), "getIndirectPassengers", "()Ljava/lang/Iterable;");
        method(nativeClass("net/minecraft/world/entity/Entity"), "isVehicle", "()Z");
        method(nativeClass("net/minecraft/world/entity/Entity"), "shouldRender", "(DDD)Z");
        method(nativeClass("net/minecraft/client/renderer/entity/EntityRenderer"), "shouldRender",
                "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z");
        method(nativeClass("net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher"), "setupAndRender",
                "(Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V");
        try (var jar = new JarFile(mod("tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar").toFile())) {
            method(metadata(jar.getInputStream(jar.getJarEntry("com/tacz/guns/entity/TargetMinecart.class"))), "shouldRenderAtSqrDistance", "(D)Z");
            method(metadata(jar.getInputStream(jar.getJarEntry("com/tacz/guns/client/renderer/block/TargetRenderer.class"))), "getViewDistance", "()I");
        }
        try (var jar = new JarFile(mod("sodium-neoforge-0.6.13+mc1.21.1.jar").toFile())) {
            method(metadata(jar.getInputStream(jar.getJarEntry("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class"))),
                    "isEntityVisible", "(Lnet/minecraft/world/entity/Entity;)Z");
        }
        System.out.println("Visibility ABI checks: " + checks + " passed (real MC 1.21.1 / TaCZ r7 / Sodium 0.6.13 classfiles)");
    }
    private static Path mod(String suffix) throws IOException {
        Path dir = Files.isDirectory(Path.of("run", "mods")) ? Path.of("run", "mods") : Path.of("neoforge-1.21.1", "run", "mods");
        try (var paths = Files.list(dir)) { return paths.filter(path -> path.getFileName().toString().endsWith(suffix)).findFirst().orElseThrow(); }
    }
    private static ClassInfo nativeClass(String name) throws IOException {
        InputStream stream = VisibilityAbiChecks.class.getClassLoader().getResourceAsStream(name + ".class");
        if (stream == null) throw new IOException("Missing native class " + name);
        return metadata(stream);
    }
    private record ClassInfo(Map<String, String> fields, Set<String> methods) {}
    private static void field(ClassInfo type, String name, String descriptor) {
        check(descriptor.equals(type.fields.get(name)), "native shadow field " + name + " matches " + descriptor);
    }
    private static void method(ClassInfo type, String name, String descriptor) {
        check(type.methods.contains(name + descriptor), "native target method " + name + descriptor);
    }
    private static void check(boolean yes, String message) { checks++; if (!yes) throw new AssertionError(message); }
    private static ClassInfo metadata(InputStream stream) throws IOException {
        try (var input = new DataInputStream(stream)) {
            if (input.readInt() != 0xcafebabe) throw new IOException("Invalid classfile");
            input.readUnsignedShort(); input.readUnsignedShort();
            Object[] pool = new Object[input.readUnsignedShort()];
            for (int i = 1; i < pool.length; i++) switch (input.readUnsignedByte()) {
                case 1 -> pool[i] = input.readUTF();
                case 3, 4 -> input.skipNBytes(4);
                case 5, 6 -> { input.skipNBytes(8); i++; }
                case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                case 15 -> input.skipNBytes(3);
                default -> throw new IOException("Unknown class pool entry");
            }
            input.skipNBytes(6); input.skipNBytes(input.readUnsignedShort() * 2L);
            var fields = new HashMap<String, String>(); var methods = new HashSet<String>();
            for (int section = 0; section < 2; section++) {
                int count = input.readUnsignedShort();
                for (int i = 0; i < count; i++) {
                    input.readUnsignedShort(); String name = (String) pool[input.readUnsignedShort()]; String descriptor = (String) pool[input.readUnsignedShort()];
                    if (section == 0) fields.put(name, descriptor); else methods.add(name + descriptor);
                    int attributes = input.readUnsignedShort();
                    for (int a = 0; a < attributes; a++) { input.readUnsignedShort(); input.skipNBytes(Integer.toUnsignedLong(input.readInt())); }
                }
            }
            return new ClassInfo(fields, methods);
        }
    }
}
