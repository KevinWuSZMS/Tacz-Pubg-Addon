"""Bake numeric floor bounds and audit TaCZ static meshes without launching Minecraft.

Only source assets are read. Missing UV faces become zero-area placeholders in TaCZ
r7; those placeholders must not count as physical geometry. Model visibility follows
GROUND defaults and built-in attachments. Runtime uses the same rules per item stack.
"""
import functools
import itertools
import json
import math
import pathlib
import re
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKS = ROOT / 'neoforge-1.21.1/run/tacz'
OUT = ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/ground_loot_footprints.json'
AUDIT = ROOT / 'logs/ground-loot-model-alignment-audit.json'


def identity(): return [[float(i == j) for j in range(4)] for i in range(4)]
def mul(a, b): return [[sum(a[i][k]*b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]
def translate(v):
    m = identity()
    for i in range(3): m[i][3] = v[i]
    return m

def scale(v):
    m = identity()
    for i in range(3): m[i][i] = v[i]
    return m

def rotation(axis, degrees):
    m = identity(); a, b = ((1, 2), (2, 0), (0, 1))[axis]
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    m[a][a] = m[b][b] = c; m[a][b], m[b][a] = -s, s
    return m

def trs(pivot, rotate):
    m = translate([x/16 for x in pivot])
    for axis in (2, 1, 0): m = mul(m, rotation(axis, rotate[axis]))
    return m

def point(m, p): return tuple(sum(m[i][j]*p[j] for j in range(4)) for i in range(3))
def area(quad):
    for a, b, c in [(quad[0], quad[1], quad[2]), (quad[0], quad[2], quad[3])]:
        x, y, z = [b[i]-a[i] for i in range(3)]; u, v, w = [c[i]-a[i] for i in range(3)]
        if (y*w-z*v)**2 + (z*u-x*w)**2 + (x*v-y*u)**2 > 1e-20: return True
    return False


def excluded_old(name, category, asset_id=None):
    name = name.lower()
    if category != 'guns': return not supplement_visible(name, asset_id)
    return name in {'lefthand', 'righthand', 'lefthand_pos', 'righthand_pos', 'additional_magazine', 'bullet_chain', 'muzzle_flash'} or name.startswith(('mag_extended_', 'bullet_in_', 'laser_beam'))


def supplement_visible(name, asset_id=None):
    name = name.lower()
    # Pack-authored static rays, not physical illuminated housings. Keep asset
    # context so identically named real geometry in unrelated models survives.
    effect_nodes = {'suffuse:sight_dbala2': {'scope_body_illuminated'},
                    'classicr:sight_canted_laser': {'beam_illuminated'}}
    return not (name in effect_nodes.get((asset_id or '').lower(), set()) or
                name in {'lefthand_pos', 'righthand_pos'} or name.startswith('laser_beam') or
                re.fullmatch(r'(division|ocular|ocular_scope|ocular_sight)(_[0-9]+)?', name))


def gun_visible(name, parent, profile):
    name = name.lower()
    if name in {'lefthand','righthand','lefthand_pos','righthand_pos','additional_magazine','muzzle_flash'} or name.startswith('laser_beam'): return False
    if parent == 'attachment_adapter': return name in profile['adapters']
    if name == 'mount': return profile['scope'] and profile['scope_mount']
    if name in {'carry', 'sight'}: return not profile['scope']
    if name == 'sight_folded': return profile['scope']
    if name == 'mag_standard': return profile['mag'] == 0
    if name in {'mag_extended_1','mag_extended_2','mag_extended_3'}: return profile['mag'] == int(name[-1])
    if name == 'handguard_default': return not profile['tactical']
    if name == 'handguard_tactical': return profile['tactical']
    if name in {'bullet_in_barrel','bullet_in_mag','bullet_chain'}: return False
    if name == 'muzzle_default': return 'muzzle' not in profile['installed'] or profile['show_muzzle']
    if name in {'stock_default','grip_default','laser_default','extended_mag_default'}: return name[:-8] not in profile['installed']
    return True


resources = {}
for pack in sorted(PACKS.iterdir()):
    if pack.is_dir() and (pack/'gunpack.meta.json').is_file():
        for path in pack.rglob('*.json'): resources[path.relative_to(pack).as_posix()] = path.read_bytes()
    elif pack.suffix == '.zip':
        with zipfile.ZipFile(pack) as archive:
            for name in archive.namelist():
                if name.endswith('.json'): resources[name] = archive.read(name)

# ClientHooks applies these vanilla baked GROUND transforms before TaCZ's renderer.
# In r7, standalone attachments natively use 0.5; that is authored drop size, not our multiplier.
ITEM_MODEL_PATHS = {'guns': 'assets/tacz/models/item/modern_kinetic_gun.json',
                    'ammo': 'assets/tacz/models/item/ammo.json',
                    'attachments': 'assets/tacz/models/item/attachment.json'}
with zipfile.ZipFile(ROOT / 'neoforge-1.21.1/run/mods/tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar') as archive:
    for path in ITEM_MODEL_PATHS.values():
        if path not in resources: resources[path] = archive.read(path)


@functools.lru_cache(maxsize=None)
def read(path):
    text = resources[path].decode('utf-8-sig')
    text = re.sub(r'("(?:\\.|[^"\\])*")|//[^\r\n]*|/\*[\s\S]*?\*/', lambda m: m.group(1) or '', text)
    text = re.sub(r'("(?:\\.|[^"\\])*")|,(?=\s*[}\]])', lambda m: m.group(1) or '', text)
    # Gson accepts leading '+' in pack-authored numeric values (e.g. movement_speed.base).
    text = re.sub(r'("(?:\\.|[^"\\])*")|(?<=[\[,:])\s*\+(?=\d)', lambda m: m.group(1) or '', text)
    return json.loads(text)


def locate(ref, category, root='assets'):
    ns, key = ref.split(':', 1)
    return f'{root}/{ns}/{category}/{key}.json'


def baked_ground_matrix(category):
    data = read(ITEM_MODEL_PATHS[category]).get('display', {}).get('ground', {})
    matrix = translate([v / 16 for v in data.get('translation', [0, 0, 0])])
    for axis, degrees in enumerate(data.get('rotation', [0, 0, 0])): matrix = mul(matrix, rotation(axis, degrees))
    matrix = mul(matrix, scale(data.get('scale', [1, 1, 1])))
    for axis, degrees in enumerate(data.get('right_rotation', [0, 0, 0])): matrix = mul(matrix, rotation(axis, degrees))
    return matrix


def attachment(ref):
    index = read(locate(ref, 'index/attachments', 'data'))
    display = read(locate(index['display'], 'display/attachments'))
    data = read(locate(index['data'], 'data/attachments', 'data'))
    return display, data


def default_profile(index):
    profile = dict(scope=False,scope_mount=False,mag=0,tactical=False,installed=set(),adapters=set(),show_muzzle=False,attachments={})
    data = read(locate(index['data'], 'data/guns', 'data'))
    for kind, ref in data.get('builtin_attachments', {}).items():
        display, data = attachment(ref); profile['installed'].add(kind)
        if display.get('model') and locate(display['model'],'geo_models') in resources:
            profile['attachments'][kind] = dict(display, _asset_id=ref)
        if kind == 'scope': profile['scope_mount'] = display.get('show_mount',True)
        if kind == 'muzzle': profile['show_muzzle'] = display.get('show_muzzle',False)
        if kind == 'extended_mag': profile['mag'] = data.get('extended_mag_level',0)
        if display.get('adapter'): profile['adapters'].add(display['adapter'])
    profile['scope'] = 'scope' in profile['installed']
    profile['tactical'] = bool({'laser','grip'} & profile['installed'])
    return profile


# Faces in Java-local coordinates. TaCZ swaps Bedrock east/west and up/down.
FACE_BITS = {
    'up':[(1,0,1),(0,0,1),(0,0,0),(1,0,0)],
    'down':[(1,1,0),(0,1,0),(0,1,1),(1,1,1)],
    'east':[(0,0,0),(0,0,1),(0,1,1),(0,1,0)],
    'west':[(1,0,1),(1,0,0),(1,1,0),(1,1,1)],
    'north':[(1,0,0),(0,0,0),(0,1,0),(1,1,0)],
    'south':[(0,0,1),(1,0,1),(1,1,1),(0,1,1)],
}


def model_parts(model):
    geometry = model.get('minecraft:geometry'); geometry = geometry[0] if geometry else next(v for k,v in model.items() if k.startswith('geometry.'))
    bones = {b['name']:b for b in geometry['bones']}; matrices = {}
    def matrix(name):
        if name not in matrices:
            bone=bones[name]; p=bone.get('pivot',[0,0,0]); parent=bones.get(bone.get('parent')); base=parent.get('pivot',[0,0,0]) if parent else [0,24,0]
            m=trs([p[0]-base[0],base[1]-p[1],p[2]-base[2]],bone.get('rotation',[0,0,0]))
            matrices[name]=mul(matrix(bone['parent']),m) if parent else m
        return matrices[name]
    return bones, matrix


def ground_matrix(display,bones,category):
    ground=mul(translate([0,1.5,0]),scale([-1,-1,1]))
    default=[.75]*3 if category == 'ammo' else [.6]*3
    gs=display.get('transform',{}).get('scale',{}).get('ground',default) if category != 'attachments' else [1]*3
    gs = gs or [1]*3
    path=[]; name='ground'
    while category != 'attachments' and name in bones: path.append(name); name=bones[name].get('parent')
    ground=mul(ground,translate([0,1.5,0]))
    for name in path:
        bone=bones[name]; angles=bone.get('rotation',[0,0,0]); p=bone.get('pivot',[0,0,0]); parent=bones.get(bone.get('parent')); base=parent.get('pivot',[0,0,0]) if parent else [0,24,0]
        for axis in (0,1,2): ground=mul(ground,rotation(axis,-angles[axis]))
        if parent: shift=[-(p[0]-base[0])*gs[0]/16,-(base[1]-p[1])*gs[1]/16,-(p[2]-base[2])*gs[2]/16]
        else: shift=[-p[0]*gs[0]/16,p[1]*gs[1]/16,-p[2]*gs[2]/16]
        ground=mul(ground,translate(shift))
    ground=mul(ground,translate([0,-1.5,0]))
    return mul(baked_ground_matrix(category), mul(mul(mul(ground,translate([0,1.5,0])),scale(gs)),translate([0,-1.5,0])))


def vertices(display,model,category,profile=None,old=False,prefix=None,asset_id=None):
    bones,matrix=model_parts(model); ground=ground_matrix(display,bones,category) if prefix is None else prefix
    result=[]; omitted=0; excluded_bones=[]
    for name,bone in bones.items():
        current=name; hidden=False
        while current in bones:
            b=bones[current]; parent=b.get('parent')
            hidden |= excluded_old(current,category,asset_id) if old else (not gun_visible(current,parent,profile) if category=='guns' else not supplement_visible(current,asset_id))
            if category=='guns' and not old and current in {kind+'_pos' for kind in ['scope','muzzle','stock','grip','laser','extended_mag']}: hidden=True
            current=parent
        if hidden:
            if bone.get('cubes'): excluded_bones.append(name)
            continue
        for cube in bone.get('cubes',[]):
            m=mul(ground,matrix(name)); pivot=bone.get('pivot',[0,0,0])
            if 'rotation' in cube:
                p=cube.get('pivot',[0,0,0]); m=mul(m,trs([p[0]-pivot[0],pivot[1]-p[1],p[2]-pivot[2]],cube['rotation']));pivot=p
            o,size=cube['origin'],cube['size']; origin=[o[0]-pivot[0],pivot[1]-o[1]-size[1],o[2]-pivot[2]]; inflate=cube.get('inflate',0)
            for face,bits_list in FACE_BITS.items():
                uv=cube.get('uv',[])
                if isinstance(uv,dict) and face not in uv:
                    omitted+=1
                    if old: result.extend([point(m,[0,0,0,1])]*4)
                    continue
                quad=[point(m,[(origin[i]+(size[i]+inflate if bits[i] else -inflate))/16 for i in range(3)]+[1]) for bits in bits_list]
                if old or area(quad): result.extend(quad)
                else: omitted+=1
    if category=='guns' and not old:
        for kind,ad in profile['attachments'].items():
            node=kind+'_pos'
            if node not in bones: continue
            m=mul(mul(ground,matrix(node)),translate([0,-1.5,0]))
            av,ao,_=vertices(ad,read(locate(ad['model'],'geo_models')),'attachments',prefix=m,asset_id=ad['_asset_id'])
            result.extend(av); omitted+=ao
    return result,omitted,excluded_bones


def flat(vertices):
    low=[min(v[i] for v in vertices) for i in range(3)]; high=[max(v[i] for v in vertices) for i in range(3)]; dims=[high[i]-low[i] for i in range(3)]
    if dims[1] <= min(dims)*1.05: mat=identity(); axis='none'
    elif dims[0] <= dims[2]: mat=rotation(2,90);axis='z90'
    else: mat=rotation(0,90);axis='x90'
    rotated=[point(mat,list(v)+[1]) for v in vertices]; low2=[min(v[i] for v in rotated) for i in range(3)]; high2=[max(v[i] for v in rotated) for i in range(3)]
    offset=[-(low2[0]+high2[0])/2,-low2[1],-(low2[2]+high2[2])/2]
    thin=min(dims) <= .040001 and min(dims)/max(dims) <= .040001
    return dict(matrix=mul(translate(offset),mat),dimensions=[high2[i]-low2[i] for i in range(3)],axis=axis,extra=1,thin=thin)


def main():
    result={'guns':{},'ammo':{},'attachments':{}}; render_types={};errors=[];rows=[];expected_guns={g['id'] for g in json.loads((ROOT/'docs/gun-balance/gun-assets.json').read_text(encoding='utf-8'))['guns']}
    for path in sorted(resources):
        pieces=path.split('/')
        if len(pieces)<5 or pieces[0]!='data' or pieces[2]!='index' or pieces[3] not in result: continue
        category=pieces[3]; identifier=pieces[1]+':'+ '/'.join(pieces[4:])[:-5]
        if category=='guns' and identifier not in expected_guns: continue
        try:
            index=read(path);display=read(locate(index['display'],'display/'+category))
            if not display.get('model'): raise ValueError('no_3d_model')
            model=read(locate(display['model'],'geo_models'));profile=default_profile(index) if category=='guns' else None
            new_vertices,omitted,excluded=vertices(display,model,category,profile,asset_id=identifier)
            if not new_vertices: raise ValueError('no_drawable_geometry')
            new=flat(new_vertices)
            assert all(math.isfinite(x) and x>=0 for x in new['dimensions'])
            placed=[point(new['matrix'],list(v)+[1]) for v in new_vertices]
            assert abs(min(v[1] for v in placed))<1e-10
            old_vertices,_,_=vertices(display,model,category,profile,old=True,asset_id=identifier)
            old=flat(old_vertices);old_drawn=[point(old['matrix'],list(v)+[1]) for v in new_vertices]
            gap=min(v[1] for v in old_drawn)
            sizes=[round(max(.004,new['dimensions'][i])+[.04,.015,.04][i],6) for i in range(3)]
            result[category][identifier]=sizes;kind='3d_thin' if new['thin'] else '3d_full';render_types[category+'/'+identifier]=kind
            rows.append(dict(category=category,id=identifier,kind=kind,source=path,model=display['model'],ignoredDegenerateFaces=omitted,excludedGeometryBones=excluded,
                oldFlatAxis=old['axis'],newFlatAxis=new['axis'],oldPhysicalFloorGapBlocks=round(gap,7),newPhysicalFloorGapBlocks=0,
                oldMeasuredDimensions=[round(x,7) for x in old['dimensions']],newDrawableDimensions=[round(x,7) for x in new['dimensions']],
                builtins=sorted(profile['installed']) if profile else []))
        except (KeyError,ValueError,TypeError,ZeroDivisionError,RecursionError,AssertionError) as error:
            matrix = baked_ground_matrix(category)
            icon = flat([point(matrix, [x, y, 0, 1]) for x in [-.5, .5] for y in [-.5, .5]])
            result[category][identifier]=[icon['dimensions'][0],max(.025,icon['dimensions'][1]),icon['dimensions'][2]];render_types[category+'/'+identifier]='icon_2d'
            expected=str(error) in {'no_3d_model','no_drawable_geometry'}
            rows.append(dict(category=category,id=identifier,kind='icon_2d',reason=str(error),source=path))
            if not expected:errors.append(dict(category=category,id=identifier,error=str(error)))
    counts={category:len(values) for category,values in result.items()}
    OUT.write_text(json.dumps(dict(version=4,units='blocks',sizePolicy='native_ground_no_extra_scale',measurement='drawable_non_degenerate_quads_and_ground_visibility',**result,render_types=render_types),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    summary=dict(**counts,drawable3d=sum(row['kind']!='icon_2d' for row in rows),icons=sum(row['kind']=='icon_2d' for row in rows),
        affectedFloorGap=sum(abs(row.get('oldPhysicalFloorGapBlocks',0))>.0001 for row in rows),
        previouslyFloating=sum(row.get('oldPhysicalFloorGapBlocks',0)>.0001 for row in rows),
        ignoredDegenerateFaces=sum(row.get('ignoredDegenerateFaces',0) for row in rows))
    audit=dict(schemaVersion=1,boundary='Offline asset geometry audit; no Minecraft, shader or visual gameplay validation.',summary=summary,
        rootCauses=['TaCZ missing PerFace UV faces compile degenerate vertices at bone origins, formerly included in bounds.',
            'Measurement included hidden mount/sight/handguard/adapter variants while TaCZ render used stack-dependent visibility.',
            'TaCZ model.render and cleanAnimationTransform mutated shared first-person/refit model state; now avoided.'],
        rows=rows,errors=errors)
    AUDIT.write_text(json.dumps(audit,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (ROOT/'logs/ground-loot-footprints-generation.json').write_text(json.dumps(dict(**counts,fallback=errors,measurement='drawable_non_degenerate_quads',audit=str(AUDIT.relative_to(ROOT))),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(summary));print('Missing source assets:',len(errors))


if __name__=='__main__': main()
