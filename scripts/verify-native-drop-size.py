"""Audit native-size placement against pre-change measurements; never starts Minecraft."""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKUP = ROOT / 'logs/source-backups/2026-10-18-native-map-editor/native-drop-size'
JAVA = ROOT / 'neoforge-1.21.1/src/main/java/com/example/examplemod/lootspawn'
audit = json.loads((ROOT / 'logs/ground-loot-model-alignment-audit.json').read_text(encoding='utf-8'))
before = json.loads((BACKUP / 'ground-loot-model-alignment-audit.json').read_text(encoding='utf-8'))
profiles = json.loads((ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/ground_loot_footprints.json').read_text(encoding='utf-8'))
assert profiles['version'] == 4 and profiles['sizePolicy'] == 'native_ground_no_extra_scale'
old = {(row['category'], row['id']): row for row in before['rows']}
assert set(old) == {(row['category'], row['id']) for row in audit['rows']}
assert audit['errors'] == before['errors'], 'Native-size update introduced a missing asset'

measured = icons = 0
for row in audit['rows']:
    category, identifier = row['category'], row['id']
    actual = profiles[category][identifier]
    previous = old[(category, identifier)]
    if row['kind'] == 'icon_2d':
        native = .5 if category == 'attachments' else 1
        assert actual == [native, .025, native], (category, identifier, actual)
        icons += 1
        continue
    assert previous['kind'] == '3d_full', 'Unexpected old thin model needs explicit prior-scale adjustment'
    # Guns/ammo have identity baked GROUND transforms. Standalone attachments
    # natively have GROUND=0.5, previously absent only from the offline bake.
    native = .5 if category == 'attachments' else 1
    current = row['newDrawableDimensions']
    previous_size = previous['newDrawableDimensions']
    assert all(abs(current[i] - previous_size[i] * native) < 2e-6 for i in range(3)), (identifier, current, previous_size)
    expected = [round(max(.004, current[i]) + [.04, .015, .04][i], 6) for i in range(3)]
    assert all(math.isfinite(v) and abs(v - expected[i]) <= 2e-6 for i, v in enumerate(actual)), (identifier, actual, expected)
    assert row['newPhysicalFloorGapBlocks'] == 0
    measured += 1

for name in ['GroundGunModelRenderer.java', 'GroundSupplementModelRenderer.java', 'GroundBakedModelRenderer.java']:
    source = (JAVA / 'client' / name).read_text(encoding='utf-8')
    assert 'handleCameraTransforms' in source and 'ItemDisplayContext.GROUND' in source, name
    assert 'placement.scale' not in source and 'THIN_SCALE' not in source and 'extraScale' not in source, name
gun = (JAVA / 'client/GroundGunModelRenderer.java').read_text(encoding='utf-8')
ammo = (JAVA / 'client/GroundSupplementModelRenderer.java').read_text(encoding='utf-8')
assert 'ground.scale(scale.x(), scale.y(), scale.z())' in gun
assert 'poses.scale(source.scale.x(), source.scale.y(), source.scale.z())' in ammo
assert 'GroundLootRenderer.renderStack' in (JAVA / 'client/DroppedLootRenderer.java').read_text(encoding='utf-8')
assert 'GroundLootIconRenderer.clearCache()' in (JAVA / 'client/GroundLootRenderer.java').read_text(encoding='utf-8')
footprint_source = (JAVA / 'GroundLootFootprints.java').read_text(encoding='utf-8')
assert 'COLLISION_WIDTH = .22F' in footprint_source and 'COLLISION_HEIGHT = .13F' in footprint_source

result = dict(profileVersion=4, sizePolicy='native_ground_no_extra_scale', measured3d=measured,
              nativeIcons=icons, totalProfiles=measured+icons, newMissingAssets=0,
              retainedNativeAttachmentBakedScale=.5, collisionWidth=.22, collisionHeight=.13,
              boundary='Offline asset dimensions and source contract checks only; no Minecraft or shader validation.')
(ROOT / 'logs/native-drop-size-checks.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps(result))
