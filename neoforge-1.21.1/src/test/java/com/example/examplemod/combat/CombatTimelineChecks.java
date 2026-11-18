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
        continuousRows();
        counterNotices();
        rollingCounters();
        System.out.println("CombatTimelineChecks: "+checks+" checks passed; no game launched.");
    }
    private static void continuousRows() {
        UUID round=new UUID(7,8);
        var timeline=new CombatTimeline<String>();timeline.reset(round);
        timeline.offer(round,1,"first",true);
        check(timeline.positionedPersonal(0,3,120,4).getFirst().slot()==0,"First message appears at its fixed baseline");
        timeline.offer(round,2,"second",true);
        var initial=timeline.positionedPersonal(0,3,120,4);
        check(initial.get(1).slot()==0,"Second message starts pushing the first from its displayed position");
        timeline.advance(false);timeline.advance(false);
        var halfway=timeline.positionedPersonal(2,3,120,4);
        check(halfway.get(1).slot()==.5,"An existing message moves half a row halfway through a push");
        timeline.offer(round,3,"third",true);
        var interrupted=timeline.positionedPersonal(2,3,120,4);
        check(interrupted.get(2).slot()==halfway.get(1).slot(),"Another arrival preserves the unfinished first-row motion");
        check(interrupted.get(1).slot()==0,"The previous newest row also starts moving without a jump");
        var feed=timeline.positionedFeed(2,3,120,4);
        check(feed.get(2).slot()==interrupted.get(2).slot(),"Public and personal stacks use the same continuous positions");
        timeline.advance(false);
        var beforeFractional=timeline.positionedPersonal(3.75,3,120,4);
        timeline.offer(round,4,"fractional arrival",true);
        var fractional=timeline.positionedPersonal(3.75,4,120,4);
        check(fractional.get(3).slot()==beforeFractional.get(2).slot(),"A packet arriving between ticks does not retroactively advance its push");
        for(int i=0;i<100;i++)timeline.advance(true);
        var paused=timeline.positionedPersonal(timeline.time(),4,120,4);
        check(paused.equals(fractional),"Entering pause does not rewind a partial-tick position");
        for(int i=0;i<8;i++)timeline.advance(false);
        var settled=timeline.positionedPersonal(timeline.time(),4,120,4);
        for(int i=0;i<settled.size();i++)check(settled.get(i).slot()==i,"Each message settles into its own row");
        check(timeline.personal(4,120).getLast().born()==0,"Later arrivals never renew an old message's lifetime");
        timeline.reset(round);
        for(int i=1;i<=4;i++)timeline.offer(round,i,"same tick "+i,true);
        var simultaneous=timeline.positionedPersonal(0,4,120,4);
        check(simultaneous.stream().allMatch(e->e.slot()==0),"Same-tick kills all begin from the baseline rather than teleporting older rows");
        timeline.advance(false);timeline.advance(false);
        simultaneous=timeline.positionedPersonal(2,4,120,4);
        check(simultaneous.get(3).slot()==1.5,"Simultaneous pushes add together without losing any rows");
        check(timeline.positionedPersonal(2,3,120,0).get(2).slot()==2,"Zero movement duration immediately uses final row positions");
        timeline.reset(round);
        timeline.offer(round,1,"expires first",true);
        for(int i=0;i<5;i++)timeline.advance(false);
        timeline.offer(round,2,"expires later",true);
        for(int i=0;i<5;i++)timeline.advance(false);
        var surviving=timeline.positionedPersonal(timeline.time(),3,10,4);
        check(surviving.size()==1&&surviving.getFirst().entry().value().equals("expires later"),"Positioned rows expire independently of newer arrivals");
        timeline.reset(round);
        check(timeline.positionedPersonal(0,3,120,4).isEmpty()&&timeline.positionedFeed(0,6,120,4).isEmpty(),"Session reset clears both movement histories");
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
    private static void rollingCounters() {
        UUID round=new UUID(7,8);
        var counter=new CombatCounterNotice();counter.reset(round,3,0);
        counter.event(round,"elimination",4,0,0);
        var first=counter.frame(0,120,4,20,4);
        check(first.previousAmount()==0&&first.progress()==1&&first.opacity()==0,
                "First notice fades in without rolling from a historical baseline");
        counter.event(round,"elimination",5,0,30);
        var start=counter.frame(30,120,4,20,4);
        check(start.previousAmount()==4&&start.amount()==5&&start.progress()==0&&start.opacity()==1,
                "Continuous credit keeps the label opaque and starts from the displayed total");
        var halfway=counter.frame(32,120,4,20,4);
        check(halfway.progress()==.5&&halfway.opacity()==1&&halfway.flash()==0,
                "Only the digits roll before the panel flashes");
        var settled=counter.frame(34,120,4,20,4);
        check(settled.progress()==1&&settled.flash()>0&&counter.frame(40,120,4,20,4).flash()==0,
                "Post-roll panel flash fades completely in six game ticks");
        check(!counter.snapshot(round,5,0,35)&&counter.age(35)==5,
                "Matching snapshot cannot replay the roll or flash");
        counter.event(round,"elimination",6,0,45);
        counter.event(round,"elimination",7,0,45);
        var batch=counter.frame(45,120,4,20,4);
        check(batch.previousAmount()==5&&batch.amount()==7,
                "Same-tick kills roll directly from the visible total to authoritative credit");
        var moving=counter.frame(46,120,4,20,4);
        counter.event(round,"elimination",8,0,46);
        var retargeted=counter.frame(46,120,4,20,4);
        check(retargeted.previousAmount()==5&&retargeted.amount()==8&&retargeted.progress()==moving.progress(),
                "A following-tick kill retargets the moving digit without restarting its position");
        check(counter.frame(46,120,4,20,4).equals(counter.frame(46,120,4,20,4)),
                "Frozen presentation time freezes the roll and flash");
        counter.event(round,"assist",8,1,50);
        check(counter.frame(50,120,4,20,4).previousAmount()==0,
                "Switching to assists never rolls from the kill count");
        counter.event(round,"assist",8,2,200);
        var expired=counter.frame(200,120,4,20,4);
        check(expired.previousAmount()==0&&expired.opacity()==0,
                "An expired total starts a new appearance rather than an old-number roll");
        check(counter.frame(205,120,0,0,0).progress()==1,
                "Zero-duration movement settles without division or invented counts");
        counter.event(round,"assist",8,3,210);
        check(counter.frame(214,120,0,0,4,0,1).flash()==0,
                "Administrator can disable the post-roll highlight");
        counter.reset(new UUID(9,10),7,2);
        check(counter.frame(0,120,4,20,4).amount()==0,
                "New session clears outgoing digits and highlight state");
    }
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
