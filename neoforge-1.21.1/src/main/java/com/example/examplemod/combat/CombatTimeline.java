package com.example.examplemod.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Presentation time advances only with the match; packet sequence never serves as a clock. */
public final class CombatTimeline<T> {
    public record Entry<T>(T value, double born) {}
    private UUID session;
    private long sequence = -1;
    private double tick, personalAt = Double.NEGATIVE_INFINITY;
    private final List<Entry<T>> feed = new ArrayList<>(), personal = new ArrayList<>();
    public void reset(UUID next) { session=next; sequence=-1; tick=0; personalAt=Double.NEGATIVE_INFINITY; feed.clear(); personal.clear(); }
    public boolean offer(UUID incoming,long order,T value,boolean own) {
        return offer(incoming,order,value,own,true);
    }
    public boolean offer(UUID incoming,long order,T value,boolean own,boolean publicRow) {
        if (!incoming.equals(session)||order<=sequence) return false;
        sequence=order; if(publicRow) {feed.add(0,new Entry<>(value,tick)); trim(feed,64);}
        if (own) { personal.add(0,new Entry<>(value,tick)); trim(personal,12); markPersonal(); }
        return true;
    }
    private static void trim(List<?> values,int count) { if(values.size()>count) values.subList(count,values.size()).clear(); }
    public void advance(boolean paused) { if (!paused) tick++; }
    public double time() { return tick; }
    public double personalAge() { return tick-personalAt; }
    public void markPersonal() { personalAt=tick; }
    public List<Entry<T>> feed(int count,double lifetime) { return visible(feed,count,lifetime); }
    public List<Entry<T>> personal(int count,double lifetime) { return visible(personal,count,lifetime); }
    private List<Entry<T>> visible(List<Entry<T>> values,int count,double lifetime) {
        values.removeIf(e->tick-e.born>=lifetime);
        return List.copyOf(values.subList(0,Math.min(count,values.size())));
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
