package com.example.examplemod.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Presentation time advances only with the match; packet sequence never serves as a clock. */
public final class CombatTimeline<T> {
    public record Entry<T>(T value, double born) {}
    /** Distance in row heights from the newest message; the renderer chooses its direction. */
    public record PositionedEntry<T>(Entry<T> entry, double slot) {}
    private record MovingEntry<T>(Entry<T> entry, double insertedAt) {}
    private UUID session;
    private long sequence = -1;
    private double tick, presentationTime, personalAt = Double.NEGATIVE_INFINITY;
    private final List<MovingEntry<T>> feed = new ArrayList<>(), personal = new ArrayList<>();
    public void reset(UUID next) { session=next; sequence=-1; tick=0; presentationTime=0; personalAt=Double.NEGATIVE_INFINITY; feed.clear(); personal.clear(); }
    public boolean offer(UUID incoming,long order,T value,boolean own) {
        return offer(incoming,order,value,own,true);
    }
    public boolean offer(UUID incoming,long order,T value,boolean own,boolean publicRow) {
        if (!incoming.equals(session)||order<=sequence) return false;
        // Packets can arrive between rendered frames of the same game tick. Anchor their
        // push at the last displayed time rather than rewinding to the tick boundary.
        var entry=new MovingEntry<>(new Entry<>(value,tick),Math.max(tick,presentationTime));
        sequence=order; if(publicRow) {feed.add(0,entry); trim(feed,64);}
        if (own) { personal.add(0,entry); trim(personal,12); markPersonal(); }
        return true;
    }
    private static void trim(List<?> values,int count) { if(values.size()>count) values.subList(count,values.size()).clear(); }
    public void advance(boolean paused) { if (!paused) tick++; }
    public double time() { return tick; }
    public double personalAge() { return tick-personalAt; }
    public void markPersonal() { personalAt=tick; }
    public List<Entry<T>> feed(int count,double lifetime) { return visible(feed,count,lifetime); }
    public List<Entry<T>> personal(int count,double lifetime) { return visible(personal,count,lifetime); }
    private List<Entry<T>> visible(List<MovingEntry<T>> values,int count,double lifetime) {
        expire(values,lifetime);
        return values.subList(0,Math.min(count,values.size())).stream().map(MovingEntry::entry).toList();
    }
    public List<PositionedEntry<T>> positionedPersonal(double now,int count,double lifetime,double moveTicks) {
        return positioned(personal,now,count,lifetime,moveTicks);
    }
    public List<PositionedEntry<T>> positionedFeed(double now,int count,double lifetime,double moveTicks) {
        return positioned(feed,now,count,lifetime,moveTicks);
    }
    private List<PositionedEntry<T>> positioned(List<MovingEntry<T>> values,double now,int count,double lifetime,double moveTicks) {
        // Pausing resets the renderer's fractional tick to zero. Keep the last displayed
        // position until the advancing game clock catches up, without a backward jump.
        presentationTime=Math.max(presentationTime,now);
        expire(values,lifetime);
        var result=new ArrayList<PositionedEntry<T>>();
        double slot=0;
        for(int i=0;i<Math.min(count,values.size());i++) {
            var moving=values.get(i);
            result.add(new PositionedEntry<>(moving.entry,slot));
            // Every newer message contributes its own push. A rapid third insertion
            // never restarts the unfinished movement caused by the second insertion.
            slot+=smooth(presentationTime-moving.insertedAt,moveTicks);
        }
        return List.copyOf(result);
    }
    private void expire(List<MovingEntry<T>> values,double lifetime) {
        values.removeIf(e->tick-e.entry.born>=lifetime);
    }
    public static double alpha(double age,double duration,double fadeIn,double fadeOut) {
        if (age<0||age>=duration||duration<=0) return 0;
        double start=fadeIn<=0?1:Math.min(1,age/fadeIn);
        double end=fadeOut<=0?1:Math.min(1,(duration-age)/fadeOut);
        return Math.max(0,Math.min(start,end));
    }
    public static double smooth(double age,double duration) {
        double t=duration<=0?1:Math.clamp(age/duration,0,1); return t*t*(3-2*t);
    }
}
