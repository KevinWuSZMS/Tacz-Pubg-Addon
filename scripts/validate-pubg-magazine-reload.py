"""Check the workbench reload implementation without launching Minecraft."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
JDK = Path("C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot/bin")
TACZ = ROOT / "neoforge-1.21.1/run/mods/tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar"
ADDON = ROOT / "neoforge-1.21.1/build/libs/tacz_pubg-1.0.0.jar"
OUT = ROOT / "logs/pubg-magazine-reload-validation.json"
TARGETS = {
    "com.tacz.guns.util.AttachmentDataUtils": {"getMagExtendLevel": "(Lnet/minecraft/world/item/ItemStack;Lcom/tacz/guns/resource/pojo/data/gun/GunData;)I"},
    "com.tacz.guns.api.entity.IGunOperator": {"cancelReload": "()V", "getDataHolder": "()Lcom/tacz/guns/entity/shooter/ShooterDataHolder;"},
    "com.tacz.guns.entity.shooter.ShooterDataHolder": {"initialData": "()V"},
    "com.tacz.guns.item.ModernKineticGunScriptAPI": {"getReloadTime": "()J", "adjustReloadTime": "(J)V"},
    "com.tacz.guns.item.ModernKineticGunItem": {
        "startReload": "(Lcom/tacz/guns/entity/shooter/ShooterDataHolder;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)Z",
        "tickReload": "(Lcom/tacz/guns/entity/shooter/ShooterDataHolder;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)Lcom/tacz/guns/api/entity/ReloadState;"},
    "com.tacz.guns.client.gameplay.LocalPlayerReload": {"doReload": "(Lcom/tacz/guns/api/item/IGun;Lcom/tacz/guns/client/resource/GunDisplayInstance;Lcom/tacz/guns/resource/pojo/data/gun/GunData;Lnet/minecraft/world/item/ItemStack;)V", "cancelReload": "()V"},
    "com.tacz.guns.client.animation.statemachine.GunAnimationStateContext": {"getStateMachineParams": "()Lorg/luaj/vm2/LuaTable;"},
    "com.tacz.guns.api.client.animation.statemachine.AnimationStateContext": {"runAnimation": "(Ljava/lang/String;IZIF)V"},
    "com.tacz.guns.api.client.animation.ObjectAnimationRunner": {"updateProgress": "(J)V", "update": "(Z)V", "updateSoundOnly": "()V"},
    "com.tacz.guns.client.sound.SoundPlayManager": {
        "playReloadSound": "(Lnet/minecraft/world/entity/LivingEntity;Lcom/tacz/guns/client/resource/GunDisplayInstance;Z)V",
        "playAnimationSound": "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceLocation;FFI)Lcom/tacz/guns/client/sound/GunSoundInstance;"},
}
MIXINS = ["AttachmentMagazineExperimentMixin", "ShooterMagazineReloadRateMixin", "ModernGunMagazineReloadMixin", "ModernGunMagazineReloadClockMixin",
          "LocalPlayerMagazineReloadMixin", "GunAnimationMagazineReloadMixin", "AnimationContextMagazineReloadMixin",
          "ObjectAnimationRunnerMagazineReloadMixin", "SoundPlayManagerMagazineReloadMixin"]


def run(args):
    result = subprocess.run([str(x) for x in args], cwd=ROOT, encoding="utf-8", errors="replace", capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    source_root = ROOT / "neoforge-1.21.1/src/main/java/com/example/examplemod/gunbalance"
    assert ADDON.is_file(), "Build the addon before validation"
    for source in source_root.rglob("*.java"):
        assert source.stat().st_mtime <= ADDON.stat().st_mtime, f"Addon JAR predates integration source: {source.name}"
    checked = []
    blocks = {}
    for class_name, methods in TARGETS.items():
        bytecode = run([JDK / "javap.exe", "-p", "-s", "-c", "-classpath", TACZ, class_name])
        declarations = list(re.finditer(r"(?m)^  [^\n]+\b(\w+)\([^\n]*\);\r?\n    descriptor: ([^\r\n]+)", bytecode))
        found = {}
        for i, match in enumerate(declarations):
            name, descriptor = match.group(1), match.group(2)
            found.setdefault(name, set()).add(descriptor)
            blocks[class_name, name, descriptor] = bytecode[match.start():declarations[i + 1].start() if i + 1 < len(declarations) else len(bytecode)]
        for name, descriptor in methods.items():
            assert descriptor in found.get(name, set()), f"Missing r7 target {class_name}.{name}{descriptor}"
            checked.append({"class": class_name, "method": name, "descriptor": descriptor})
    runner = "com.tacz.guns.api.client.animation.ObjectAnimationRunner"
    audio_target = "ObjectAnimationSoundChannel.playSound:(DDLnet/minecraft/world/entity/Entity;IFF)V"
    runner_calls = {method: blocks[runner, method, descriptor].count(audio_target) for method, descriptor in [("update", "(Z)V"), ("updateSoundOnly", "()V")]}
    assert runner_calls == {"update": 1, "updateSoundOnly": 1}, runner_calls
    sound_class = "com.tacz.guns.client.sound.SoundPlayManager"
    sound_descriptor = TARGETS[sound_class]["playReloadSound"]
    whole_calls = blocks[sound_class, "playReloadSound", sound_descriptor].count("playClientSound:(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceLocation;FFI)Lcom/tacz/guns/client/sound/GunSoundInstance;")
    assert whole_calls == 2, whole_calls
    with zipfile.ZipFile(ADDON) as archive:
        config = json.loads(archive.read("tacz_pubg.gunbalance.mixins.json"))
        configured = config["mixins"] + config["client"]
        assert all(name in configured and f"com/example/examplemod/gunbalance/mixin/{name}.class" in archive.namelist() for name in MIXINS)
        assert not any(name.startswith("org/luaj/") for name in archive.namelist()), "Compile-only Lua runtime must not be bundled"
    with tempfile.TemporaryDirectory(prefix="pubg-magazine-timing-") as temp:
        run([JDK / "javac.exe", "-d", temp, source_root / "reload/MagazineReloadTiming.java", ROOT / "scripts/validation/MagazineReloadTimingTest.java"])
        timing = run([JDK / "java.exe", "-cp", temp, "com.example.examplemod.gunbalance.reload.MagazineReloadTimingTest"])
    result = {"status": "passed", "runtime_tested": False, "game_launched": False, "installed": False,
              "tacz_jar_sha256": digest(TACZ), "addon_jar": ADDON.relative_to(ROOT).as_posix(), "addon_jar_sha256": digest(ADDON),
              "mixin_target_descriptors": checked, "registered_reload_mixins": MIXINS,
              "keyframe_sound_call_sites": runner_calls, "whole_reload_sound_call_sites": whole_calls,
              "timing_check": timing, "lua_runtime_bundled": False,
              "magazine_control": "Server world gunSettings.magazine, confirmed protocol-2 state and native marker/slot/matcher; no client override.",
              "disable_behavior": "Cancel the active six-ID accelerated reload before withdrawing its clock; clear native holder, client runners and tracked reload sounds. No feed replay or gun/inventory deletion.",
              "validation_boundary": "Compilation, exact installed r7 bytecode descriptors/call sites and standalone timing regression; not a live Mixin/game test."}
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
