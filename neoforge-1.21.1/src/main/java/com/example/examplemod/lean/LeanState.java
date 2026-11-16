package com.example.examplemod.lean;

import java.util.ArrayDeque;
import net.minecraft.world.phys.Vec3;

/** Per-player instance state, never shared between the integrated server and local prediction. */
public final class LeanState {
    final LeanMotion motion = new LeanMotion();
    final ArrayDeque<InputFrame> inputs = new ArrayDeque<>();
    int sequence = -1;
    boolean predicted;
    long renderNanos;
    double renderFraction = Double.NaN;
    LeanGeometry.Pose renderPose;
    Vec3 renderFeet;
    long inputNanos;
    int wireSequence = Integer.MIN_VALUE, wireTarget;
    float wireFraction = Float.NaN;
    record InputFrame(int sequence, long nanos, double fraction, int target) { }
}
