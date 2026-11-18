package com.example.examplemod.vitals;

import com.example.examplemod.localization.TestLanguages;
import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.hud.client.InteractionPromptHud;
import com.example.examplemod.vitals.client.ActionCircleRenderer;
import com.example.examplemod.vitals.client.MedicineUseToast;
import com.example.examplemod.vitals.client.PubgMedicineHud;
import net.minecraft.locale.Language;

/** Screenshot geometry, complete localized phrases and viewport fitting; no game launched. */
public final class MedicinePresentationChecks {
    private static int checks;
    public static void main(String[] args) {
        var reference = ActionCircleRenderer.medicineLayout(56, 80, 6, 18, 9);
        check(reference.labelX() - 57 == 224 && reference.labelY() - 57 == 59,
                "Status text sits at reference offset from circle centre");
        check(reference.keyX() - 57 == 174 && reference.keyY() - 57 == 101 && reference.keyHeight() == 42,
                "Cancel key follows the reference's separate lower row");
        interactionAnchors();
        for (int width : new int[]{160, 320, 640, 1280}) for (int height : new int[]{120, 240, 400, 800})
            for (int radius : new int[]{12, 20, 50}) for (int offset : new int[]{-120, 0, 120})
                for (int keyWidth : new int[]{6, 110}) {
                    var layout = ActionCircleRenderer.medicineLayout(radius, 180, keyWidth, 40, 9);
                    var bounds = ActionCircleRenderer.medicineBounds(width, height, offset, layout);
                    check(bounds.x() >= 0 && bounds.y() >= 0 && bounds.x() + bounds.width() <= width
                            && bounds.y() + bounds.height() <= height, "Entire medicine HUD stays in viewport");
                    check(layout.labelY() > radius * 2 && layout.keyY() > layout.labelY(),
                            "Status and cancel remain below the ring at every radius");
                }
        Language.inject(TestLanguages.language(TestLanguages.catalog("zh_cn")));
        String[] actions = {"正在包扎伤口", "正在使用急救包", "正在使用医疗箱", "正在饮用能量饮料", "正在服用止痛药", "正在注射肾上腺素"};
        String[] used = {"使用了绷带", "使用了急救包", "使用了医疗箱", "使用了能量饮料", "使用了止痛药", "使用了肾上腺素注射器"};
        var kinds = PubgMedicineModel.Kind.values();
        for (int i = 0; i < kinds.length; i++) {
            check(PubgMedicineHud.actionLabel(kinds[i]).getString().equals(actions[i]), "Exact screenshot action phrase for " + kinds[i]);
            check(MedicineUseToast.label(kinds[i]).getString().equals(used[i]), "Exact screenshot completion phrase for " + kinds[i]);
        }
        check(MedicineUseToast.labelInterrupted().getString().equals("物品使用被中断"), "Exact interruption phrase shares the successful-use presentation");
        check(MedicineUseToast.labelInterrupted().getStyle().isBold() == MedicineUseToast.label(PubgMedicineModel.Kind.BANDAGE).getStyle().isBold(),
                "Interruption and completion use the same text emphasis");
        Language.inject(TestLanguages.language(TestLanguages.catalog("en_us")));
        for (var kind : kinds) {
            check(!PubgMedicineHud.actionLabel(kind).getString().contains("tacz_pubg"), "English action resolves");
            check(MedicineUseToast.label(kind).getString().startsWith("Used "), "English completion resolves");
        }
        check(!MedicineUseToast.labelInterrupted().getString().contains("tacz_pubg"), "English interruption resolves");
        System.out.println("MedicinePresentationChecks: " + checks + " checks passed; no game launched.");
    }
    private static void interactionAnchors() {
        // The supplied reference places the F box at (1454, 900) in a 2560 x 1600 frame.
        var reference = ActionCircleRenderer.medicineLayout(56, 80, 6, 18, 9);
        var referenceBounds = ActionCircleRenderer.medicineBounds(2560, 1600, 0, reference);
        var referenceKey = InteractionPromptHud.anchor(referenceBounds, reference);
        check(Math.abs(referenceKey.x() - 1454) <= 1 && Math.abs(referenceKey.y() - 900) <= 1,
                "Shared interaction key matches the supplied medicine-cancel reference");

        // Text extends to the right of the key; different medicine names or bindings may not
        // recenter the row when there is room for it.
        for (int radius : new int[]{12, 20, 50}) {
            var compact = ActionCircleRenderer.medicineLayout(radius, 60, 6, 18, 9);
            var expected = InteractionPromptHud.anchor(ActionCircleRenderer.medicineBounds(4096, 2160, 0, compact), compact);
            for (int labelWidth : new int[]{60, 180, 400}) for (int keyWidth : new int[]{6, 110}) {
                var layout = ActionCircleRenderer.medicineLayout(radius, labelWidth, keyWidth, 40, 9);
                var anchor = InteractionPromptHud.anchor(ActionCircleRenderer.medicineBounds(4096, 2160, 0, layout), layout);
                check(anchor.x() == expected.x() && anchor.y() == expected.y(),
                        "Long action labels and rebound key names keep the same key origin");
                check(anchor.keyHeight() == expected.keyHeight() && anchor.textScale() == expected.textScale(),
                        "Long labels do not change the interaction key size");
            }
        }

        for (int width : new int[]{160, 320, 640, 1280}) for (int height : new int[]{120, 240, 400, 800})
            for (int radius : new int[]{12, 20, 50}) {
                var layout = ActionCircleRenderer.medicineLayout(radius, 180, 110, 40, 9);
                var fallback = ActionCircleRenderer.medicineBounds(width, height, 0, layout);
                var original = InteractionPromptHud.anchor(fallback, layout);
                check(original.x() >= 0 && original.x() < width && original.y() >= 0
                                && original.y() + original.keyHeight() <= height,
                        "Interaction key remains visible when the medicine HUD shrinks to fit");
                for (double x : new double[]{0, .5, 1}) for (double y : new double[]{0, .5, 1}) {
                    var placed = HudLayoutModel.resolve(new HudLayoutModel.Position(x, y), width, height, fallback);
                    var key = InteractionPromptHud.anchor(placed, layout);
                    check(key.x() - original.x() == placed.x() - fallback.x()
                                    && key.y() - original.y() == placed.y() - fallback.y(),
                            "A saved medicine HUD move shifts the shared action key by exactly the same amount");
                    check(key.keyHeight() == original.keyHeight() && key.textScale() == original.textScale(),
                            "Moving a saved HUD cannot change key size or text scale");
                    var savedAgain = HudLayoutModel.normalize(placed.x(), placed.y(), width, height, placed.width(), placed.height());
                    var restored = HudLayoutModel.resolve(savedAgain, width, height, fallback);
                    check(InteractionPromptHud.anchor(restored, layout).equals(key),
                            "Saving and restoring the moved HUD preserves the interaction key position");
                }
            }
    }
    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
}
