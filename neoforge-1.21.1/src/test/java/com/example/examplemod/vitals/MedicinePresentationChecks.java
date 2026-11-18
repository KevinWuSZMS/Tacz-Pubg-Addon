package com.example.examplemod.vitals;

import com.example.examplemod.localization.TestLanguages;
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
    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
}
