package com.example.examplemod.backpack;

/** Offline capacity scenarios: mixed spare attachments and namespace isolation. */
public final class PubgAttachmentWeightsChecks {
    public static void main(String[] args) {
        // A sniper spare kit: 8x + SR suppressor + cheek pad + extended quickdraw = 83.
        int sniperKit = PubgAttachmentWeights.unit("cbrg:8x_scope")
                + PubgAttachmentWeights.unit("cbrg:suppressor_sr")
                + PubgAttachmentWeights.unit("cbrg:cheek_pad")
                + PubgAttachmentWeights.unit("tacz_pubg_workbench:extended_quickdraw_mag_sniper");
        check(sniperKit == 83_000, "sniper spare kit must consume 83 capacity");
        check(BackpackRules.accepted(287_000, 370_000, sniperKit, 2) == 1,
                "one spare kit fits an exact remaining 83 capacity");
        check(BackpackRules.accepted(287_001, 370_000, sniperKit, 1) == 0,
                "a spare kit cannot overfill by one thousandth");

        // Switching only the magazine source pack must not change the capacity outcome.
        for (String variant : new String[]{"quickdraw", "extended", "extended_quickdraw"}) {
            check(PubgAttachmentWeights.unit("cbrg:" + variant + "_mag_rifle")
                            == PubgAttachmentWeights.unit("tacz_pubg_workbench:" + variant + "_mag_rifle"),
                    "native/workbench rifle magazines must agree: " + variant);
        }
        check(PubgAttachmentWeights.unit("cbrg:suppressor_pistol") == 15_000,
                "CBRG pistol ID is the shared Handgun/SMG suppressor");
        check(PubgAttachmentWeights.unit("other:8x_scope") == 10_000,
                "matching paths in unrelated packs must not inherit PUBG equivalence");
        check(PubgAttachmentWeights.unit("cbrg:future_8x_scope") == 10_000,
                "unregistered variants retain the conservative fallback");
        check(PubgAttachmentWeights.unit("tacz:extended_mag_3") == 10_000,
                "generic magazine level cannot identify a PUBG weapon family");
        System.out.println("PUBG attachment capacity scenarios passed");
    }

    private static void check(boolean result, String reason) {
        if (!result) throw new AssertionError(reason);
    }
}
