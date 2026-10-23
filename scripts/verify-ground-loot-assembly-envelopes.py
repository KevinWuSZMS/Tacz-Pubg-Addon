"""Offline numeric containment checks; never starts Minecraft or loads client models."""
import ast
import hashlib
import json
from pathlib import Path
import math
import random

ROOT = Path(__file__).resolve().parents[1]
generator = ROOT / 'scripts/generate-ground-loot-assembly-envelopes.py'
source = generator.read_text(encoding='utf-8')
tree = ast.parse(source)
method = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'possible_flat_dimensions')
namespace = {}
exec(compile(ast.Module(body=[method], type_ignores=[]), str(generator), 'exec'), namespace)
rng = random.Random(20261018)
interval_checks = 0
for _ in range(5000):
    lower = [10 ** rng.uniform(-4, 1) for _ in range(3)]
    upper = [v * (1 + 10 * rng.random()) for v in lower]
    limit, axes, _scale = namespace['possible_flat_dimensions'](lower, upper)
    for blend in [[0, 0, 0], [1, 1, 1]] + [[rng.random() for _ in range(3)] for _ in range(8)]:
        actual = [lower[i] + (upper[i] - lower[i]) * blend[i] for i in range(3)]
        # Independent reference: the existing GroundLootFlatPose physical-axis rule.
        if actual[1] <= min(actual) * 1.05:
            rendered = actual
        elif actual[0] <= actual[2]:
            rendered = [actual[1], actual[0], actual[2]]
        else:
            rendered = [actual[0], actual[2], actual[1]]
        assert all(rendered[i] <= limit[i] + 1e-9 for i in range(3)), (lower, upper, actual, axes, limit)
        interval_checks += 1

expected = {g['id'] for g in json.loads((ROOT / 'docs/gun-balance/gun-assets.json').read_text(encoding='utf-8'))['guns']}
default = json.loads((ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/ground_loot_footprints.json').read_text(encoding='utf-8'))['guns']
envelopes = json.loads((ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/ground_loot_assembly_envelopes.json').read_text(encoding='utf-8'))
assert envelopes['schemaVersion'] == 2
assert envelopes['sizePolicy'] == 'native_ground_no_extra_scale'
assert set(envelopes['guns']) == expected
profile_checks = 0
for identifier, size in envelopes['guns'].items():
    assert len(size) == 3 and all(math.isfinite(value) and value > 0 for value in size)
    assert all(size[i] >= default[identifier][i] for i in range(3))
    profile_checks += 1
result = {'schemaVersion': 1, 'generatorSha256': hashlib.sha256(generator.read_bytes()).hexdigest(),
          'seed': 20261018, 'intervalContainmentChecks': interval_checks, 'profileCoverageChecks': profile_checks,
          'boundary': 'Offline numeric geometry containment. No Minecraft, gameplay or shader validation.'}
(ROOT / 'logs/ground-loot-envelope-interval-checks.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result))
