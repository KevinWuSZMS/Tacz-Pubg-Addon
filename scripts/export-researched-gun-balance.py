"""Export only the reviewed balance factors; never modifies an installed TaCZ gun pack."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parent.parent
source = ROOT / "docs/gun-balance/proposed-gun-data.json"
candidate = json.loads(source.read_text(encoding="utf-8"))
guns = []
seen = {}
for gun in candidate["guns"]:
    factors = (gun["damageFactor"], gun["controlFactor"])
    data_id = gun["originalDataRef"]
    if data_id in seen and seen[data_id] != factors:
        raise ValueError(f"Conflicting factors for shared gun data {data_id}")
    seen[data_id] = factors
    guns.append(dict(gunId=gun["gunId"], dataId=data_id,
                     damageFactor=factors[0], controlFactor=factors[1]))
output = dict(schemaVersion=1, source="docs/gun-balance/proposed-gun-data.json",
              sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
              note="Apply to freshly loaded source JSON only; unlisted guns retain pack defaults.",
              guns=guns)
destination = ROOT / "neoforge-1.21.1/src/main/resources/data/tacz_pubg/gun_balance/researched.json"
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(output, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
print(f"Exported {len(guns)} guns / {len(seen)} data references, {destination.stat().st_size} bytes")
