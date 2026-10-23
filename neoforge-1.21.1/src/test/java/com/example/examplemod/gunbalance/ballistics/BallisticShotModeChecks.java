package com.example.examplemod.gunbalance.ballistics;

import java.nio.ByteBuffer;

public final class BallisticShotModeChecks {
    public static void main(String[] args) {
        int checks = 0;
        for (boolean initial : new boolean[] {false, true}) {
            BallisticShotMode latch = new BallisticShotMode();
            if (latch.isLocked()) throw new AssertionError("New projectile starts unlocked");
            if (latch.lock(initial) != initial || !latch.isLocked()) throw new AssertionError("Launch mode was not latched");
            if (latch.lock(!initial) != initial || latch.experimental() != initial)
                throw new AssertionError("An airborne projectile changed profile");
            ByteBuffer buffer = ByteBuffer.allocate(6);
            buffer.putInt(0x5441435A); // Native payload sentinel stands for bytes written by TaCZ first.
            buffer.put((byte) BallisticShotMode.SPAWN_SCHEMA).put((byte) (initial ? 1 : 0));
            buffer.flip();
            if (buffer.getInt() != 0x5441435A) throw new AssertionError("Native spawn payload was changed");
            boolean received = BallisticShotMode.decode(Byte.toUnsignedInt(buffer.get()), Byte.toUnsignedInt(buffer.get()));
            if (received != initial || buffer.hasRemaining()) throw new AssertionError("Spawn suffix not consumed exactly once");
            checks += 6;
        }
        for (int schema : new int[] {0, 2, 255}) {
            try { BallisticShotMode.decode(schema, 1); throw new AssertionError("Bad schema accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        for (int mode : new int[] {-1, 2, 255}) {
            try { BallisticShotMode.decode(1, mode); throw new AssertionError("Bad mode accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        System.out.println("Ballistic shot mode checks passed: " + checks);
    }
}
