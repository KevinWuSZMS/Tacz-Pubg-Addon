package com.example.examplemod.vitals.client;

import com.example.examplemod.inventory.client.PubgInventoryInput;
import com.example.examplemod.throwables.client.ThrowableClient;
import net.minecraft.client.gui.GuiGraphics;

/** Only ongoing actions and their results move above the inventory's player model. */
public final class InventoryActionHud {
    private InventoryActionHud() { }

    public static boolean usesInventoryForeground() {
        return PubgInventoryInput.activeScreen();
    }

    /** Call once after the inventory restores its view scale and before its tooltip pass. */
    public static void renderForeground(GuiGraphics graphics) {
        if (!usesInventoryForeground()) return;
        graphics.flush();
        graphics.pose().pushPose();
        try {
            // Vanilla inventory entities write depth; later submission alone does not put HUD above them.
            graphics.pose().translate(0, 0, 1000);
            PubgMedicineHud.renderContents(graphics);
            ThrowableClient.renderInventoryStatus(graphics);
            MedicineUseToast.renderContents(graphics);
            graphics.flush();
        } finally { graphics.pose().popPose(); }
    }
}
