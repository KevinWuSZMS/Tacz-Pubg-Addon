package com.example.examplemod.movement;

import com.example.examplemod.movement.MovementPayloads.Input;
import com.example.examplemod.movement.MovementState.Mode;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Real packet codec and authoritative ownership transitions, without starting a Minecraft world. */
public final class MovementWireChecks {
    private static int checks;
    private MovementWireChecks() { }

    public static void main(String[] args) {
        packets();
        ownership();
        System.out.println("Movement wire/ownership checks passed: " + checks);
    }

    private static void packets() {
        for (Mode mode : Mode.values()) {
            for (boolean active : new boolean[]{false, true}) {
                var packet = new Input(mode, active);
                var buffer = buffer();
                try {
                    Input.CODEC.encode(buffer, packet);
                    check(buffer.readableBytes() == 1, "stance change occupies one bounded byte");
                    check(Input.CODEC.decode(buffer).equals(packet), "every action and release survives the actual codec");
                    check(!buffer.isReadable(), "decoder consumes the complete frame");
                } finally { buffer.release(); }
                if (!active) check(packet.mode() == Mode.STAND, "releasing ownership drops any previous stance");
            }
        }
        for (int code = 6; code <= 255; code++) {
            var buffer = buffer();
            try {
                buffer.writeByte(code);
                rejects(() -> Input.CODEC.decode(buffer), "unknown unsigned mode is rejected before reaching the server");
            } finally { buffer.release(); }
        }
        var truncated = buffer();
        try { rejects(() -> Input.CODEC.decode(truncated), "empty movement frame is rejected"); }
        finally { truncated.release(); }
        rejects(() -> new Input(null, true), "active input needs a defined action");
    }

    private static void ownership() {
        for (Mode mode : Mode.values()) {
            byte active = MovementModes.authorize(mode, true, true);
            check(active != 0 && MovementModes.decode(active) == mode, "eligible actions retain exact authoritative stance");
            check(MovementModes.authorize(mode, false, true) == 0, "profile/context release clears ownership");
            check(MovementModes.authorize(mode, true, false) == 0, "flight/death/priority rejection clears ownership");
            check(MovementModes.stopsSprint(active, (byte) 0), "every lifecycle clear stops owned sprint state");
            for (Mode next : Mode.values()) {
                byte value = MovementModes.authorize(next, true, true);
                check(MovementModes.stopsSprint(active, value) == (next != Mode.SPRINT),
                        "stance changes stop sprint while the sprint request preserves native sprint validation");
            }
        }
        check(!MovementModes.stopsSprint((byte) 0, (byte) 0), "inactive addon leaves ordinary native sprint untouched");
        check(MovementModes.encode(Mode.STAND, true) != MovementModes.encode(Mode.STAND, false),
                "explicit stand differs from yielding to native crouch/crawl controls");
        rejects(() -> MovementModes.decode(-1), "negative metadata code is rejected");
        rejects(() -> MovementModes.decode(256), "oversized metadata code is rejected");
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
        checks++;
    }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); }
        catch (RuntimeException expected) { checks++; return; }
        throw new AssertionError("Expected rejection: " + reason);
    }
}
