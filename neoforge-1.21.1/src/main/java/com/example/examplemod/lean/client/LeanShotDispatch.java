package com.example.examplemod.lean.client;

/** One client-thread decision for pose, native request and effects; a rejected burst cannot resume. */
public final class LeanShotDispatch {
    private final LeanShotSession.Ticket ticket;
    private int nextOrdinal;
    private boolean cancelled;

    public LeanShotDispatch(LeanShotSession.Ticket ticket) {
        this.ticket = ticket;
    }

    public boolean dispatch(LeanShotSession session, LeanShotSession.Identity live, int ordinal,
            Runnable pose, Runnable nativeSend, Runnable animation) {
        if (cancelled) return false;
        // Leave the group cancelled if validation or any native callback fails.
        cancelled = true;
        if (ordinal != nextOrdinal || !session.valid(ticket, live)
                || (ordinal == 0 && nativeSend == null)) return false;
        pose.run();
        if (ordinal == 0) nativeSend.run();
        animation.run();
        nextOrdinal++;
        cancelled = false;
        return true;
    }
}
