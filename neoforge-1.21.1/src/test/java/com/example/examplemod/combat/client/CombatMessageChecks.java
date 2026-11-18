package com.example.examplemod.combat.client;

import com.example.examplemod.combat.CombatPayloads.Actor;
import com.example.examplemod.combat.CombatPayloads.Event;
import com.example.examplemod.localization.TestLanguages;
import java.util.List;
import java.util.UUID;
import net.minecraft.locale.Language;

public final class CombatMessageChecks {
    private static final UUID SESSION = new UUID(0, 100);
    private static final Actor DOWNER = new Actor(new UUID(0, 1), "Alice", 1, 0);
    private static final Actor FINISHER = new Actor(new UUID(0, 2), "Bob", 1, 0);
    private static final Actor VICTIM = new Actor(new UUID(0, 3), "Eve", 2, 0);

    public static void main(String[] args) {
        for (String locale : List.of("zh_cn", "en_us")) {
            Language.inject(TestLanguages.language(TestLanguages.catalog(locale)));
            boolean zh = locale.equals("zh_cn");
            String finallyMessage = zh ? "你终于淘汰了 Eve" : "You finally eliminated Eve";
            for (String cause : List.of("bleedout", "drown", "team_wipe", "disconnect", "boundary", "environment")) {
                Event event = new Event(SESSION, 1, "elimination", DOWNER, VICTIM, "", false, cause, 1, 0, true);
                expect(CombatMessageRenderer.message(event, false, 0xFF0000).getString(), finallyMessage);
                expect(CombatFeedRow.actionLabel(event), "");
                expect(CombatFeedRow.statusIcon(event, false).getPath(), "textures/gui/hud/kill_feed/downed.png");
            }
            Event knock = new Event(SESSION, 2, "knock", Actor.NONE, VICTIM, "", false, "environment", 0, 0);
            Event direct = new Event(SESSION, 2, "elimination", Actor.NONE, VICTIM, "", false, "environment", 0, 0, false);
            expect(CombatFeedRow.statusIcon(direct, false).getPath(), "textures/gui/hud/kill_feed/eliminated.png");
            expect(CombatFeedRow.actionLabel(knock), zh ? "击倒" : "knocked down");
            Event punch = new Event(SESSION, 3, "knock", DOWNER, VICTIM, "", false, "unarmed", 0, 0);
            expect(CombatMessageRenderer.message(punch, false, 0xFF0000).getString(),
                    zh ? "你用 拳头 击倒了 Eve" : "You used fists to knock down Eve");
            Event finish = new Event(SESSION, 4, "elimination", DOWNER, VICTIM, "tacz:rpg7", false,
                    "gun_blast", 1, 0, true, FINISHER);
            if (!CombatMessageRenderer.showsWeapon(finish)) throw new AssertionError("Feed omitted the finishing launcher");
            expect(CombatMessageRenderer.message(finish, false, 0xFF0000).getString(), finallyMessage);
        }
        System.out.println("Chinese and English combat messages passed.");
    }

    private static void expect(String actual, String expected) {
        if (!actual.equals(expected)) throw new AssertionError("Expected '" + expected + "', got '" + actual + "'");
    }
}
