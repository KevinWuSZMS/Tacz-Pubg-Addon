package com.example.examplemod.vitals;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Medical timing, health caps and fixed post-use budgets; does not launch a game. */
public final class PubgMedicineChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var bandage = PubgMedicineModel.Kind.BANDAGE;
        var aid = PubgMedicineModel.Kind.FIRST_AID_KIT;
        var kit = PubgMedicineModel.Kind.MED_KIT;
        check(bandage.useTicks() == 60 && bandage.recoveryTicks() == 58, "PC 42.1 bandage takes 3s and recovers over 2.9s");
        check(kit.useTicks() == 120 && kit.recoveryTicks() == 0, "PC 42.1 med kit takes 6s then heals instantly");
        check(aid.useTicks() == 120 && aid.recoveryTicks() == 40, "First aid casts for 6s then restores over 2s");
        check(bandage.recoverySteps() == 5 && aid.recoverySteps() == 5 && kit.recoverySteps() == 1,
                "Bandages and first aid use five pulses while med kits are immediate");
        near(bandage.healingBudget(1, 20), 2.4, "Bandage recovers 12% maximum health");
        near(bandage.healingBudget(14, 20), 1, "Bandage stops at 75%");
        near(aid.healingBudget(5, 20), 10, "First aid snapshots its 75% target");
        near(kit.healingBudget(5, 20), 15, "Med kit reaches full health");
        near(bandage.healingBudget(10, 40), 4.8, "Health normalization supports changed maximum HP");
        near(bandage.firstStepBudget(5, 20), .48, "Each bandage pulse converts 2.4 PUBG HP to 0.48 MC HP");
        near(aid.firstStepBudget(5, 20), 2, "First aid divides the current deficit to 15 MC HP into five equal pulses");
        near(kit.firstStepBudget(5, 20), 15, "The med-kit preview contains its whole instant heal");
        for (var kind : new PubgMedicineModel.Kind[] {bandage, aid}) {
            check(!kind.canUse(15, 20, 0), "75%-limited healing cannot waste an item at its ceiling");
            check(!kind.canUse(16, 20, 0), "75%-limited healing cannot reduce health above its ceiling");
            check(kind.canUse(14.99, 20, 0), "Healing remains usable just below 75%");
            check(!kind.canUse(30, 40, 0), "The 75% restriction scales with maximum HP");
            check(!kind.canUse(31, 40, 0), "Higher maximum HP cannot bypass the restriction");
        }
        check(!kit.canUse(20, 20, 0), "Full health does not consume med kits");
        check(!kit.canUse(0, 20, 0), "Medicine cannot resurrect a dead player");
        for (var kind : new PubgMedicineModel.Kind[] { PubgMedicineModel.Kind.ENERGY_DRINK,
                PubgMedicineModel.Kind.PAINKILLER, PubgMedicineModel.Kind.ADRENALINE_SYRINGE }) {
            check(kind.canUse(20, 20, 0), "Boost can be consumed at full health");
            check(!kind.canUse(20, 20, 100), "Full boost does not consume boosters");
            check(!kind.canUse(20, 20, -1), "Paused or unavailable players cannot consume boost");
            check(!kind.canUse(20, 20, Double.NaN), "Invalid energy cannot bypass the full-gauge guard");
            check(kind.canUse(20, 20, 40), "Ordinary-world boost uses the same gauge rules");
            near(kind.healingBudget(1, 20), 0, "Boost never directly heals");
        }
        check(!PubgMedicineModel.completed(100, 158, 60, 60), "Client duration cannot bypass server clock");
        check(PubgMedicineModel.Kind.ENERGY_DRINK.useTicks() == 80, "Energy drink takes four seconds");
        check(PubgMedicineModel.Kind.PAINKILLER.useTicks() == 120, "Painkiller takes six seconds");
        check(PubgMedicineModel.Kind.ADRENALINE_SYRINGE.useTicks() == 120, "Adrenaline syringe takes six seconds");
        check(!PubgMedicineModel.completed(100, 160, 60, 10), "Server clock cannot bypass actual continuous use");
        check(PubgMedicineModel.completed(100, 159, 60, 59), "Vanilla final entity tick completes before post-server clock");
        check(PubgMedicineModel.movedTooFar(1), "Large displacement interrupts");
        check(!PubgMedicineModel.movedTooFar(.05), "Walking remains allowed");
        check(PubgMedicineModel.movedTooFar(Double.NaN), "Invalid movement cannot bypass validation");
        var recovery = new PubgMedicineModel.Recovery(10, .75, 40, 100, 0);
        var first = recovery.advance(120, 5, 20);
        near(first.healing(), 4, "Two of five first-aid pulses have completed after one second");
        var damaged = first.recovery().advance(140, 6, 20);
        near(damaged.healing(), 6, "Damage during recovery does not enlarge the original budget");
        check(damaged.recovery().finished(), "Recovery expires after its fixed duration");
        near(damaged.recovery().advance(200, 1, 20).healing(), 0, "Expired treatment cannot refill later damage");
        var capped = recovery.advance(120, 14, 20);
        near(capped.healing(), 1, "Other healing cannot make medicine exceed 75%");
        near(capped.recovery().advance(140, 1, 20).healing(), 6, "Discarded capped healing cannot be banked");
        near(recovery.advance(100, 5, 20).healing(), 0, "A paused clock delivers no recovery");
        fivePulseSchedule(bandage, 5, 20, new int[]{12, 24, 35, 47, 58}, .48, 7.4);
        fivePulseSchedule(aid, 5, 20, new int[]{8, 16, 24, 32, 40}, 2, 15);
        fivePulseSchedule(bandage, 10, 40, new int[]{12, 24, 35, 47, 58}, .96, 14.8);
        fivePulseSchedule(aid, 10, 40, new int[]{8, 16, 24, 32, 40}, 4, 30);
        fivePulseSchedule(bandage, 14, 20, new int[]{12, 24, 35, 47, 58}, .2, 15);
        steppedRecoveryAndHud();
        inputPaths();
        System.out.println("PubgMedicineChecks: " + checks + " checks passed; no game launched.");
    }
    private static void healthWarningColors(com.example.examplemod.vitals.client.PubgVitalsLayout.Style style) {
        check(style.red() == 0xFFE85B4F && style.criticalRed() == 0xFFE82F28
                && style.apricot() == 0xFFFCE6AB && style.white() == 0xFFF2F2F2,
                "Health colors match the supplied screenshot fill samples");
        check(style.flashTicks() == 5, "User-selected warning alternates every quarter-second");
        for (long tick : new long[]{0, 4, 5, 9, 10, 14, 15, 19, 20, 1_000_005}) {
            int warning = tick % 10 < 5 ? style.red() : style.criticalRed();
            check(com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(Math.nextDown(.1), style, tick) == warning,
                    "Below 10% alternates exactly at each five-tick boundary");
            check(com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(.1, style, tick) == style.red(),
                    "Exactly 10% is steady soft red at every animation phase");
            check(com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(0, style, tick) == style.red(),
                    "Zero health cannot flash a living fill");
            for (double ratio : new double[]{.25, .499, .5, .999, 1})
                check(com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(ratio, style, tick)
                        == com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(ratio, style),
                        "Noncritical colors stay steady through the warning cycle");
        }
        for (double maximum : new double[]{20, 40}) {
            var soft = com.example.examplemod.vitals.client.PubgVitalsLayout.bars(640, 360, maximum * .05, maximum,
                    maximum * .2, 40, 0, maximum * .75, maximum * .15, style, 0);
            var bright = com.example.examplemod.vitals.client.PubgVitalsLayout.bars(640, 360, maximum * .05, maximum,
                    maximum * .2, 40, 0, maximum * .75, maximum * .15, style, 5);
            check(soft.size() == bright.size(), "Warning never changes health/forecast geometry");
            int changes = 0;
            for (int i = 0; i < soft.size(); i++) {
                var a = soft.get(i); var b = bright.get(i);
                check(a.left() == b.left() && a.top() == b.top() && a.right() == b.right() && a.bottom() == b.bottom(),
                        "All geometry remains identical at the phase switch");
                if (a.color() != b.color()) {
                    changes++;
                    check(a.color() == style.red() && b.color() == style.criticalRed(),
                            "Only actual remaining health flashes, not the damage trail, forecast or boost");
                }
            }
            check(changes == 1, "Exactly the actual health fill alternates");
            check(com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(
                    com.example.examplemod.vitals.client.PubgVitalsLayout.fraction(maximum * .1, maximum), style, 5) == style.red(),
                    "Ten-percent boundary scales with actual maximum health");
        }
    }
    private static void fivePulseSchedule(PubgMedicineModel.Kind kind, double health, double maximum,
                                          int[] deadlines, double amount, double expectedHealth) {
        var recovery = new PubgMedicineModel.Recovery(kind.healingBudget(health, maximum), kind.ceiling(),
                kind.recoveryTicks(), 0, 0, kind.recoverySteps());
        int pulses = 0;
        for (int tick = 1; tick <= kind.recoveryTicks(); tick++) {
            var step = recovery.advance(tick, health, maximum);
            if (pulses < deadlines.length && tick == deadlines[pulses]) {
                near(step.healing(), amount, kind + " delivers the expected converted health at pulse " + (pulses + 1));
                pulses++;
            } else near(step.healing(), 0, kind + " does not heal between pulse deadlines");
            health += step.healing(); recovery = step.recovery();
            check(recovery.finished() == (tick == kind.recoveryTicks()), kind + " recovery lasts its complete window");
            near(recovery.nextBudget(), tick == kind.recoveryTicks() ? 0 : amount,
                    kind + " HUD next-pulse budget matches the next scheduled heal");
        }
        check(pulses == 5, kind + " has exactly five positive pulses");
        near(health, expectedHealth, kind + " total recovery uses the intended health scale and ceiling");
        near(recovery.remainingBudget(), 0, kind + " has no recovery remaining after its fifth pulse");
    }
    private static void steppedRecoveryAndHud() throws Exception {
        var aid = new PubgMedicineModel.Recovery(7.4, .75, 40, 100, 0, 5);
        near(aid.advance(107, 7.6, 20).healing(), 0, "First aid cannot heal before its 0.4s pulse");
        var first = aid.advance(108, 7.6, 20);
        near(first.healing(), 1.48, "First aid divides the captured deficit into five pulses");
        near(first.recovery().remainingBudget(), 5.92, "A pulse subtracts from the original fixed budget");
        near(first.recovery().advance(115, 9.08, 20).healing(), 0, "No smooth healing leaks between pulses");
        var second = first.recovery().advance(116, 9.08, 20);
        near(second.healing(), 1.48, "Second scheduled first-aid pulse");
        near(second.recovery().advance(140, 10.56, 20).healing(), 4.44, "The final three pulses reach 75% exactly");
        near(second.recovery().advance(140, 5, 20).healing(), 4.44, "New damage cannot enlarge the remaining treatment budget");
        near(first.recovery().advance(116, 14, 20).healing(), 1, "Concurrent healing still obeys the health ceiling");
        near(first.recovery().advance(108, 9.08, 20).healing(), 0, "Revisiting the same clock cannot duplicate a pulse");
        near(first.recovery().advance(107, 9.08, 20).healing(), 0, "A stale clock cannot roll a recovery back");
        var bandage = new PubgMedicineModel.Recovery(2.4, .75, 58, 0, 0, 5);
        near(bandage.advance(58, 5, 20).healing(), 2.4, "Delayed settlement does not lose bandage recovery");
        var aidTarget = com.example.examplemod.vitals.client.PubgMedicineHud.forecast(7.6, 20, 0, 0, 0,
                PubgMedicineModel.Kind.FIRST_AID_KIT);
        near(aidTarget.total(), 15, "First-aid cast preview ends at 15 MC HP");
        near(aidTarget.next(), 9.08, "First-aid next-pulse preview uses one fifth of the missing health");
        var bandageTarget = com.example.examplemod.vitals.client.PubgMedicineHud.forecast(5, 20, 0, 0, 0,
                PubgMedicineModel.Kind.BANDAGE);
        near(bandageTarget.total(), 7.4, "Bandage preview adds only 2.4 MC HP");
        near(bandageTarget.next(), 5.48, "Bandage next-pulse preview adds only 0.48 MC HP");
        var continuedBandage = com.example.examplemod.vitals.client.PubgMedicineHud.forecast(5, 20, 2.4, .48, .75,
                PubgMedicineModel.Kind.BANDAGE);
        near(continuedBandage.total(), 9.8, "Starting another bandage previews both treatments' total remaining healing");
        near(continuedBandage.next(), 5.48, "Casting another bandage does not double the older treatment's next pulse");
        var bandageThenAid = com.example.examplemod.vitals.client.PubgMedicineHud.forecast(5, 20, 2.4, .48, .75,
                PubgMedicineModel.Kind.FIRST_AID_KIT);
        near(bandageThenAid.total(), 15, "Starting first aid during bandage recovery still previews the 75% ceiling");
        near(bandageThenAid.next(), 5.48, "The pending bandage pulse remains next while first aid is being cast");
        var injuredTarget = com.example.examplemod.vitals.client.PubgMedicineHud.forecast(6.08, 20,
                first.recovery().remainingBudget(), first.recovery().nextBudget(), .75, null);
        near(injuredTarget.total(), 12, "An injury during recovery reduces the HUD endpoint instead of expanding its budget");
        near(injuredTarget.next(), 7.56, "The HUD preserves the next pulse after an injury");
        var cappedTarget = com.example.examplemod.vitals.client.PubgMedicineHud.forecast(14.9, 20, 2, .4, .75, null);
        near(cappedTarget.total(), 15, "The remaining-budget forecast respects the 75% cap");
        near(cappedTarget.next(), 15, "The next-pulse forecast also respects the 75% cap");
        var kitTarget = com.example.examplemod.vitals.client.PubgMedicineHud.forecast(5, 20, 0, 0, 0,
                PubgMedicineModel.Kind.MED_KIT);
        near(kitTarget.total(), 20, "Med-kit preview reaches full MC health");
        near(kitTarget.next(), 20, "Med-kit preview does not invent a post-use healing sequence");
        var finishedTarget = com.example.examplemod.vitals.client.PubgMedicineHud.forecast(12, 20, 0, 0, 0, null);
        near(finishedTarget.total(), 12, "Finished or canceled healing leaves no total forecast");
        near(finishedTarget.next(), 12, "Finished or canceled healing leaves no next-pulse forecast");
        var style = com.example.examplemod.vitals.client.PubgVitalsLayout.Style.defaults();
        check(com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(Math.nextDown(.25), style) == style.red(), "Below 25% is red");
        check(com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(.25, style) == style.apricot(), "Exactly 25% is pale yellow");
        check(com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(Math.nextDown(.5), style) == style.apricot(), "Below 50% remains pale yellow");
        check(com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(.5, style) == style.white(), "Exactly 50% is ordinary white");
        check(com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(Math.nextDown(1.0), style) == style.white(), "Every value below 100% remains white");
        healthWarningColors(style);
        check(com.example.examplemod.vitals.client.PubgVitalsLayout.healthColor(1, style) == style.full()
                && (style.full() >>> 24) < (style.white() >>> 24), "Full HP is greyer and more transparent");
        for (int[] viewport : new int[][]{{220,160},{320,240},{640,360},{1920,1080}}) {
            var frame = com.example.examplemod.vitals.client.PubgVitalsLayout.frame(viewport[0], viewport[1], style);
            var rectangles = com.example.examplemod.vitals.client.PubgVitalsLayout.bars(viewport[0],viewport[1],7.6,20,7.6,45,0,
                    aidTarget.total(),aidTarget.next(),style);
            check(rectangles.stream().allMatch(r -> r.left() >= 0 && r.top() >= 0 && r.right() <= viewport[0] && r.bottom() <= viewport[1]), "Combat bars escaped a resized GUI");
            var target = rectangles.stream().filter(r -> r.color() == style.treatment()).findFirst().orElseThrow();
            var next = rectangles.stream().filter(r -> r.color() == style.nextStep()).findFirst().orElseThrow();
            check(target.right() == frame.left() + Math.round(frame.width() * .75)
                    && next.right() == frame.left() + Math.round(frame.width() * .454), "Total and next-pulse grey targets lost their distinct reference endpoints");
            check(rectangles.stream().filter(r -> r.color() == style.reference()).allMatch(r -> r.right() <= target.right()), "75% reference hatching leaks past the healing ceiling");
        }
        check(com.example.examplemod.vitals.client.WeaponHudData.loaded(29, 95, false, true, false) == 30, "Closed bolt counts its chambered round");
        check(com.example.examplemod.vitals.client.WeaponHudData.loaded(30, 95, false, true, true) == 30, "Open bolt never duplicates a chambered round");
        check(com.example.examplemod.vitals.client.WeaponHudData.loaded(0, 95, true, false, false) == 95, "Inventory-fed weapon reads inventory ammunition");
        check(com.example.examplemod.vitals.client.WeaponHudData.loaded(Integer.MAX_VALUE, 0, false, true, false) == Integer.MAX_VALUE, "Large ammunition counts cannot overflow negative");
        check(com.example.examplemod.vitals.client.WeaponStackRenderer.visibleSlots(List.of(true,false,true,false,true,true)).equals(List.of(0,2,4)),
                "Sparse quick slots preserve numbers, drop empty rows and exclude slots beyond five");
        var genericItem = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("lrtactical", "throwable");
        var smokeKey = new com.example.examplemod.vitals.client.WeaponStackRenderer.ThrowableKey(genericItem,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("lrtactical", "smoke"));
        var stunKey = new com.example.examplemod.vitals.client.WeaponStackRenderer.ThrowableKey(genericItem,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("lrtactical", "stun"));
        check(!smokeKey.equals(stunKey), "Different LR assets sharing one generic Item must remain separate quantity icons");
        var stackRenderer = node("com/example/examplemod/vitals/client/WeaponStackRenderer");
        check(calls(method(stackRenderer, "throwableKey"), "me/xjqsh/lrtactical/api/item/IThrowable", "getId"), "Native LR asset id is used by real aggregation, not just item identity");
        check(Arrays.stream(method(stackRenderer, "draw").instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call
                && call.name.equals("drawGunSilhouette") && call.desc.contains("Lnet/minecraft/world/item/ItemStack;")),
                "Side-stack gun drawing must preserve the native silhouette ratio and selected display skin");
        near(com.example.examplemod.vitals.client.ActionCircleRenderer.progress(120,120),0,"Circle begins empty at twelve o'clock");
        near(com.example.examplemod.vitals.client.ActionCircleRenderer.progress(30,120),.75,"Circle uses elapsed action duration");
        near(com.example.examplemod.vitals.client.ActionCircleRenderer.progress(0,120),1,"Circle completes at full sweep");
        try (var zip = new java.util.zip.ZipFile(java.nio.file.Files.isDirectory(java.nio.file.Path.of("run/mods"))
                ? "run/mods/tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar" : "neoforge-1.21.1/run/mods/tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar")) {
            for (var mode : List.of(com.tacz.guns.api.item.gun.FireMode.SEMI,com.tacz.guns.api.item.gun.FireMode.BURST,com.tacz.guns.api.item.gun.FireMode.AUTO)) {
                var texture = com.example.examplemod.vitals.client.WeaponHudData.fireModeTexture(mode);
                var entry = zip.getEntry("assets/"+texture.getNamespace()+"/"+texture.getPath());
                check(entry != null, "Native TaCZ firing-mode asset missing: "+mode);
                try (var stream=zip.getInputStream(entry)) { var bytes=stream.readNBytes(24); var header=java.nio.ByteBuffer.wrap(bytes);
                    check(header.getInt(16)==128&&header.getInt(20)==128,"Native firing-mode source PNG is 128x128, sampled in full by TaCZ's logical 10x10 UV"); }
            }
        }
    }
    private static void inputPaths() throws Exception {
        for (int slot = 0; slot < 9; slot++) {
            check(new MatchVitalsPayloads.StartMedicine(slot, 12).slot() == slot, "Every selected hotbar slot can request a held treatment");
            check(new MatchVitalsPayloads.MedicineStarted(slot, 12, 0).duration() == 0, "Server can explicitly reject a request without starting a timer");
        }
        for (int slot : new int[]{-1, 9}) {
            try { new MatchVitalsPayloads.StartMedicine(slot, 1); throw new AssertionError("Invalid slot accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        for (int request : new int[]{0, -1}) {
            try { new MatchVitalsPayloads.StartMedicine(0, request); throw new AssertionError("Invalid request identity accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            try { new MatchVitalsPayloads.CancelMedicine(request); throw new AssertionError("Invalid cancellation identity accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        var item = node("com/example/examplemod/vitals/PubgMedicineItem");
        check(field(method(item, "use"), "MAIN_HAND") && calls(method(item, "use"), "net/minecraft/world/InteractionResultHolder", "fail")
                && calls(method(item, "use"), item.name, "startTreatment"), "Main-hand vanilla use must fail while retaining the offhand path");
        check(calls(method(item, "startTreatment"), "com/example/examplemod/vitals/PubgMedicineService", "begin")
                && calls(method(item, "startTreatment"), "net/minecraft/world/entity/player/Player", "startUsingItem"),
                "Dedicated treatment start bypassed server medical eligibility or vanilla duration");
        check(calls(method(item, "appendHoverText"), "net/minecraft/network/chat/Component", "keybind"), "Medicine hints hard-code the interaction key");
        var service = node("com/example/examplemod/vitals/PubgMedicineService");
        var start = method(service, "startHeld");
        check(field(start, "selected") && field(start, "containerMenu") && field(start, "inventoryMenu")
                && calls(start, "net/minecraft/server/level/ServerPlayer", "isUsingItem")
                && calls(start, item.name, "startTreatment") && field(start, "inputRequest"),
                "Held requests can start the wrong slot, restart an existing use, or lose their scoped cancellation identity");
        check(field(method(service, "cancelRequest"), "inputRequest") && calls(method(service, "cancelRequest"), service.name, "stopUse"),
                "A delayed cancellation can interrupt a different treatment");
        var inventory = node("com/example/examplemod/inventory/MatchInventoryService");
        check(calls(method(inventory, "selectAndUse"), item.name, "startTreatment")
                && !calls(method(inventory, "selectAndUse"), item.name, "use"), "Medicine wheel still calls the now-blocked vanilla main-hand entry");
        var ground = node("com/example/examplemod/lootspawn/client/GroundLootClient");
        String clientName = "com/example/examplemod/vitals/client/MedicineClient";
        check(callIndex(method(ground, "tryPickupOrInteract"), clientName, "tryUseHeld")
                < callIndex(method(ground, "tryPickupOrInteract"), ground.name, "tryPickup"), "Pickup can run before the held medicine");
        check(callIndex(method(ground, "mouse"), clientName, "captureMouse")
                < callIndex(method(ground, "mouse"), "com/example/examplemod/inventory/client/MatchGunInteractionClient", "captureMouse"),
                "Mouse interaction can also reach shooting or pickup before medicine");
        var keyboard = node("com/example/examplemod/inventory/mixin/MatchGunKeyboardMixin");
        check(callIndex(method(keyboard, "taczPubg$unifiedGunInteract"), clientName, "captureKey")
                < callIndex(method(keyboard, "taczPubg$unifiedGunInteract"), "com/example/examplemod/inventory/client/MatchGunInteractionClient", "captureKey"),
                "Keyboard interaction bypasses the medicine priority");
        var client = node(clientName);
        check(calls(method(client, "captureKey"), "com/example/examplemod/lootspawn/client/GroundLootClient", "pickupKey")
                && calls(method(client, "captureKey"), "net/minecraft/client/KeyMapping", "matches")
                && calls(method(client, "captureMouse"), "net/minecraft/client/KeyMapping", "matchesMouse"), "Medicine input ignores a rebound shared binding");
        check(calls(method(client, "captureKey"), "com/example/examplemod/keybindings/BindingInput", "freshPress")
                && calls(method(client, "captureMouse"), "com/example/examplemod/keybindings/BindingInput", "freshPress"),
                "A second chord member or already-held alternative can cancel the first medicine press");
        check(!calls(method(client, "active"), "com/example/examplemod/inventory/InventoryConfig", "active")
                && !calls(method(client, "active"), "com/example/examplemod/vitals/client/MatchVitalsHud", "active"), "Ordinary-world treatment is gated to a match");
        check(field(method(client, "accept"), "pending") && calls(method(client, "accept"), clientName, "sameHeld")
                && calls(method(client, "accept"), clientName, "cancelPending") && !field(method(client, "accept"), "selected"),
                "Late acknowledgements can switch back to an old hotbar slot or revive cancelled input");
        var throwable = node("com/example/examplemod/throwables/client/ThrowableClient");
        check(calls(method(client, "tryUseHeld"), throwable.name, "consumeSharedCancelClick")
                && calls(method(client, "tryUseHeld"), throwable.name, "cancelMedicineUse")
                && calls(method(throwable, "cancelMedicineUse"), throwable.name, "requestCancel")
                && calls(method(throwable, "requestCancel"), throwable.name, "cancelQuick"),
                "Shared medicine interaction cannot cancel or leaves automatic use running");
        var sustain = method(throwable, "sustainingMedicineUse");
        check(field(sustain, "quickSlot") && field(sustain, "quickStarted") && calls(sustain, "net/minecraft/client/player/LocalPlayer", "isUsingItem")
                && calls(sustain, "net/minecraft/client/player/LocalPlayer", "getUsedItemHand")
                && calls(sustain, "net/minecraft/client/player/LocalPlayer", "getUseItem")
                && calls(sustain, "net/minecraft/client/player/LocalPlayer", "getMainHandItem"), "Sustaining a medicine can outlive its exact current main-hand use");
        check(!calls(method(throwable, "tick"), "net/minecraft/client/KeyMapping", "setDown")
                && !calls(method(throwable, "cancelQuick"), "net/minecraft/client/KeyMapping", "setDown")
                && !calls(method(throwable, "tick"), "net/minecraft/client/player/LocalPlayer", "startUsingItem"),
                "Auto-use still rewrites physical right-click state or restarts from the tick loop");
        var minecraft = node("net/minecraft/client/Minecraft");
        check(calls(method(minecraft, "handleKeybinds"), "net/minecraft/client/KeyMapping", "isDown")
                && calls(method(minecraft, "handleKeybinds"), "net/minecraft/client/multiplayer/MultiPlayerGameMode", "releaseUsingItem"),
                "Native release-on-key-up call site changed");
        check(field(method(client, "useKeyDown"), "keyUse")
                && calls(method(client, "useKeyDown"), throwable.name, "sustainingMedicineUse")
                && calls(method(client, "useKeyDown"), "net/minecraft/client/KeyMapping", "isDown"), "Virtual hold does not preserve all other physical key states");
        try (var stream = PubgMedicineChecks.class.getResourceAsStream("/tacz_pubg.vitals.mixins.json")) {
            check(stream != null && new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("MedicineUseInputMixin"),
                    "Single-press medicine hold mixin is not registered");
        }
        var packets = node("com/example/examplemod/vitals/MatchVitalsPayloads");
        check(Arrays.stream(method(packets, "register").instructions.toArray()).anyMatch(i -> i instanceof LdcInsnNode text && text.cst.equals("4-match-vitals")),
                "Changed medical input wire registration must reject incompatible clients");
    }
    private static ClassNode node(String name) throws IOException {
        try (var input = PubgMedicineChecks.class.getResourceAsStream("/" + name + ".class")) {
            check(input != null, "Missing runtime class: " + name);
            var node = new ClassNode(); new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); return node;
        }
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError("Missing method " + node.name + "." + name));
    }
    private static boolean calls(MethodNode method, String owner, String name) { return callIndex(method, owner, name) < Integer.MAX_VALUE; }
    private static int callIndex(MethodNode method, String owner, String name) {
        var code = method.instructions.toArray();
        for (int i = 0; i < code.length; i++) if (code[i] instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return i;
        return Integer.MAX_VALUE;
    }
    private static boolean field(MethodNode method, String name) {
        return Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode field && field.name.equals(name));
    }
    private static void near(double actual, double expected, String reason) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) < 1.0e-8, reason + ": " + actual);
    }
    private static void check(boolean condition, String reason) {
        checks++; if (!condition) throw new AssertionError(reason);
    }
}
