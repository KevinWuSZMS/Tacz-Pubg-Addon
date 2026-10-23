package com.example.examplemod.spawn;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;

/** Derived building groups; never discovers or approves new floors. */
public final class SpawnAreaGroups {
    public static final int VERTICAL_LINK_DISTANCE = 10;

    private SpawnAreaGroups() {}

    public record GroupIndex(Map<Long, Long> groupByFloor, int groupCount) {}

    public static GroupIndex compute(Collection<BlockPos> floors) {
        Set<Long> remaining = new HashSet<>();
        for (BlockPos pos : floors) remaining.add(pos.asLong());
        Map<Long, Long> groups = new HashMap<>();
        int count = 0;
        while (!remaining.isEmpty()) {
            long start = remaining.iterator().next();
            remaining.remove(start);
            ArrayDeque<Long> queue = new ArrayDeque<>();
            Set<Long> members = new HashSet<>();
            queue.add(start);
            long groupId = start;
            while (!queue.isEmpty()) {
                long packed = queue.removeFirst();
                members.add(packed);
                groupId = Math.min(groupId, packed);
                BlockPos pos = BlockPos.of(packed);
                enqueue(remaining, queue, pos.north());
                enqueue(remaining, queue, pos.south());
                enqueue(remaining, queue, pos.east());
                enqueue(remaining, queue, pos.west());
                for (int dy = -VERTICAL_LINK_DISTANCE; dy <= VERTICAL_LINK_DISTANCE; dy++) {
                    if (dy != 0) enqueue(remaining, queue, pos.offset(0, dy, 0));
                }
            }
            for (long member : members) groups.put(member, groupId);
            count++;
        }
        return new GroupIndex(Map.copyOf(groups), count);
    }

    private static void enqueue(Set<Long> remaining, ArrayDeque<Long> queue, BlockPos candidate) {
        long packed = candidate.asLong();
        if (remaining.remove(packed)) queue.addLast(packed);
    }
}
