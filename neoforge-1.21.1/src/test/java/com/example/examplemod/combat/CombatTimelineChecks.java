package com.example.examplemod.combat;

import java.util.UUID;

public final class CombatTimelineChecks {
    private static int checks;
    public static void main(String[] args) {
        var timeline=new CombatTimeline<String>(); UUID first=new UUID(1,2),second=new UUID(3,4);
        timeline.reset(first);
        check(!timeline.offer(second,1,"old connection",true),"Wrong session rejected");
        for(int i=1;i<=4;i++){timeline.offer(first,i,"event"+i,true);timeline.advance(false);}
        var messages=timeline.personal(3,120);check(messages.size()==3,"Exactly three center messages");
        check(messages.getFirst().value().equals("event4")&&messages.getLast().value().equals("event2"),"Newest at bottom, older above in render order");
        check(!timeline.offer(first,4,"duplicate",true),"Duplicate sequence rejected");
        check(!timeline.offer(first,3,"late",true),"Reordered sequence rejected");
        double before=timeline.time();for(int i=0;i<300;i++)timeline.advance(true);
        check(timeline.time()==before&&timeline.personal(3,120).size()==3,"Pause freezes both age and expiry");
        timeline.offer(first,5,"private assist",true,false);
        check(timeline.feed(64,120).size()==4&&timeline.personal(3,120).getFirst().value().equals("private assist"),"Private assist does not duplicate public elimination");
        for(int i=0;i<200;i++)timeline.advance(false);
        check(timeline.personal(3,120).isEmpty()&&timeline.feed(64,120).isEmpty(),"Expired rows removed");
        check(timeline.personalAge()==200,"Personal message age follows personal activity");
        timeline.reset(second);check(timeline.feed(64,120).isEmpty()&&Double.isInfinite(timeline.personalAge()),"New match resets count presentation");
        check(timeline.offer(second,1,"new round",false),"New session accepts restarted sequence");
        for(int i=2;i<1000;i++)timeline.offer(second,i,"bounded",false);
        check(timeline.feed(1000,120).size()==64,"Flood remains bounded");
        check(CombatTimeline.alpha(0,120,4,20)==0&&CombatTimeline.alpha(4,120,4,20)==1,"Fade begins at transparent and reaches full");
        check(CombatTimeline.alpha(110,120,4,20)==.5&&CombatTimeline.alpha(120,120,4,20)==0,"Fade-out reaches zero exactly at expiry");
        check(CombatTimeline.alpha(0,1,0,0)==1,"Zero fade durations supported");
        for(int i=0;i<1000;i++) {double a=CombatTimeline.alpha(i/10.,60,5,12);check(a>=0&&a<=1,"Opacity always bounded");}
        check(CombatTimeline.smooth(0,4)==0&&CombatTimeline.smooth(4,4)==1,"Movement reaches both endpoints");
        counterNotices();
        System.out.println("CombatTimelineChecks: "+checks+" checks passed; no game launched.");
    }
    private static void counterNotices() {
        UUID round=new UUID(1,2),nextRound=new UUID(3,4);
        var timeline=new CombatTimeline<String>(); timeline.reset(round);
        var counter=new CombatCounterNotice(); counter.reset(round,6,2);
        check(counter.amount()==0&&Double.isInfinite(counter.age(timeline.time())),
                "Joining a match with existing totals does not replay old credit");
        for(String action:new String[]{"knock","revive"}) {
            timeline.offer(round,action.equals("knock")?1:2,action,true);
            check(!counter.event(round,action,6,2,timeline.time())&&counter.amount()==0,
                    "A personal "+action+" message cannot flash the old red kill total");
            check(!counter.snapshot(round,6,2,timeline.time()),"Unchanged snapshots cannot revive a counter");
        }
        check(timeline.personal(3,120).size()==2,"Knock and revive text remain visible independently of counter notices");
        check(counter.event(round,"assist",6,3,timeline.time())&&counter.assist()&&counter.amount()==3,
                "Team finishing credit displays the new yellow assist total before its snapshot");
        for(int tick=0;tick<20;tick++)timeline.advance(false);
        check(!counter.snapshot(round,6,3,timeline.time())&&counter.age(timeline.time())==20,
                "Matching snapshot does not restart an event's counter animation");
        check(!counter.event(round,"knock",6,3,timeline.time())&&counter.assist()&&counter.amount()==3,
                "A subsequent knock cannot turn the yellow assist notice into an old red kill notice");
        check(!counter.event(round,"revive",6,3,timeline.time())&&counter.age(timeline.time())==20,
                "Reviving a teammate cannot extend a credit notice");
        for(int tick=0;tick<200;tick++)timeline.advance(false);
        timeline.offer(round,3,"revive",true);counter.event(round,"revive",6,3,timeline.time());
        check(CombatTimeline.alpha(counter.age(timeline.time()),120,4,20)==0&&timeline.personalAge()==0,
                "A new rescue message cannot resurrect an expired counter");
        check(counter.snapshot(round,7,3,timeline.time())&&!counter.assist()&&counter.amount()==7,
                "Snapshot-first elimination displays a red total when actual kills increase");
        timeline.advance(false);
        check(!counter.event(round,"elimination",7,3,timeline.time())&&counter.age(timeline.time())==1,
                "Delayed elimination event does not double-trigger snapshot credit");
        check(!counter.event(round,"elimination",6,3,timeline.time())&&!counter.snapshot(round,6,2,timeline.time())
                        &&counter.amount()==7,
                "Older event or snapshot totals cannot replace the latest credit");
        check(counter.event(round,"elimination",8,3,timeline.time())&&counter.amount()==8,
                "The next distinct elimination triggers its own total");
        for(int tick=0;tick<50;tick++)timeline.advance(true);
        check(counter.age(timeline.time())==0,"Paused match freezes the independent counter clock");
        counter.reset(nextRound,0,0);timeline.reset(nextRound);
        check(counter.amount()==0&&Double.isInfinite(counter.age(timeline.time()))
                        &&!counter.event(round,"elimination",9,3,timeline.time())
                        &&!counter.snapshot(round,9,3,timeline.time()),
                "New match clears the notice and rejects previous-session credit");
        check(counter.snapshot(nextRound,0,1,timeline.time())&&counter.assist()&&counter.amount()==1,
                "A fresh match starts assist attribution from zero");
    }
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
