package com.example.examplemod.keybindings;

/** Vanilla sneak, sprint, and auto-jump behavior is global across both key profiles. */
public record BehaviorOptions(boolean toggleCrouch, boolean toggleSprint, boolean autoJump) {}
