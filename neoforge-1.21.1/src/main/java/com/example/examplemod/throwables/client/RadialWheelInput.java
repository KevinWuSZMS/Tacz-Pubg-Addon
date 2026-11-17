package com.example.examplemod.throwables.client;

import java.util.function.Predicate;

/** One physical hold opens at most one wheel, including holds cancelled before release. */
final class RadialWheelInput<T> {
    private T trigger;
    private boolean open;

    boolean tryOpen(T candidate) {
        if (trigger != null || candidate == null) return false;
        trigger = candidate;
        open = true;
        return true;
    }

    /** Returns true exactly once when an open wheel should confirm its selection. */
    boolean release(Predicate<T> held) {
        if (trigger == null || held.test(trigger)) return false;
        boolean confirm = open;
        clear();
        return confirm;
    }

    void cancel() {
        open = false;
    }

    /** A fresh PRESS proves a previous release even if its callback and polling gap were missed. */
    void pressed(T candidate) {
        if (!open && trigger == candidate) clear();
    }

    boolean blocked() {
        return trigger != null;
    }

    void clear() {
        trigger = null;
        open = false;
    }
}
