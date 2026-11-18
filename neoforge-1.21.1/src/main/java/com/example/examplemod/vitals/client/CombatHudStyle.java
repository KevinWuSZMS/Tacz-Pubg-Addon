package com.example.examplemod.vitals.client;

import com.example.examplemod.pubgsettings.PubgSettingsService;

public final class CombatHudStyle {
    private CombatHudStyle() {}
    public static PubgVitalsLayout.Style vitals() {
        var s = PubgSettingsService.client();
        return new PubgVitalsLayout.Style(width(s), s.integer("hud.health_height"), s.integer("hud.boost_height"),
                s.integer("hud.bar_gap"), s.integer("hud.bottom_margin"), s.integer("hud.row_height"),
                s.number("hud.health_red_threshold"), s.number("hud.health_apricot_threshold"), s.color("hud.health_red"),
                s.color("hud.health_apricot"), s.color("hud.health_white"), s.color("hud.health_full"),
                s.color("hud.health_reference"), s.color("hud.health_treatment"), s.color("hud.health_next_step"), s.color("hud.boost_color"), s.color("hud.panel_color"),
                s.color("hud.health_critical_red"), s.integer("hud.health_flash_ticks"));
    }
    public static int width(com.example.examplemod.pubgsettings.PubgSettings settings) {
        return settings.enabled("hud.align_hotbar") ? PubgVitalsLayout.WIDTH : settings.integer("hud.bar_width");
    }
    public static PubgVitalsLayout.Frame frame(int width, int height) { return PubgVitalsLayout.frame(width, height, vitals()); }
}
