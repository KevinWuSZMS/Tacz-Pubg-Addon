package com.example.examplemod.operatorpermissions;

import com.example.examplemod.localization.Texts;

/** Extra PUBG privileges for a real server-side permission-level-2 operator. */
public record OperatorPermissions(boolean damageBots, boolean damagePlayers, boolean bypassFriendlyFire,
        boolean bypassPreparationProtection, boolean immuneBlueZone, boolean keepGameMode) {
    public static OperatorPermissions defaults() {
        return new OperatorPermissions(true, false, false, true, false, false);
    }

    public enum Permission {
        DAMAGE_BOTS("damageBots", "pubgOpDamageBots", Texts.tr("message.tacz_pubg.operator_permissions.damage_match_bots_outside"), Texts.tr("message.tacz_pubg.operator_permissions.allow_nonparticipating_eliminated_ops_damage_bots_managed_by")),
        DAMAGE_PLAYERS("damagePlayers", "pubgOpDamagePlayers", Texts.tr("message.tacz_pubg.operator_permissions.damage_human_participants_outside"), Texts.tr("message.tacz_pubg.operator_permissions.allow_nonparticipating_eliminated_ops_damage_human_participants")),
        BYPASS_FRIENDLY_FIRE("bypassFriendlyFire", "pubgOpBypassFriendlyFire", Texts.tr("message.tacz_pubg.operator_permissions.bypass_friendly_fire_protection"), Texts.tr("message.tacz_pubg.operator_permissions.allow_ops_who_can_already_attack_target_damage")),
        BYPASS_PREPARATION_PROTECTION("bypassPreparationProtection", "pubgOpBypassPreparationProtection", Texts.tr("message.tacz_pubg.operator_permissions.bypass_preparation_protection"), Texts.tr("message.tacz_pubg.operator_permissions.allow_ops_who_can_already_attack_target_deal")),
        IMMUNE_BLUE_ZONE("immuneBlueZone", "pubgOpImmuneBlueZone", Texts.tr("message.tacz_pubg.operator_permissions.immune_blue_zone_damage"), Texts.tr("message.tacz_pubg.operator_permissions.participating_ops_take_no_pubg_blue_zone_damage")),
        KEEP_GAME_MODE("keepGameMode", "pubgOpKeepGameMode", Texts.tr("message.tacz_pubg.operator_permissions.keep_original_game_mode"), Texts.tr("message.tacz_pubg.operator_permissions.ops_keep_their_original_mode_count_as_participants"));

        private final String field, gameRule, label, description;
        Permission(String field, String gameRule, String label, String description) {
            this.field = field; this.gameRule = gameRule; this.label = label; this.description = description;
        }
        public String field() { return field; }
        public String gameRule() { return gameRule; }
        public String label() { return label; }
        public String description() { return description; }
    }

    public boolean value(Permission permission) {
        return switch (permission) {
            case DAMAGE_BOTS -> damageBots;
            case DAMAGE_PLAYERS -> damagePlayers;
            case BYPASS_FRIENDLY_FIRE -> bypassFriendlyFire;
            case BYPASS_PREPARATION_PROTECTION -> bypassPreparationProtection;
            case IMMUNE_BLUE_ZONE -> immuneBlueZone;
            case KEEP_GAME_MODE -> keepGameMode;
        };
    }

    public OperatorPermissions with(Permission permission, boolean value) {
        return new OperatorPermissions(permission == Permission.DAMAGE_BOTS ? value : damageBots,
                permission == Permission.DAMAGE_PLAYERS ? value : damagePlayers,
                permission == Permission.BYPASS_FRIENDLY_FIRE ? value : bypassFriendlyFire,
                permission == Permission.BYPASS_PREPARATION_PROTECTION ? value : bypassPreparationProtection,
                permission == Permission.IMMUNE_BLUE_ZONE ? value : immuneBlueZone,
                permission == Permission.KEEP_GAME_MODE ? value : keepGameMode);
    }
}
