package com.example.examplemod.zones.client.xaero;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

/** Regression for the actual 26.6.0 compatibility wrapper; does not load a game or Xaero classes. */
public final class XaeroMinimapReflectionChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        NativeState direct = new NativeState();
        Wrapper indirect = new Wrapper();
        DeeperWrapper deeper = new DeeperWrapper();
        try {
            Wrapper.class.getDeclaredField("ps");
            throw new AssertionError("old lookup unexpectedly worked");
        } catch (NoSuchFieldException expected) { checks++; }
        for (NativeState handler : new NativeState[]{direct, indirect, deeper}) {
            var bound = new XaeroMinimapStateFields(handler.getClass());
            frame(bound.read(handler), 1, 0, 2.5, 80, 64, true);
            handler.setFrame(-.6, .8, .125, 120, 90, false);
            frame(bound.read(handler), -.6, .8, .125, 120, 90, false);
            handler.setFrame(0, 1, 8, 24, 24, true);
            frame(bound.read(handler), 0, 1, 8, 24, 24, true);
            Field ps = XaeroMinimapStateFields.inheritedField(handler.getClass(), "ps", double.class);
            check(ps.getDeclaringClass() == NativeState.class, "private state bound to declaring superclass");
        }
        fails(() -> new XaeroMinimapStateFields(NoState.class), "missing state fails clearly");
        fails(() -> new XaeroMinimapStateFields(WrongType.class), "shadowing incompatible field type rejected");
        fails(() -> new XaeroMinimapStateFields(StaticState.class), "static field cannot masquerade as native instance state");

        Path jar = args.length > 0 ? Path.of(args[0]) : installedJar();
        String wrapperName = "xaero/common/minimap/element/render/over/MinimapElementOverMapRendererHandler";
        String nativeName = "xaero/hud/minimap/element/render/over/MinimapElementOverMapRendererHandler";
        Map<String, String> contract = Map.of("ps", "D", "pc", "D", "zoom", "D", "halfViewW", "I", "halfViewH", "I", "circle", "Z");
        try (JarFile file = new JarFile(jar.toFile())) {
            var wrapper = metadata(file, wrapperName);
            check(wrapper.superName.equals(nativeName), "installed public compatibility wrapper extends native state owner");
            check(!wrapper.fields.containsKey("ps"), "installed runtime class reproduces original NoSuchFieldException");
            for (var field : contract.entrySet()) {
                String owner = wrapperName;
                ClassInfo info = wrapper;
                while (!info.fields.containsKey(field.getKey()) && info.superName != null) {
                    owner = info.superName;
                    info = metadata(file, owner);
                }
                check(info.fields.containsKey(field.getKey()), "installed inherited field exists: " + field.getKey());
                FieldInfo actual = info.fields.get(field.getKey());
                check(actual.descriptor.equals(field.getValue()), "installed field type matches adapter: " + field.getKey());
                check((actual.flags & 8) == 0 && (actual.flags & 2) != 0, "installed state is private instance data: " + field.getKey());
                check(owner.equals(nativeName), "installed six fields share native declaring class: " + field.getKey());
            }
        }
        System.out.println("Xaero Minimap reflection checks: " + checks + " passed (" + jar.getFileName() + ")");
    }

    private static Path installedJar() throws IOException {
        String jar = "xaerominimap-neoforge-1.21.1-26.6.0.jar";
        Path candidate = Path.of("libs", jar);
        if (Files.isRegularFile(candidate)) return candidate;
        throw new IOException("Installed Xaero 26.6.0 jar is required for its offline API contract check");
    }

    /** Minimal read-only classfile metadata: avoids Class.forName, static initializers and Minecraft linkage. */
    private static ClassInfo metadata(JarFile jar, String path) throws IOException {
        var entry = jar.getJarEntry(path + ".class");
        if (entry == null) throw new IOException("Missing installed class: " + path);
        try (var input = new DataInputStream(jar.getInputStream(entry))) {
            if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid classfile: " + path);
            input.readUnsignedShort(); input.readUnsignedShort();
            Object[] pool = new Object[input.readUnsignedShort()];
            for (int i = 1; i < pool.length; i++) {
                switch (input.readUnsignedByte()) {
                    case 1 -> pool[i] = input.readUTF();
                    case 7 -> pool[i] = input.readUnsignedShort();
                    case 3, 4 -> input.skipNBytes(4);
                    case 5, 6 -> { input.skipNBytes(8); i++; }
                    case 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IOException("Unexpected constant pool type in " + path);
                }
            }
            input.readUnsignedShort();
            int thisClass = input.readUnsignedShort(), parentClass = input.readUnsignedShort();
            String name = (String) pool[(Integer) pool[thisClass]];
            String parent = parentClass == 0 ? null : (String) pool[(Integer) pool[parentClass]];
            int interfaces = input.readUnsignedShort(); input.skipNBytes(interfaces * 2L);
            Map<String, FieldInfo> fields = new HashMap<>();
            int count = input.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                int flags = input.readUnsignedShort();
                String field = (String) pool[input.readUnsignedShort()], descriptor = (String) pool[input.readUnsignedShort()];
                fields.put(field, new FieldInfo(descriptor, flags));
                int attributes = input.readUnsignedShort();
                for (int j = 0; j < attributes; j++) { input.readUnsignedShort(); input.skipNBytes(Integer.toUnsignedLong(input.readInt())); }
            }
            return new ClassInfo(name, parent, fields);
        }
    }
    private record FieldInfo(String descriptor, int flags) {}
    private record ClassInfo(String name, String superName, Map<String, FieldInfo> fields) {}

    private static class NativeState {
        private double ps = 1, pc = 0, zoom = 2.5;
        private int halfViewW = 80, halfViewH = 64;
        private boolean circle = true;
        void setFrame(double ps, double pc, double zoom, int width, int height, boolean circle) {
            this.ps = ps; this.pc = pc; this.zoom = zoom;
            halfViewW = width; halfViewH = height; this.circle = circle;
        }
    }
    private static class Wrapper extends NativeState {}
    private static final class DeeperWrapper extends Wrapper {}
    private static final class NoState {}
    private static final class WrongType extends NativeState { private float ps; }
    private static final class StaticState extends NativeState { private static double ps; }
    private static void frame(XaeroMinimapStateFields.Frame actual, double ps, double pc, double zoom, int width, int height, boolean circle) {
        check(actual.ps() == ps && actual.pc() == pc && actual.zoom() == zoom && actual.halfWidth() == width
                && actual.halfHeight() == height && actual.circle() == circle, "all native frame values read from current inherited state");
    }
    private interface Throwing { void run() throws ReflectiveOperationException; }
    private static void fails(Throwing action, String message) throws Exception {
        try { action.run(); throw new AssertionError(message); }
        catch (NoSuchFieldException expected) { checks++; }
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
