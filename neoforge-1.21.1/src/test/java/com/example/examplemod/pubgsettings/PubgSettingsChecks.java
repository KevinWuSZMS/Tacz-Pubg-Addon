package com.example.examplemod.pubgsettings;

import com.example.examplemod.pubgsettings.client.PubgSettingsScreen;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.Locale;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.connection.ConnectionType;

public final class PubgSettingsChecks {
    private static int checks;
    public static void main(String[] args) {
        var initial=PubgSettings.initial();
        check(initial.values().isEmpty()&&initial.toJson().equals("{}"),"Old worlds retain all versioned defaults without verbose overrides");
        check(initial.number("hud.health_red_threshold")==.25&&initial.number("hud.health_apricot_threshold")==.5,
                "Reference health color bands end at 25 and 50 percent");
        check(initial.color("hud.health_red")==0xFFE85B4F&&initial.color("hud.health_apricot")==0xFFFCE6AB
                &&initial.color("hud.health_white")==0xFFF2F2F2&&initial.color("hud.health_critical_red")==0xFFE82F28,
                "Default health colors match the screenshot fill samples");
        check(initial.integer("hud.health_flash_ticks")==5,"Critical health switches color every quarter second at 20 ticks per second");
        var customHealth=initial.with("hud.health_critical_red",0xA0123456L).with("hud.health_flash_ticks",11);
        check(PubgSettings.parse(customHealth.toJson()).equals(customHealth),"Custom flash interval and critical color retain their exact values and alpha");
        check(round(PubgSettingsPayloads.State.CODEC,new PubgSettingsPayloads.State(0,3,customHealth,0)).settings().equals(customHealth),
                "Critical color and flash interval reach clients unchanged");
        var oldHealth=PubgSettings.parse("{\"hud.health_apricot_threshold\":0.4,\"hud.health_red\":4293287767}");
        check(oldHealth.number("hud.health_apricot_threshold")==.4&&oldHealth.color("hud.health_red")==0xFFE65F57,
                "Explicit old health overrides remain custom instead of being migrated");
        check(initial.with("hud.health_flash_ticks",1).integer("hud.health_flash_ticks")==1
                &&initial.with("hud.health_flash_ticks",40).integer("hud.health_flash_ticks")==40,"Flash timing accepts both administrator range endpoints");
        rejects(()->initial.with("hud.health_flash_ticks",0));
        rejects(()->initial.with("hud.health_flash_ticks",41));
        rejects(()->initial.with("hud.health_flash_ticks",5.5));
        check(initial.integer("medicine.bandage_recovery_ticks")==58&&initial.integer("medicine.first_aid_recovery_ticks")==40,"PC 42.1 recovery durations are 2.9 and 2 seconds at 20 ticks per second");
        check(initial.integer("medicine.bandage_use_ticks")==60&&initial.integer("medicine.first_aid_use_ticks")==120
                &&initial.integer("medicine.med_kit_use_ticks")==120,"PC 42.1 medicine use durations are 3, 6 and 6 seconds");
        var legacyMedicine="{\"medicine.recovery_step_ticks\":8,\"medicine.first_aid_step_percent\":10,\"medicine.bandage_recovery_ticks\":60,\"hud.bar_width\":200}";
        var migratedMedicine=PubgSettings.parse(legacyMedicine);
        check(migratedMedicine.integer("medicine.bandage_recovery_ticks")==60
                &&migratedMedicine.integer("medicine.first_aid_recovery_ticks")==40
                &&migratedMedicine.integer("hud.bar_width")==200,"Legacy cadence migrates while preserving custom duration and unrelated HUD settings");
        check(!migratedMedicine.toJson().contains("recovery_step_ticks")&&!migratedMedicine.toJson().contains("first_aid_step_percent"),"Retired medicine controls are omitted from the new save and wire data");
        check(PubgSettings.fields().stream().noneMatch(f->f.key().equals("medicine.recovery_step_ticks")||f.key().equals("medicine.first_aid_step_percent")),"The administrator editor no longer exposes ineffective cadence controls");
        check(PubgSettings.parse("{\"medicine.recovery_step_ticks\":4,\"medicine.first_aid_step_percent\":15}").equals(initial),"Legacy default-only values receive the updated medicine defaults");
        check(PubgSettings.parse("{\"medicine.bandage_recovery_ticks\":4}").integer("medicine.bandage_recovery_ticks")==5,"Legacy minimum duration migrates to fit five distinct recovery ticks");
        rejects(()->initial.with("medicine.bandage_recovery_ticks",4));
        for(String bad:new String[]{"{\"medicine.recovery_step_ticks\":0}","{\"medicine.recovery_step_ticks\":4.5}","{\"medicine.recovery_step_ticks\":21}",
                "{\"medicine.first_aid_step_percent\":0}","{\"medicine.first_aid_step_percent\":76}","{\"medicine.first_aid_step_percent\":\"15\"}",
                "{\"medicine.recovery_step_ticks\":4,\"medicine.recovery_step_ticks\":8}","{\"medicine.unknown_cadence\":4}"})rejects(()->PubgSettings.parse(bad));
        check(!initial.enabled("hud.hide_hotbar"),"Native slots six to nine retain visibility by default");
        String leanTolerance="lean.body_penetration_tolerance";
        check(initial.number(leanTolerance)==.06,"Lean body tolerance defaults to 0.06 blocks for the standard player scale");
        check(PubgSettings.field(leanTolerance).kind()==PubgSettings.Kind.NUMBER&&PubgSettings.field(leanTolerance).group().equals("lean"),"Lean tolerance is a decimal field in the lean category");
        check(PubgSettings.parse("{\"hud.bar_width\":200}").number(leanTolerance)==.06,"Existing settings without a lean override receive the new default");
        check(PubgSettings.parse(initial.with(leanTolerance,.035).toJson()).number(leanTolerance)==.035,"Fractional lean tolerance survives storage without integer rounding");
        check(initial.with(leanTolerance,0).number(leanTolerance)==0&&initial.with(leanTolerance,.15).number(leanTolerance)==.15,"Lean tolerance accepts both administrator range endpoints");
        rejects(()->initial.with(leanTolerance,-.001));
        rejects(()->initial.with(leanTolerance,.150001));
        rejects(()->initial.with(leanTolerance,Double.NaN));
        rejects(()->initial.with(leanTolerance,Double.POSITIVE_INFINITY));
        check(initial.integer("map.entity_recording_prompt_side_length")==500,"Large-map entity choice defaults to the area of a 500 by 500 block map");
        check(PubgSettings.parse(initial.with("map.entity_recording_prompt_side_length",8192).toJson()).integer("map.entity_recording_prompt_side_length")==8192,"Administrator threshold persists independently");
        rejects(()->initial.with("map.entity_recording_prompt_side_length",0));
        rejects(()->initial.with("map.entity_recording_prompt_side_length",500.5));
        var all=new LinkedHashMap<String,Double>();
        for(var f:PubgSettings.fields()) {
            f.validate(f.initial()); f.validate(f.minimum()); f.validate(f.maximum());
            check(Double.isFinite(initial.number(f.key())),"Every field has a finite default");
            if(!f.key().endsWith("threshold"))all.put(f.key(),f.maximum());
        }
        all.put("hud.health_red_threshold",.2);all.put("hud.health_apricot_threshold",.6);
        var maximum=new PubgSettings(all);
        check(maximum.toJson().length()<PubgSettings.MAX_JSON,"All 100 team colors and numeric overrides fit the wire bound");
        check(PubgSettings.parse(maximum.toJson()).equals(maximum),"Full catalog round trip");
        for(String bad:new String[]{"null","[]","{\"hud.bar_width\":200,\"hud.bar_width\":220}","{\"unknown\":1}","{\"hud.bar_width\":\"240\"}","{\"hud.bar_width\":239.5}","{\"hud.bar_width\":99999}","{\"hud.bar_width\":NaN}","{} trailing","{\"hud.hide_hotbar\":2}","{\"hud.health_red_threshold\":0.5,\"hud.health_apricot_threshold\":0.4}"})rejects(()->PubgSettings.parse(bad));
        rejects(()->PubgSettings.parse(" ".repeat(PubgSettings.MAX_JSON+1)));
        rejects(()->initial.with("map.arrowSpeed",Double.POSITIVE_INFINITY));
        var raw=new LinkedHashMap<String,String>();
        for(var f:PubgSettings.fields())raw.put(f.key(),f.kind()==PubgSettings.Kind.COLOR?String.format(Locale.ROOT,"#%08X",(long)f.initial()):Double.toString(f.initial()));
        check(PubgSettingsScreen.parseDraft(raw).equals(initial),"Editor accepts all initial field types");
        raw.put(leanTolerance,"0.035");check(PubgSettingsScreen.parseDraft(raw).number(leanTolerance)==.035,"Administrator editor preserves decimal lean tolerance");
        raw.put(leanTolerance,"0.16");rejects(()->PubgSettingsScreen.parseDraft(raw));
        raw.put(leanTolerance,"0.06");
        raw.put("hud.health_red","#123456");check(PubgSettingsScreen.parseDraft(raw).color("hud.health_red")==0xff123456,"Six-digit color gets full alpha");
        raw.put("hud.health_red","#40123456");check(PubgSettingsScreen.parseDraft(raw).color("hud.health_red")==0x40123456,"Eight-digit color retains authored opacity");
        raw.put("hud.health_red","#GG123456");rejects(()->PubgSettingsScreen.parseDraft(raw));
        raw.put("hud.health_red","#40123456");raw.put("combat.message_rows","");rejects(()->PubgSettingsScreen.parseDraft(raw));
        var data=PubgSettingsSavedData.load(new CompoundTag(),null);
        check(data.readable()&&data.revision()==0,"Empty world starts at revision zero");
        check(data.update(0,maximum)&&data.revision()==1,"Matching revision saves atomically");
        check(!data.update(0,initial)&&data.settings().equals(maximum),"Stale admin cannot overwrite a concurrent edit");
        var encoded=data.save(new CompoundTag(),null);var restored=PubgSettingsSavedData.load(encoded,null);
        check(restored.readable()&&restored.settings().equals(maximum)&&restored.revision()==1,"Saved overrides and revision survive restart");
        var legacySave=encoded.copy();legacySave.putString("settings",legacyMedicine);legacySave.putLong("revision",8);
        var migratedSave=PubgSettingsSavedData.load(legacySave,null);
        check(migratedSave.readable()&&migratedSave.revision()==8&&migratedSave.settings().equals(migratedMedicine),"Historical medicine overrides do not make the entire world settings unreadable");
        check(PubgSettings.parse(migratedSave.save(new CompoundTag(),null).getString("settings")).equals(migratedMedicine),"Migrated world settings retain current values on the next save");
        var corrupt=encoded.copy();corrupt.putString("settings","{broken");var protectedData=PubgSettingsSavedData.load(corrupt,null);
        check(!protectedData.readable()&&!protectedData.update(1,initial)&&protectedData.save(new CompoundTag(),null).equals(corrupt),"Malformed source is preserved and protected from overwrite");
        var future=encoded.copy();future.putInt("version",99);check(!PubgSettingsSavedData.load(future,null).readable(),"Unknown future version protected");
        check(round(PubgSettingsPayloads.Request.CODEC,new PubgSettingsPayloads.Request(20)).requestId()==20,"Explicit reload identity survives wire");
        var save=new PubgSettingsPayloads.Save(21,7,maximum);check(round(PubgSettingsPayloads.Save.CODEC,save).equals(save),"Save identity/revision/settings remain in order");
        for(int status=0;status<4;status++){var state=new PubgSettingsPayloads.State(21,8,maximum,status);check(round(PubgSettingsPayloads.State.CODEC,state).equals(state),"Reply status and request identity survive wire");}
        rejects(()->new PubgSettingsPayloads.Request(0));rejects(()->new PubgSettingsPayloads.Save(0,0,initial));rejects(()->new PubgSettingsPayloads.State(0,0,initial,1));
        check(round(PubgSettingsPayloads.State.CODEC,new PubgSettingsPayloads.State(0,8,maximum,0)).requestId()==0,"Broadcast is distinct from explicit acknowledgement");
        System.out.println("PubgSettingsChecks: "+checks+" checks passed; no game launched.");
    }
    private static <T>T round(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value) {
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.NEOFORGE);
        try{codec.encode(b,value);T copy=codec.decode(b);check(b.readableBytes()==0,"No unread wire data");return copy;}finally{b.release();}
    }
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Invalid value accepted");}
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
