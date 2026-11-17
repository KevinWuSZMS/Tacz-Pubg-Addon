package com.example.examplemod.backpack;

/**
 * Loose PUBG-equivalent attachments, in thousandths of one inventory-space unit.
 * Source: https://pubg.gs/attachments/ (44.1, build 26563649, 2026-11-11).
 * IDs are verified against the installed CBRG and workbench attachment indices;
 * unrelated packs retain the existing fallback until their equivalence is known.
 */
public final class PubgAttachmentWeights {
    private PubgAttachmentWeights() {}

    public static int unit(String attachmentId) {
        if (attachmentId == null) return 10_000;
        return switch (attachmentId) {
            case "cbrg:2x_scope", "cbrg:3x_scope", "cbrg:4x_scope" -> 15_000;
            case "cbrg:6x_scope", "cbrg:8x_scope", "cbrg:15x_scope" -> 20_000;
            // Despite its asset ID, CBRG's pistol suppressor is the shared Handgun/SMG item.
            case "cbrg:suppressor_pistol" -> 15_000;
            case "cbrg:suppressor_rifle" -> 20_000;
            case "cbrg:suppressor_sr" -> 23_000;
            case "cbrg:cheek_pad" -> 17_000;
            case "cbrg:quickdraw_mag_rifle", "tacz_pubg_workbench:quickdraw_mag_rifle" -> 14_000;
            case "cbrg:extended_mag_rifle", "tacz_pubg_workbench:extended_mag_rifle" -> 15_000;
            case "cbrg:extended_quickdraw_mag_rifle",
                    "tacz_pubg_workbench:extended_quickdraw_mag_rifle" -> 20_000;
            case "tacz_pubg_workbench:quickdraw_mag_smg" -> 12_000;
            case "tacz_pubg_workbench:extended_mag_smg" -> 13_000;
            case "tacz_pubg_workbench:extended_quickdraw_mag_smg" -> 17_000;
            case "tacz_pubg_workbench:quickdraw_mag_sniper" -> 16_000;
            case "tacz_pubg_workbench:extended_mag_sniper" -> 17_000;
            case "tacz_pubg_workbench:extended_quickdraw_mag_sniper" -> 23_000;
            // Known red-dot/holographic/canted sights, grips, lasers and rifle brakes cost 10.
            // The same conservative fallback applies to unclassified third-party attachments.
            default -> 10_000;
        };
    }
}
