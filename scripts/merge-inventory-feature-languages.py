"""Merge the reviewed bilingual catalogs for the inventory feature into the mod."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
bundles = [
    "backpack-inventory-ui-lang.json",
    "medicine-throwables-lang.json",
    "armor-lang.json",
    "inventory-core-lang.json",
]
for locale in ("zh_cn", "en_us"):
    target = root / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg/lang" / (locale + ".json")
    catalog = json.loads(target.read_text(encoding="utf-8"))
    for bundle in bundles:
        data = json.loads((root / "docs/research" / bundle).read_text(encoding="utf-8-sig"))
        if locale in data:
            catalog.update(data[locale])
        else:
            catalog.update({key: value[locale] for key, value in data.items()})
    target.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{locale}: {len(catalog)} keys")
