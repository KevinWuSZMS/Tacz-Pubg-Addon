package com.example.examplemod.combat;

import java.util.List;
import java.util.UUID;

/** User finishing policy, assist boundaries, DBNO bleed budget and rescue eligibility without a world. */
public final class CombatRulesChecks {
    private static int checks;
    public static void main(String[] args) {
        UUID victim=id(1),downer=id(2),mate=id(3),enemy=id(4),friendly=id(5);
        var damage=List.of(new CombatRules.Contributor(downer,"A",40,100),new CombatRules.Contributor(mate,"A",20,100),new CombatRules.Contributor(enemy,"B",30,100),new CombatRules.Contributor(friendly,"V",100,100));
        var same=CombatRules.credit(victim,"V",downer,"A",mate,"A",damage,200,1200,1);
        check(same.killer().equals(downer)&&same.assistants().equals(List.of(mate)),"Same-team finisher grants downer kill and finisher assist; excludes third parties/friendly hits");
        var finishOnly=CombatRules.credit(victim,"V",downer,"A",mate,"A",List.of(),2000,1,100);
        check(finishOnly.killer().equals(downer)&&finishOnly.assistants().equals(List.of(mate)),
                "A teammate who only finishes the downed enemy still earns an assist without prior damage or time-window credit");
        var ownFinish=CombatRules.credit(victim,"V",downer,"A",downer,"A",List.of(),2000,1,100);
        check(ownFinish.killer().equals(downer)&&ownFinish.assistants().isEmpty(),"Finishing one's own knock awards no self-assist");
        var stolen=CombatRules.credit(victim,"V",downer,"A",enemy,"B",damage,200,1200,1);
        check(stolen.killer().equals(enemy)&&stolen.assistants().isEmpty(),"Different enemy team's finisher owns kill; unrelated team cannot gain an assist");
        var bleed=CombatRules.credit(victim,"V",downer,"A",null,"",damage,200,1200,1);
        check(bleed.killer().equals(downer)&&bleed.assistants().equals(List.of(mate)),"Bleedout/team wipe/environment credits recorded hostile downer");
        var teamkill=CombatRules.credit(victim,"V",friendly,"V",friendly,"V",damage,200,1200,1);
        check(teamkill.killer()==null&&teamkill.assistants().isEmpty(),"Friendly damage never creates positive kill or assist");
        var solo=CombatRules.credit(victim,"V",null,"",downer,"A",damage,200,1200,1);
        check(solo.killer().equals(downer),"Direct solo elimination has an independent finisher");
        check(CombatRules.credit(victim,"V",null,"",null,"",damage,200,1200,1).killer()==null,"An environmental elimination without a downer has no invented killer");
        var boundary=List.of(new CombatRules.Contributor(mate,"A",1,100));
        check(CombatRules.credit(victim,"V",null,"",downer,"A",boundary,1300,1200,1).assistants().contains(mate),"Inclusive assist time and minimum damage boundaries");
        check(CombatRules.credit(victim,"V",null,"",downer,"A",boundary,1301,1200,1).assistants().isEmpty(),"Expired damage does not produce assists");
        check(CombatRules.credit(victim,"V",null,"",downer,"A",boundary,1300,1200,1.01).assistants().isEmpty(),"Below-threshold damage does not produce assists");
        check(!CombatRules.directDeath(true,true,false),"An actionable teammate permits DBNO");
        check(CombatRules.directDeath(true,false,false),"No rescue route means direct death");
        check(!CombatRules.directDeath(true,false,true),"Self-rescue eligibility can independently permit DBNO");
        check(CombatRules.directDeath(false,true,true),"Disabled DBNO always chooses direct death");
        for(int count=-2;count<20;count++)check(CombatRules.bleedTier(count)>=1&&CombatRules.bleedTier(count)<=9,"Repeated knock tier is bounded");
        near(CombatRules.pubgDamage(20,20),100,"Default Minecraft health normalizes to PUBG100");
        near(CombatRules.pubgDamage(40,40),100,"Modified max health preserves independent DBNO damage ratio");
        check(CombatRules.pubgDamage(Double.NaN,20)==0,"Malformed damage is rejected");
        float hp=100;for(int tick=0;tick<1800;tick++)hp=CombatRules.bleed(hp,100,90,false);
        check(hp<.01,"First stationary down depletes its whole budget in90seconds");
        for(int tick=0;tick<10000;tick++)hp=CombatRules.bleed(25,100,90,true);
        check(hp==25,"Paused/rescued blood budget is frozen exactly");
        check(CombatRules.mayRescue(true,true,true,false,9,3,true,.1225,.35),"Rescue accepts inclusive reach and movement boundaries");
        for(int invalid=0;invalid<8;invalid++)check(!CombatRules.mayRescue(invalid!=0,invalid!=1,invalid!=2,invalid==3,invalid==4?9.01:9,3,invalid!=5,invalid==6?.123:.1225,invalid==7?.3:.35),"Every rescue eligibility condition is enforced");
        check(CombatTeams.number("lobby:20:team:21")==21,"Frozen global team number is not squad-member slot");
        check(CombatTeams.number("lobby:20:team:100")==100&&CombatTeams.number("lobby:20:team:101")==0,"Global team numbers honor100teams");
        check(CombatTeams.number("scoreboard:blue")==0,"Ordinary scoreboard identifiers use deterministic allocation fallback");
        System.out.println("CombatRulesChecks: "+checks+" checks passed; no game launched.");
    }
    private static UUID id(long n){return new UUID(0,n);}
    private static void near(double actual,double expected,String why){check(Math.abs(actual-expected)<1e-6,why);}
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
}
