package com.example.examplemod.hud.client;

import java.nio.file.Files;
import java.util.Map;

/** Uses temporary files only; does not read or write the user's game configuration. */
public final class HudLayoutMigrationChecks {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("pubg-hud-v2-");
        var file = directory.resolve("tacz_pubg-hud.json");
        int checks = 0;
        try {
            var fresh = HudLayoutStore.loadForClient(file);
            check(fresh.positions().isEmpty() && fresh.nativeMapResetPending(), "A native-only install did not request reset"); checks++;
            check(HudLayoutStore.loadForClient(file).equals(fresh), "Pending native reset did not survive reload"); checks++;
            String legacy = "{\"version\":1,\"positions\":{\"vitals\":{\"x\":0.2,\"y\":0.9},"
                    + "\"xaero_minimap\":{\"x\":0,\"y\":0},\"match_phase\":{\"x\":0.8,\"y\":0.8},"
                    + "\"weapon_stack\":{\"x\":1,\"y\":0}}}";
            Files.writeString(file, legacy);
            var migrated = HudLayoutStore.loadForClient(file);
            check(migrated.positions().isEmpty() && migrated.nativeMapResetPending(), "Legacy positions survived the explicit reset"); checks++;
            try (var files = Files.list(directory)) {
                var backup = files.filter(path -> path.getFileName().toString().contains(".pre-v2.")).toList();
                check(backup.size() == 1 && Files.readString(backup.getFirst()).equals(legacy), "Legacy backup lost exact original bytes"); checks++;
            }
            var personal = Map.of("vitals", new HudLayoutModel.Position(.17, .42), "xaero_minimap", new HudLayoutModel.Position(.72, .88),
                    "match_phase", new HudLayoutModel.Position(0, 0), "weapon_stack", new HudLayoutModel.Position(1, 0));
            HudLayoutStore.write(file, new HudLayoutStore.Layout(personal, false));
            var afterReset = HudLayoutStore.loadForClient(file);
            check(!afterReset.nativeMapResetPending() && afterReset.positions().size() == 2
                    && afterReset.positions().get("xaero_minimap").equals(personal.get("xaero_minimap")),
                    "After-reset personal edits were reset again or independent child offsets survived"); checks++;
            check(HudLayoutStore.loadForClient(file).equals(afterReset), "A second reload reset a v2 personal layout"); checks++;
            try (var files = Files.list(directory)) {
                check(files.filter(path -> path.getFileName().toString().contains(".pre-v2.")).count() == 1,
                        "A completed migration repeated its backup/reset"); checks++;
            }
            Files.writeString(file, "{\"positions\":{\"equipment\":{\"x\":0,\"y\":1}}}");
            check(HudLayoutStore.loadForClient(file).nativeMapResetPending(), "Unversioned legacy layout did not migrate"); checks++;
        } finally {
            try (var paths = Files.list(directory)) { for (var path : paths.toList()) Files.deleteIfExists(path); }
            Files.deleteIfExists(directory);
        }
        System.out.println("HudLayoutMigrationChecks: " + checks + " passed; only temporary files used.");
    }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
}
