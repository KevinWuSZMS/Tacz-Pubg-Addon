package com.example.examplemod.vitals;

import com.example.examplemod.vitals.client.ThrowableHudIcons;
import net.minecraft.resources.ResourceLocation;

/** Checks shared side-stack/feed identity mapping and the packaged source silhouettes, without a client. */
public final class ThrowableHudIconChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        var names = new String[]{"frag_grenade", "stun_grenade", "smoke_grenade", "molotov",
                "sticky_bomb", "c4", "blue_zone_grenade"};
        var textures = new java.util.HashSet<ResourceLocation>();
        for (var name : names) {
            var texture = ThrowableHudIcons.texture(id("tacz_pubg:" + name), null);
            check(texture != null && texture.getPath().endsWith("/" + name + ".png"),
                    "Every PUBG throwable selects its own existing silhouette");
            check(textures.add(texture), "The seven PUBG throwable silhouettes remain distinct");
            try (var input = ThrowableHudIconChecks.class.getResourceAsStream("/assets/" + texture.getNamespace() + "/" + texture.getPath())) {
                check(input != null, "Every shared silhouette is packaged");
                var image = javax.imageio.ImageIO.read(input);
                check(image != null && image.getWidth() == image.getHeight() && image.getColorModel().hasAlpha(),
                        "Shared source silhouettes retain square alpha canvases");
            }
        }
        check(same("tacz_pubg:blue_zone_grenade", "lrtactical:m67", "blue_zone_grenade"),
                "A blue-zone grenade sharing the frag animation does not become a frag icon");
        check(same("tacz_pubg:sticky_bomb", "lrtactical:rgn", "sticky_bomb"),
                "A sticky bomb sharing the RGN animation keeps the sticky silhouette");
        check(same("tacz_pubg:frag_grenade", "lrtactical:smoke_grenade", "frag_grenade"),
                "Stable PUBG item identity wins over a conflicting display alias");
        for (var alias : new String[][]{{"m67", "frag_grenade"}, {"rgn", "frag_grenade"},
                {"flash_grenade", "stun_grenade"}, {"smoke_grenade", "smoke_grenade"},
                {"molotov", "molotov"}, {"c4", "c4"}}) {
            check(same("lrtactical:throwable", "lrtactical:" + alias[0], alias[1]),
                    "Native LR stacks sharing one item resolve through their individual asset identity");
            check(same("lrtactical:" + alias[0], null, alias[1]),
                    "Kill-feed identities retain their existing native LR alias mapping");
        }
        check(ThrowableHudIcons.texture(id("lrtactical:throwable"), id("third_party:unknown")) == null,
                "An unknown native throwable falls back to its own item icon");
        check(ThrowableHudIcons.texture(id("minecraft:iron_sword"), null) == null,
                "Melee items with no silhouette are not assigned a fabricated weapon picture");
        check(ThrowableHudIcons.texture(null, null) == null, "A missing identity remains absent");
        System.out.println("Throwable HUD icon checks: " + checks + " passed; no game launched.");
    }

    private static boolean same(String item, String asset, String name) {
        return id("tacz_pubg:textures/gui/hud/kill_feed/throwables/" + name + ".png")
                .equals(ThrowableHudIcons.texture(id(item), asset == null ? null : id(asset)));
    }

    private static ResourceLocation id(String value) { return ResourceLocation.parse(value); }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
