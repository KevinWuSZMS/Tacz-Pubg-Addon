package com.example.examplemod.gunbalance.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Frame-rate-independent additive impulses; stores no player orientation or mouse-input baseline. */
public final class PersistentRecoilState {
    private static final int MAX_PENDING = 256;
    private final List<Shot> pending = new ArrayList<>();
    private double clock = Double.NEGATIVE_INFINITY;
    private double overflowPitch, overflowYaw;

    public record Delta(double pitch, double yaw) {}

    public void add(PersistentRecoilMath.Peak pitch, PersistentRecoilMath.Peak yaw, double timeMillis) {
        if (!Double.isFinite(timeMillis) || (pitch.value() == 0 && yaw.value() == 0)) return;
        if (pending.size() >= MAX_PENDING) {
            Shot oldest = pending.removeFirst();
            overflowPitch += oldest.pitch.value() - oldest.pitchApplied;
            overflowYaw += oldest.yaw.value() - oldest.yawApplied;
        }
        pending.add(new Shot(pitch, yaw, Math.max(timeMillis, clock)));
    }

    public Delta sample(double timeMillis) {
        if (!Double.isFinite(timeMillis)) return new Delta(0, 0);
        clock = Math.max(clock, timeMillis);
        double pitch = overflowPitch, yaw = overflowYaw;
        overflowPitch = overflowYaw = 0;
        for (Iterator<Shot> iterator = pending.iterator(); iterator.hasNext();) {
            Shot shot = iterator.next();
            double age = Math.max(0, clock - shot.started);
            double targetPitch = shot.pitch.displacement(age), targetYaw = shot.yaw.displacement(age);
            pitch += targetPitch - shot.pitchApplied;
            yaw += targetYaw - shot.yawApplied;
            shot.pitchApplied = targetPitch;
            shot.yawApplied = targetYaw;
            if (age >= Math.max(shot.pitch.timeMillis(), shot.yaw.timeMillis())) iterator.remove();
        }
        return new Delta(pitch, yaw);
    }

    public int pendingCount() { return pending.size(); }

    /** Discard future motion on switching modes/guns; already applied aim never snaps back. */
    public void clear() {
        pending.clear();
        clock = Double.NEGATIVE_INFINITY;
        overflowPitch = overflowYaw = 0;
    }

    private static final class Shot {
        private final PersistentRecoilMath.Peak pitch, yaw;
        private final double started;
        private double pitchApplied, yawApplied;
        private Shot(PersistentRecoilMath.Peak pitch, PersistentRecoilMath.Peak yaw, double started) {
            this.pitch = pitch;
            this.yaw = yaw;
            this.started = started;
        }
    }
}
