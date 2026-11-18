package com.example.examplemod.zones.match.bot;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

/** Checks the installed native ABI without initializing Minecraft, Carpet, TaCZ or mixins. */
public final class BotAbiChecks {
    private static int checks;
    private record Info(String parent, Map<String, Integer> methods, Map<String, Integer> fields) {}
    public static void main(String[] args) throws Exception {
        try (JarFile carpet = new JarFile(mod("neoforge-carpet-1.21.1-1.0.8+v251027.jar").toFile());
                JarFile tacz = new JarFile(mod("tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar").toFile())) {
            Info fake = from(carpet, "carpet/patches/EntityPlayerMPFake");
            check(fake.parent().equals("net/minecraft/server/level/ServerPlayer"), "actual Carpet fake body extends ServerPlayer");
            method(fake, "respawnFake", "(Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lcarpet/patches/EntityPlayerMPFake;", true);
            method(fake, "kill", "(Lnet/minecraft/network/chat/Component;)V", false);
            field(fake, "fixStartingPosition", "Ljava/lang/Runnable;", false);
            field(from(carpet, "carpet/patches/NetHandlerPlayServerFake"), "DUMMY_CONNECTION", "Lnet/minecraft/network/Connection;", true);
            method(from(carpet, "carpet/fakes/ServerPlayerInterface"), "getActionPack", "()Lcarpet/helpers/EntityPlayerActionPack;", false);
            Info actions = from(carpet, "carpet/helpers/EntityPlayerActionPack");
            method(actions, "stopAll", "()Lcarpet/helpers/EntityPlayerActionPack;", false);
            for (String name : new String[] {"setForward", "setStrafing"}) method(actions, name, "(F)Lcarpet/helpers/EntityPlayerActionPack;", false);
            method(actions, "setSprinting", "(Z)Lcarpet/helpers/EntityPlayerActionPack;", false);
            method(actions, "look", "(FF)Lcarpet/helpers/EntityPlayerActionPack;", false);
            method(actions, "lookAt", "(Lnet/minecraft/world/phys/Vec3;)Lcarpet/helpers/EntityPlayerActionPack;", false);
            Info operator = from(tacz, "com/tacz/guns/api/entity/IGunOperator");
            method(operator, "fromLivingEntity", "(Lnet/minecraft/world/entity/LivingEntity;)Lcom/tacz/guns/api/entity/IGunOperator;", true);
            method(operator, "draw", "(Ljava/util/function/Supplier;)V", false);
            method(operator, "shoot", "(Ljava/util/function/Supplier;Ljava/util/function/Supplier;)Lcom/tacz/guns/api/entity/ShootResult;", false);
            for (String name : new String[] {"reload", "cancelReload", "bolt"}) method(operator, name, "()V", false);
            method(operator, "aim", "(Z)V", false);
            method(operator, "getSynIsBolting", "()Z", false);
            method(operator, "getSynAimingProgress", "()F", false);
            method(operator, "getSynReloadState", "()Lcom/tacz/guns/api/entity/ReloadState;", false);
            method(operator, "getCacheProperty", "()Lcom/tacz/guns/resource/modifier/AttachmentCacheProperty;", false);
            Info gunBuilder = from(tacz, "com/tacz/guns/api/item/builder/GunItemBuilder");
            method(gunBuilder, "create", "()Lcom/tacz/guns/api/item/builder/GunItemBuilder;", true);
            method(gunBuilder, "build", "(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;", false);
            method(from(tacz, "com/tacz/guns/api/item/builder/AmmoItemBuilder"), "build", "()Lnet/minecraft/world/item/ItemStack;", false);
            method(from(tacz, "com/tacz/guns/api/item/IAmmo"), "isAmmoOfGun", "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z", false);
            Info api = from(tacz, "com/tacz/guns/api/TimelessAPI");
            method(api, "getCommonGunIndex", "(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;", true);
            method(api, "getCommonAmmoIndex", "(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;", true);
            method(api, "getAllCommonGunIndex", "()Ljava/util/Set;", true);
        }
        method(nativeClass("net/minecraft/server/players/PlayerList"), "placeNewPlayer", "(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V", false);
        method(nativeClass("net/minecraft/server/network/CommonListenerCookie"), "<init>", "(Lcom/mojang/authlib/GameProfile;ILnet/minecraft/server/level/ClientInformation;Z)V", false);
        method(nativeClass("net/minecraft/server/level/ClientInformation"), "createDefault", "()Lnet/minecraft/server/level/ClientInformation;", true);
        method(nativeClass("net/minecraft/core/UUIDUtil"), "createOfflinePlayerUUID", "(Ljava/lang/String;)Ljava/util/UUID;", true);
        method(nativeClass("net/minecraft/world/TickRateManager"), "runsNormally", "()Z", false);
        method(nativeClass("net/minecraft/world/entity/LivingEntity"), "jumpFromGround", "()V", false);
        System.out.println("BotAbiChecks: " + checks + " installed Carpet / TaCZ r7 / MC 1.21.1 signatures passed");
    }
    private static Path mod(String name) {
        return Path.of("libs", name);
    }
    private static Info from(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name + ".class"); if (entry == null) throw new IOException("Missing actual mod class " + name);
        return metadata(jar.getInputStream(entry));
    }
    private static Info nativeClass(String name) throws IOException {
        InputStream stream = BotAbiChecks.class.getClassLoader().getResourceAsStream(name + ".class");
        if (stream == null) throw new IOException("Missing mapped MC class " + name); return metadata(stream);
    }
    private static void method(Info type, String name, String descriptor, boolean isStatic) { access(type.methods().get(name + descriptor), isStatic, "method " + name + descriptor); }
    private static void field(Info type, String name, String descriptor, boolean isStatic) { access(type.fields().get(name + descriptor), isStatic, "field " + name + descriptor); }
    private static void access(Integer flags, boolean isStatic, String message) {
        check(flags != null, "actual " + message + " exists");
        check((flags & 1) != 0 && ((flags & 8) != 0) == isStatic, "actual " + message + " has public " + (isStatic ? "static" : "instance") + " access");
    }
    private static void check(boolean yes, String message) { checks++; if (!yes) throw new AssertionError(message); }
    private static Info metadata(InputStream stream) throws IOException {
        try (var input = new DataInputStream(stream)) {
            if (input.readInt() != 0xcafebabe) throw new IOException("Invalid classfile");
            input.readUnsignedShort(); input.readUnsignedShort(); Object[] pool = new Object[input.readUnsignedShort()];
            for (int i = 1; i < pool.length; i++) switch (input.readUnsignedByte()) {
                case 1 -> pool[i] = input.readUTF();
                case 7 -> pool[i] = input.readUnsignedShort();
                case 3, 4 -> input.skipNBytes(4);
                case 5, 6 -> { input.skipNBytes(8); i++; }
                case 8, 16, 19, 20 -> input.skipNBytes(2);
                case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                case 15 -> input.skipNBytes(3);
                default -> throw new IOException("Unknown class pool entry");
            }
            input.skipNBytes(4); int parent = input.readUnsignedShort();
            String parentName = parent == 0 ? "" : (String) pool[(Integer) pool[parent]];
            input.skipNBytes(input.readUnsignedShort() * 2L);
            var fields = new HashMap<String, Integer>(); var methods = new HashMap<String, Integer>();
            for (int section = 0; section < 2; section++) {
                int count = input.readUnsignedShort();
                for (int i = 0; i < count; i++) {
                    int access = input.readUnsignedShort(); String name = (String) pool[input.readUnsignedShort()], descriptor = (String) pool[input.readUnsignedShort()];
                    (section == 0 ? fields : methods).put(name + descriptor, access);
                    int attributes = input.readUnsignedShort();
                    for (int a = 0; a < attributes; a++) { input.readUnsignedShort(); input.skipNBytes(Integer.toUnsignedLong(input.readInt())); }
                }
            }
            return new Info(parentName, methods, fields);
        }
    }
}
