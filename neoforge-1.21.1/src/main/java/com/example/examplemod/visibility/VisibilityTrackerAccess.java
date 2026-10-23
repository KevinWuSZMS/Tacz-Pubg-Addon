package com.example.examplemod.visibility;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/** Implemented by the native tracker; no parallel or duplicate entity tracker exists. */
public interface VisibilityTrackerAccess {
    void pubg$refreshVisibility(List<ServerPlayer> players);
}
