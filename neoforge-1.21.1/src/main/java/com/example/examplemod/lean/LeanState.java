package com.example.examplemod.lean;

import java.util.ArrayDeque;

/** Per-player instance state, never shared between the integrated server and local prediction. */
public final class LeanState {
    final LeanMotion motion = new LeanMotion();
    final ArrayDeque<InputFrame> inputs = new ArrayDeque<>();
    int sequence = -1;
    boolean predicted;
    long renderNanos;
    double renderFraction = Double.NaN;
    LeanGeometry.Pose renderPose;
    long inputNanos;
    int wireSequence = Integer.MIN_VALUE, wireTarget;
    float wireFraction = Float.NaN;
    record InputFrame(int sequence, long nanos, double fraction, int target) { }
}
