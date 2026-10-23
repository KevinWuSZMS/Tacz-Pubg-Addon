package com.example.examplemod.lootconfig.client;

import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootTier;
import com.example.examplemod.lootconfig.LootConfigModel.TierProfile;
import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exercises the actual native page geometry and acknowledgement gates without starting Minecraft. */
public final class LootConfigNativeChecks {
    private static int checks;
    private static void check(boolean success, String message) {
        checks++;
        if (!success) throw new AssertionError(message);
    }
    private record Rect(int x, int y, int width, int height) {}

    public static void main(String[] args) throws Exception {
        otherSettingsNavigation();
        for (int width : new int[]{320, 480, 599, 600, 640, 960})
            for (int height : new int[]{240, 360, 384, 540})
                for (boolean advanced : new boolean[]{false, true})
                    for (boolean gunClasses : new boolean[]{false, true}) checkLayout(width, height, advanced, gunClasses);

        check(!LootConfigScreen.layoutFor(320, 240, false).inlineAmmo(), "minimum GUI keeps the complete child editor reachable");
        check(LootConfigScreen.layoutFor(640, 360, false).inlineAmmo(), "640-wide GUI shows all three plans inline");
        for (int index = 0; index < 4; index++) {
            check(LootConfigScreen.canNavigate(index, false), "all four tabs can retain unfinished raw drafts");
            check(!LootConfigScreen.canNavigate(index, true), "a pending server write prevents navigation");
        }
        check(!LootConfigScreen.canNavigate(-1, false) && !LootConfigScreen.canNavigate(4, false), "unknown navigation targets are rejected");

        Document submitted = new Document(LootConfigModel.General.defaults(), List.of(new Entry("gun", "test:gun", 1, 1, 1)));
        List<String> originalRaw = List.of("100.00", "1", "30", "60", "10");
        check(!LootConfigScreen.editedAfterSubmission(submitted, submitted, originalRaw, originalRaw),
                "accepted numeric formatting does not masquerade as a post-submission edit");
        check(LootConfigScreen.editedAfterSubmission(submitted, submitted, List.of("", "1", "30", "60", "10"), originalRaw),
                "a later incomplete field remains a newer draft even when its model has not parsed");
        Document later = new Document(submitted.general(), List.of(new Entry("gun", "test:gun", 1, 1, 2)));
        check(LootConfigScreen.editedAfterSubmission(later, submitted, originalRaw, originalRaw),
                "a later valid model edit cannot be replaced by the older acknowledgement");
        check(!LootConfigScreen.editedAfterSubmission(submitted, null, originalRaw, null), "an unsolicited snapshot has no submitted draft to overwrite");
        check(LootConfigScreen.shouldPreserveDraft(later, later, submitted, originalRaw, originalRaw, true),
                "submit A, timeout, submit B, then late ACK A cannot replace B or its raw baseline");
        check(!LootConfigScreen.shouldPreserveDraft(later, later, later, originalRaw, originalRaw, true),
                "the matching ACK B can complete its own unedited submission");
        check(LootConfigScreen.shouldPreserveDraft(later, later, later, List.of("-"), originalRaw, true),
                "retaining B's baseline through ACK A protects a still-newer incomplete edit from ACK B");
        check(LootConfigScreen.shouldPreserveDraft(later, null, submitted, List.of("-"), null, true),
                "an unsolicited or duplicate success cannot clear an unsaved draft after its baseline was released");
        check(!LootConfigScreen.shouldPreserveDraft(later, null, submitted, originalRaw, null, false),
                "an idle clean page may receive a successful refresh without inventing a conflict");
        TierProfile veryLow = new TierProfile(List.of(new Entry("gun", "test:rare", 2, 3, .25)), submitted.categoryProbabilities());
        Document independent = submitted.withTier(LootTier.VERY_LOW, veryLow);
        Document lowView = LootConfigScreen.viewTier(independent, LootTier.VERY_LOW);
        check(lowView.items().equals(veryLow.items()), "selected tier exposes its own complete entry quantities and weights");
        check(lowView.tier(LootTier.HIGH).items().equals(submitted.items()), "viewing a lower tier preserves the independently saved high tier");
        Document normalized = LootConfigScreen.normalizeDocument(lowView);
        check(normalized.items().equals(submitted.items()) && normalized.tier(LootTier.VERY_LOW).equals(veryLow), "server submission restores high compatibility fields while preserving every tier");
        check(LootConfigScreen.viewTier(normalized, LootTier.VERY_LOW).equals(lowView), "save acknowledgement returns to the same selected tier without overwriting its entries");
        System.out.println("LootConfigNativeChecks: " + checks
                + " viewport, four-tab, inline-plan and delayed-acknowledgement checks passed; no game launched.");
    }

    /** Verify the real navigation wiring without initializing client screens or sending packets. */
    private static void otherSettingsNavigation() throws Exception {
        var root = node("com/example/examplemod/lootconfig/client/LootConfigScreen");
        var init = method(root, "init");
        check(java.util.Arrays.stream(init.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
                        && call.owner.equals(root.name) && call.name.equals("fixedButton")).count() == 2,
                "footer regained a duplicate Other Settings button");
        var labels = java.util.Arrays.stream(method(root, "initExperiments").instructions.toArray())
                .filter(LdcInsnNode.class::isInstance).map(i -> ((LdcInsnNode) i).cst).toList();
        for (String key : List.of("screen.tacz_pubg.inventory.settings", "screen.tacz_pubg.hud_layout.open",
                "screen.tacz_pubg.model_scale.title", "screen.tacz_pubg.loot_config.operator_permissions",
                "screen.tacz_pubg.battle_settings.title"))
            check(labels.contains(key), "top Other Settings scroll page lost an existing or merged entry: " + key);
        for (String[] target : new String[][] {
                {"editModelScales", "com/example/examplemod/modelscale/client/ModelScaleSettingsScreen"},
                {"editBattleSettings", "com/example/examplemod/pubgsettings/client/PubgSettingsScreen"}}) {
            var open = method(root, target[0]);
            check(calls(open, root.name, "rememberScroll") && !calls(open, root.name, "applyForms"),
                    "independent child navigation parses or loses an unfinished root draft");
            check(java.util.Arrays.stream(open.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call
                            && call.owner.equals(target[1]) && call.name.equals("<init>")
                            && call.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;)V")
                            && previousInstruction(i) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0),
                    "a merged child no longer returns to the same root authoring session");
            check(java.util.Arrays.stream(open.instructions.toArray()).noneMatch(i -> i instanceof TypeInsnNode type
                            && type.getOpcode() == Opcodes.NEW && type.desc.equals(root.name)),
                    "child navigation constructs a fresh root and drops raw fields");
        }
        check(!calls(method(root, "editOperatorPermissions"), root.name, "applyForms"),
                "permission entry blocks or parses unfinished root fields");
        var permissions = node("com/example/examplemod/lootconfig/client/OperatorPermissionsScreen");
        check(calls(method(permissions, "commit"), root.name, "saveConfiguration"),
                "merged permission editor lost the original acknowledged root-save behavior");
    }
    private static org.objectweb.asm.tree.AbstractInsnNode previousInstruction(org.objectweb.asm.tree.AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static ClassNode node(String name) throws Exception {
        try (var input = LootConfigNativeChecks.class.getResourceAsStream("/" + name + ".class")) {
            check(input != null, "missing compiled navigation class: " + name);
            var node = new ClassNode(); new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); return node;
        }
    }
    private static MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static boolean calls(MethodNode method, String owner, String name) {
        return java.util.Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name));
    }

    private static void checkLayout(int width, int height, boolean advanced, boolean gunClasses) {
        var layout = LootConfigScreen.layoutFor(width, height, advanced, gunClasses);
        check(layout.top() == ConfigUi.CONTENT_TOP && layout.top() >= ConfigUi.TABS_Y + ConfigUi.TABS_HEIGHT + 4,
                "the viewport clears the shared four-tab chrome");
        check(layout.bottom() <= height - 46 && layout.bottom() < height - 38, "the viewport clears status and save controls");
        check(layout.viewportHeight() >= 130, "minimum supported GUI retains a scrollable viewport");
        check(layout.leftWidth() >= 104 && layout.rightWidth() >= 170, "neither pane obtains a negative or unusable width");
        check(10 + layout.leftWidth() < layout.right(), "the independent list and detail panes do not overlap");
        check(layout.right() + layout.rightWidth() <= width - 14, "detail controls clear the scrollbar");
        List<Rect> controls = new ArrayList<>();
        int tierWidth = (width - 36) / 5;
        for (int tier = 0; tier < 5; tier++) controls.add(new Rect(10 + tier * (tierWidth + 4), 0, tierWidth, 20));
        Rect totalBounds = new Rect(10, LootConfigScreen.PROBABILITY_TOTAL_Y, width - 20, 9);
        for (Rect tier : controls)
            check(tier.y() + tier.height() + 4 <= totalBounds.y(), "category total has its own row below every tier selector");
        check(totalBounds.y() + totalBounds.height() + 4 <= LootConfigScreen.POOL_CONTROLS_Y,
                "the complete total text clears the category heading and actions at all GUI widths");
        check(LootConfigScreen.POOL_CONTROLS_Y + 20 + 4 <= LootConfigScreen.CATEGORY_GRID_Y,
                "category actions clear the first probability input row");
        controls.add(new Rect(width - 140, LootConfigScreen.POOL_CONTROLS_Y, 58, 20));
        for (int index = 0; index < 10; index++) {
            int x = 10 + index % layout.columns() * (layout.cellWidth() + 4), y = LootConfigScreen.CATEGORY_GRID_Y + index / layout.columns() * 47;
            controls.add(new Rect(x, y, layout.cellWidth(), 20));
            controls.add(new Rect(x, y + 22, layout.cellWidth() - 8, 20));
            check(y + 42 < layout.generalY(), "all ten category inputs precede the general probabilities");
        }
        if (gunClasses) {
            int classColumns = width >= 600 ? 9 : 3, span = (width - 24) / classColumns;
            int classTop = layout.generalY() - 54 - (width >= 600 ? 1 : 3) * 43;
            controls.add(new Rect(width - 142, classTop, 124, 20));
            for (int index = 0; index < 9; index++) {
                int y = classTop + 30 + index / classColumns * 43;
                controls.add(new Rect(10 + index % classColumns * span, y + 14, span - 8, 20));
                check(y + 34 < layout.generalY(), "all nine gun-class inputs precede general settings");
            }
        }
        int generalRows = (3 + layout.generalColumns() - 1) / layout.generalColumns();
        for (int index = 0; index < 3; index++) {
            int span = (width - 24) / layout.generalColumns(), fieldWidth = Math.min(70, span / 3);
            int x = 10 + index % layout.generalColumns() * span, y = layout.generalY() + 16 + index / layout.generalColumns() * 26;
            controls.add(new Rect(x + span - fieldWidth - 6, y, fieldWidth, 20));
        }
        check(layout.generalY() + 16 + (generalRows - 1) * 26 + 20 < layout.advancedY(),
                "general probabilities do not collide with the advanced-settings disclosure");
        if (advanced) {
            int cols = width >= 600 ? 2 : 1, rows = (7 + cols - 1) / cols;
            check(layout.advancedY() + 30 + rows * 29 + 20 < layout.bodyY(),
                    "expanded density, spacing and auxiliary-screen buttons clear the item browser");
            for (int index = 0; index < 7; index++) {
                int span = (width - 24) / cols, fieldWidth = Math.min(100, span / 3);
                controls.add(new Rect(10 + index % cols * span + span - fieldWidth - 8,
                        layout.advancedY() + 30 + index / cols * 29, fieldWidth, 20));
            }
        } else check(layout.advancedY() + 20 < layout.bodyY(), "collapsed disclosure clears the browser");
        controls.add(new Rect(10, layout.bodyY() + 16, layout.leftWidth(), 20));
        controls.add(new Rect(10, layout.bodyY() + 214, layout.leftWidth(), 20));
        controls.add(new Rect(layout.right() + layout.rightWidth() - Math.min(105, Math.max(54, layout.rightWidth() / 3)),
                layout.bodyY() + 60, Math.min(105, Math.max(54, layout.rightWidth() / 3)), 20));
        if (layout.inlineAmmo()) {
            int right = layout.right(), start = layout.bodyY() + 116, probabilityX = right + layout.rightWidth() - 108;
            int groupsX = probabilityX - 46, selfX = groupsX - 40;
            check(selfX - right - 24 >= 60, "inline ammo picker retains a usable name width");
            check(right + 80 + 62 < right + layout.rightWidth() - 105, "round count and restore-default button do not overlap");
            for (int index = 0; index < 3; index++) {
                int y = start + 70 + index * 26;
                controls.add(new Rect(right + 18, y, selfX - right - 24, 20));
                controls.add(new Rect(selfX, y, 34, 20));
                controls.add(new Rect(groupsX, y, 40, 20));
                controls.add(new Rect(probabilityX, y, 52, 20));
            }
        } else controls.add(new Rect(layout.right(), layout.bodyY() + 116, layout.rightWidth(), 20));
        int contentHeight = layout.bodyY() + (layout.inlineAmmo() ? 348 : 247);
        double maxScroll = Math.max(0, contentHeight - layout.viewportHeight());
        for (Rect control : controls) {
            check(control.width() >= 20 && control.x() >= 8 && control.x() + control.width() <= width - 10,
                    "native controls stay inside horizontal viewport bounds");
            double witness = Math.clamp(control.y() - (layout.viewportHeight() - control.height()) / 2.0, 0, maxScroll);
            check(layout.fullyVisible(control.y(), control.height(), witness), "every control has a reachable fully visible scroll position");
            int translatedY = layout.top() + control.y() - (int) witness;
            check(layout.contains(control.x() + control.width() / 2.0, translatedY + 10),
                    "a reachable control's hit target is inside the same scissor as its rendering");
        }
        check(!layout.contains(20, ConfigUi.TABS_Y + 5) && !layout.contains(20, height - 20),
                "translated content cannot consume header or footer clicks");
        check(!layout.intersects(-30, 20, 0), "a fully hidden control above the viewport is not rendered");
        check(!layout.intersects(layout.viewportHeight() + 1, 20, 0), "a fully hidden control below the viewport is not rendered");
        check(layout.intersects(0, 20, 10) && !layout.fullyVisible(0, 20, 10),
                "partially clipped controls can be drawn but are ineligible for input focus");
    }
}
