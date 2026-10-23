from pathlib import Path
import zipfile
ROOT = Path(__file__).resolve().parents[1]
dest = ROOT/'downloads/tacz/r7-default-reference'
prefix = 'assets/tacz/custom/tacz_default_gun/'
with zipfile.ZipFile(ROOT/'downloads/tacz/tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar') as archive:
    count = 0
    for name in archive.namelist():
        if not name.startswith(prefix) or name.endswith('/'): continue
        target = dest/name[len(prefix):]
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(archive.read(name))
        count += 1
doc = ROOT/'docs/TaCZ枪械注册与制作说明.md'
text = doc.read_text(encoding='utf-8')
text = text.replace('../neoforge-1.21.1/run/tacz/tacz_default_gun/','../downloads/tacz/r7-default-reference/')
text = text.replace('默认包根目录为 `neoforge-1.21.1/run/tacz/tacz_default_gun/`。下表全部是实际 r7 文件。',
    '下表核对的是 JAR 内原始 r7 默认包，已提取至 `downloads/tacz/r7-default-reference/` 供只读参考。安装 CBRG 后，运行目录的默认包已有作者覆盖与兼容修正，不能再把该处参数当原始 r7 参数。')
doc.write_text(text,encoding='utf-8')
print('Original r7 reference files:',count)
