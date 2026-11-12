#!/usr/bin/env python3
"""
Bakes the Midtown Madness 2 cars for the taskbar toy (apps/mm2cars) out of the
midtown-madness web port's converted models.

    python3 scripts/export_mm2_cars.py ~/Documents/priv_workspace/midtown-madness

Each car becomes three files in app/src/main/assets/mm2cars/:

    <id>.json      the mesh, one entry per part (body, the four wheels, and every
                   piece that can come off: MM2's BREAK*_H panels, the Panoz's
                   suspension arms and fenders). Positions are metres relative to
                   the part's pivot, in MM2's frame (Y up, the nose towards -Z);
                   UVs are already in atlas pixels, which is what
                   Canvas.drawVertices wants.
    <id>.png       every texture the car uses, in one atlas, so the car is a single
                   draw call.
    <id>_dmg.png   the same atlas with MM2's *_dmg paint, region for region, so
                   swapping bitmaps is all damage takes.

The LIGHT_* quads are left out: they are the web port's headlight glow cards. The police
car also gets "siren": where the left (red) and right (blue) glows of its light bar sit.

And two of MM2's props, each as <id>.json plus its own textures (they tile, so they can't
share an atlas; UVs are in each texture's pixels and run past its edges):

    gate.json      the race checkpoint, geometry/pt_check.pkg, as js/data/mm2-checkpoint-model.js
                   has it: model units, scaled by the game to (radius, 7.5, radius).
    trailer.json   the jump trailer, sp_jumptrailer_f, moved so its foot is at the origin and it
                   rises along +X, with "profile": its deck as (x, height) points, for driving on.

And the street the toy drives along, from San Francisco:

    street.png     one atlas: the Victorian row-house facade tiles (sw_stc_<colour>_<piece>_f)
                   in every colour, the tar roof, the road (r2_f: one lane, yellow centre line
                   at the top, white edge line at the bottom) and sidewalk (swalk_f) - those
                   two as the city mesh sf_city.glb has them, the loose PNGs' lines being off
                   colour - and the street trees' textures.
    street.json    "tiles": name -> [left, top, right, bottom] in atlas pixels, "colours" and
                   "pieces" to build houses from, and "trees": sp_tree1/5/6_s as meshes in
                   metres, standing on y = 0, UVs in atlas pixels.
"""
import io
import struct
import re
import json
import os
import sys

from PIL import Image

CARS = [
    # id, folder in cars/, paint (MM2's colour key inside the texture names)
    ("bullet", "Ford_Mustang_Bullet", "green"),
    ("beetle", "VW_Bug", "yellow"),
    ("cab", "London_Cab", "black"),
    ("panoz", "Panoz_Roadster", "red"),
    ("cop", "Police_Car", "ca"),
]

PAD = 2  # pixels of repeated edge around each atlas region, so filtering never bleeds
SWATCH = 4  # size of the region baked for an untextured (colour-only) material


def parse_mtl(path):
    mats, cur = {}, None
    for line in open(path):
        p = line.split()
        if not p:
            continue
        if p[0] == "newmtl":
            cur = mats.setdefault(p[1], {"kd": (1.0, 1.0, 1.0), "map": None})
        elif p[0] == "Kd" and cur is not None:
            cur["kd"] = tuple(float(x) for x in p[1:4])
        elif p[0] == "map_Kd" and cur is not None:
            cur["map"] = p[1]
    return mats


def parse_obj(path):
    verts, uvs, parts = [], [], {}
    obj, mtl = None, None
    for line in open(path):
        p = line.split()
        if not p:
            continue
        if p[0] == "v":
            verts.append(tuple(float(x) for x in p[1:4]))
        elif p[0] == "vt":
            uvs.append(tuple(float(x) for x in p[1:3]))
        elif p[0] == "o":
            obj = p[1]
        elif p[0] == "usemtl":
            mtl = p[1]
        elif p[0] == "f":
            corners = []
            for c in p[1:]:
                ids = c.split("/")
                v = verts[int(ids[0]) - 1]
                t = uvs[int(ids[1]) - 1] if len(ids) > 1 and ids[1] else (0.5, 0.5)
                corners.append((v, t))
            for i in range(1, len(corners) - 1):  # fan, in case of quads
                parts.setdefault(obj, []).append((mtl, corners[0], corners[i], corners[i + 1]))
    return parts


def paint_file(folder, name, paint, damaged):
    """The texture file for one material in the chosen paint (and damage), if it exists."""
    manifest_textures = PAINT_PATTERNS.get(folder, {})
    if name in manifest_textures:
        entry = manifest_textures[name]
        pattern = entry.get("dmgPattern") if damaged else entry["pattern"]
        if pattern:
            return pattern.replace("{color}", paint)
        return entry["pattern"].replace("{color}", paint)
    return None


def kind_of(part):
    if part.startswith("wheel_"):
        return "wheel"
    if part == "BODY_H":
        return "body"
    return "break"  # BREAK*_H panels, ARM*_H suspension, FNDR*_H fenders


def export(root, out_dir, car_id, folder, paint):
    car_dir = os.path.join(root, "cars", folder)
    mats = parse_mtl(os.path.join(car_dir, "car.mtl"))
    parts = parse_obj(os.path.join(car_dir, "car.obj"))
    parts = {k: v for k, v in parts.items() if not k.startswith("LIGHT_")}

    # One atlas region per (material). Clean and damaged images per region.
    used = sorted({t[0] for tris in parts.values() for t in tris})
    regions = {}
    for m in used:
        mat = mats.get(m, {"kd": (1, 1, 1), "map": None})
        kd = mat["kd"]
        if mat["map"]:
            clean_name = paint_file(folder, m, paint, False) or mat["map"]
            dmg_name = paint_file(folder, m, paint, True) or clean_name
            clean_path = os.path.join(car_dir, clean_name)
            if not os.path.exists(clean_path):
                clean_path = os.path.join(car_dir, mat["map"])
            dmg_path = os.path.join(car_dir, dmg_name)
            if not os.path.exists(dmg_path):
                dmg_path = clean_path
            clean = Image.open(clean_path).convert("RGBA")
            dmg = Image.open(dmg_path).convert("RGBA").resize(clean.size)
            if kd != (1.0, 1.0, 1.0):
                clean, dmg = tint(clean, kd), tint(dmg, kd)
        else:
            colour = tuple(int(round(c * 255)) for c in kd) + (255,)
            clean = Image.new("RGBA", (SWATCH, SWATCH), colour)
            dmg = clean
        regions[m] = {"clean": clean, "dmg": dmg}

    # Shelf packing, tallest first, into a power-of-two-ish square.
    order = sorted(regions, key=lambda m: -regions[m]["clean"].size[1])
    width = 512
    while True:
        x = y = shelf = 0
        ok = True
        for m in order:
            w, h = regions[m]["clean"].size
            w2, h2 = w + PAD * 2, h + PAD * 2
            if x + w2 > width:
                x, y, shelf = 0, y + shelf, 0
            if w2 > width:
                ok = False
                break
            regions[m]["at"] = (x + PAD, y + PAD)
            x += w2
            shelf = max(shelf, h2)
        height = y + shelf
        if ok and height <= width:
            break
        width *= 2
    atlas_h = height

    atlases = {}
    for which in ("clean", "dmg"):
        atlas = Image.new("RGBA", (width, atlas_h), (0, 0, 0, 0))
        for m in order:
            img = regions[m][which]
            ax, ay = regions[m]["at"]
            atlas.paste(edge_extend(img, PAD), (ax - PAD, ay - PAD))
        atlases[which] = atlas

    out_parts = []
    for name, tris in parts.items():
        xs = [c[0][0] for t in tris for c in t[1:]]
        ys = [c[0][1] for t in tris for c in t[1:]]
        zs = [c[0][2] for t in tris for c in t[1:]]
        if kind_of(name) == "body":
            pivot = (0.0, 0.0, 0.0)
        else:
            pivot = ((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, (min(zs) + max(zs)) / 2)
        pos, uv = [], []
        for mtl, *corners in tris:
            w, h = regions[mtl]["clean"].size
            ax, ay = regions[mtl]["at"]
            for (vx, vy, vz), (u, v) in corners:
                pos += [round(vx - pivot[0], 4), round(vy - pivot[1], 4), round(vz - pivot[2], 4)]
                u = min(max(u, 0.0), 1.0)
                v = min(max(v, 0.0), 1.0)
                uv += [round(ax + 0.5 + u * (w - 1), 2), round(ay + 0.5 + (1 - v) * (h - 1), 2)]
        entry = {"name": name, "kind": kind_of(name), "pivot": [round(c, 4) for c in pivot], "pos": pos, "uv": uv}
        if kind_of(name) == "wheel":
            entry["radius"] = round((max(ys) - min(ys)) / 2, 4)
        out_parts.append(entry)

    # body first, then wheels, then the pieces that come off
    rank = {"body": 0, "wheel": 1, "break": 2}
    out_parts.sort(key=lambda p: (rank[p["kind"]], p["name"]))

    all_y = [c[0][1] for tris in parts.values() for t in tris for c in t[1:]]
    all_x = [c[0][0] for tris in parts.values() for t in tris for c in t[1:]]
    all_z = [c[0][2] for tris in parts.values() for t in tris for c in t[1:]]
    doc = {
        "id": car_id,
        "atlas": f"{car_id}.png",
        "atlasDamaged": f"{car_id}_dmg.png",
        "bounds": [round(min(all_x), 3), round(min(all_y), 3), round(min(all_z), 3),
                   round(max(all_x), 3), round(max(all_y), 3), round(max(all_z), 3)],
        "parts": out_parts,
    }
    siren = [m for m in used if m.endswith("_lights")]
    if siren:
        corners = [c[0] for tris in parts.values() for t in tris if t[0] in siren for c in t[1:]]
        top = max(v[1] for v in corners)
        mid_z = (min(v[2] for v in corners) + max(v[2] for v in corners)) / 2
        reach = max(abs(v[0]) for v in corners) * 0.55
        doc["siren"] = [round(-reach, 3), round(top, 3), round(mid_z, 3), round(reach, 3), round(top, 3), round(mid_z, 3)]

    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, f"{car_id}.json"), "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    atlases["clean"].save(os.path.join(out_dir, f"{car_id}.png"), optimize=True)
    atlases["dmg"].save(os.path.join(out_dir, f"{car_id}_dmg.png"), optimize=True)
    tri_count = sum(len(t) for t in parts.values())
    print(f"{car_id}: {tri_count} triangles, {len(out_parts)} parts, atlas {width}x{atlas_h}")


def tint(img, kd):
    r, g, b, a = img.split()
    r = r.point(lambda c: int(c * kd[0]))
    g = g.point(lambda c: int(c * kd[1]))
    b = b.point(lambda c: int(c * kd[2]))
    return Image.merge("RGBA", (r, g, b, a))


def edge_extend(img, pad):
    w, h = img.size
    out = Image.new("RGBA", (w + pad * 2, h + pad * 2))
    out.paste(img, (pad, pad))
    out.paste(img.crop((0, 0, w, 1)).resize((w, pad)), (pad, 0))
    out.paste(img.crop((0, h - 1, w, h)).resize((w, pad)), (pad, h + pad))
    left = out.crop((pad, 0, pad + 1, h + pad * 2)).resize((pad, h + pad * 2))
    right = out.crop((w + pad - 1, 0, w + pad, h + pad * 2)).resize((pad, h + pad * 2))
    out.paste(left, (0, 0))
    out.paste(right, (w + pad, 0))
    return out


def export_prop(out_dir, prop_id, groups, extra=None):
    """groups: [(texture path, [(x, y, z, u, v) per corner, three per triangle], v_down)]."""
    parts = []
    for i, (tex_path, corners, v_down) in enumerate(groups):
        img = Image.open(tex_path).convert("RGBA")
        name = f"{prop_id}_{i}.png"
        img.save(os.path.join(out_dir, name), optimize=True)
        w, h = img.size
        pos, uv = [], []
        for x, y, z, u, v in corners:
            pos += [round(x, 4), round(y, 4), round(z, 4)]
            uv += [round(u * w, 2), round((v if v_down else 1 - v) * h, 2)]
        parts.append({"tex": name, "pos": pos, "uv": uv})
    doc = {"id": prop_id, "parts": parts}
    doc.update(extra or {})
    with open(os.path.join(out_dir, f"{prop_id}.json"), "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    print(f"{prop_id}: {sum(len(g[1]) for g in groups) // 3} triangles")


def export_gate(root, out_dir):
    src = open(os.path.join(root, "js", "data", "mm2-checkpoint-model.js")).read()
    block = src[src.index("pt_check:"):src.index("pt_finish:")]
    groups = []
    for tex, pos, uv in re.findall(r"tex:\s*'([^']+)',\s*pos:\s*\[([^\]]*)\],\s*uv:\s*\[([^\]]*)\]", block):
        p = [float(x) for x in pos.split(",") if x.strip()]
        t = [float(x) for x in uv.split(",") if x.strip()]
        corners = [(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], t[i * 2], t[i * 2 + 1]) for i in range(len(p) // 3)]
        groups.append((os.path.join(root, tex), corners, True))
    export_prop(out_dir, "gate", groups)


def export_trailer(root, out_dir):
    folder = os.path.join(root, "map_models")
    mats = parse_mtl(os.path.join(folder, "sp_jumptrailer_f.mtl"))
    tris = [t for part in parse_obj(os.path.join(folder, "sp_jumptrailer_f.obj")).values() for t in part]
    xs = [c[0][0] for t in tris for c in t[1:]]
    ys = [c[0][1] for t in tris for c in t[1:]]
    x0, y0 = min(xs), min(ys)
    by_mat = {}
    for mtl, *corners in tris:
        by_mat.setdefault(mtl, []).extend((v[0] - x0, v[1] - y0, v[2], u, v_) for v, (u, v_) in corners)
    groups = [(os.path.join(folder, mats[m]["map"]), c, False) for m, c in by_mat.items()]
    # The deck it is driven up: the upward faces of the body, as a polyline from its foot
    deck = set()
    for mtl, *corners in tris:
        if mtl != "p_trailer_f":
            continue
        a, b, c = (v for v, _ in corners)
        ux, uy, uz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
        vx, vy, vz = c[0] - a[0], c[1] - a[1], c[2] - a[2]
        nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
        # facing up and rising towards +X (the trailer's floor inside is level, and left out)
        if ny / max(1e-9, (nx * nx + ny * ny + nz * nz) ** 0.5) > 0.5 and nx < -0.01:
            for v in (a, b, c):
                deck.add((round(v[0] - x0, 3), round(v[1] - y0, 3)))
    profile = []
    for x, y in sorted(deck):
        if profile and abs(profile[-1][0] - x) < 1e-3:
            profile[-1][1] = max(profile[-1][1], y)
        else:
            profile.append([x, y])
    export_prop(out_dir, "trailer", groups, {"profile": profile, "width": round(max(c[0][2] for t in tris for c in t[1:]) * 2, 3)})


HOUSE_COLOURS = ["cream", "green", "yellow", "peach", "gray", "offwhite", "tan", "brownbrick", "redbrick"]
HOUSE_PIECES = {
    "ground": ["garage", "bdoor", "sdoor", "basewin", "stairs"],
    "floor": ["hexbay", "marbay", "sqrbay", "nonbay", "marswin"],
    "top": ["pediment"],
}
STREET_TREES = ["sp_tree1_s", "sp_tree5_s", "sp_tree6_s"]


def glb_textures(path, names):
    """The named materials' base colour images out of a glTF binary."""
    with open(path, "rb") as f:
        f.read(12)
        length, _ = struct.unpack("<II", f.read(8))
        doc = json.loads(f.read(length))
        length, _ = struct.unpack("<II", f.read(8))
        blob = f.read(length)
    out = {}
    for m in doc["materials"]:
        name = re.match(r"\('([^']+)'", m["name"]).group(1)
        if name not in names or name in out:
            continue
        image = doc["images"][doc["textures"][m["pbrMetallicRoughness"]["baseColorTexture"]["index"]]["source"]]
        view = doc["bufferViews"][image["bufferView"]]
        start = view.get("byteOffset", 0)
        out[name] = Image.open(io.BytesIO(blob[start:start + view["byteLength"]])).convert("RGBA")
    return out


def pack(images, width=1024):
    """Shelf-packs {name: image} with edge padding; returns the atlas and name -> (x, y)."""
    order = sorted(images, key=lambda n: -images[n].size[1])
    at, x, y, shelf = {}, 0, 0, 0
    for n in order:
        w, h = images[n].size
        if x + w + PAD * 2 > width:
            x, y, shelf = 0, y + shelf, 0
        at[n] = (x + PAD, y + PAD)
        x += w + PAD * 2
        shelf = max(shelf, h + PAD * 2)
    atlas = Image.new("RGBA", (width, y + shelf), (0, 0, 0, 0))
    for n, img in images.items():
        ax, ay = at[n]
        atlas.paste(edge_extend(img, PAD), (ax - PAD, ay - PAD))
    return atlas, at


def export_street(root, out_dir):
    folder = os.path.join(root, "map_models")
    images = {}
    for colour in HOUSE_COLOURS:
        for piece in sum(HOUSE_PIECES.values(), []):
            images[f"{colour}_{piece}"] = Image.open(os.path.join(folder, f"sw_stc_{colour}_{piece}_f.png")).convert("RGBA")
    images["roof"] = Image.open(os.path.join(folder, "sw_roof_tar01_s.png")).convert("RGBA")
    city = glb_textures(os.path.join(folder, "sf", "sf_city.glb"), {"r2_f", "swalk_f"})
    images["road"] = city["r2_f"]
    images["sidewalk"] = city["swalk_f"]
    trees = []
    for tree in STREET_TREES:
        mats = parse_mtl(os.path.join(folder, tree + ".mtl"))
        tris = [t for part in parse_obj(os.path.join(folder, tree + ".obj")).values() for t in part]
        for mtl, *_ in tris:
            images.setdefault("tex_" + mats[mtl]["map"], Image.open(os.path.join(folder, mats[mtl]["map"])).convert("RGBA"))
        trees.append((tree, mats, tris))
    atlas, at = pack(images)
    atlas.save(os.path.join(out_dir, "street.png"), optimize=True)

    tiles = {n: [at[n][0] + 0.5, at[n][1] + 0.5, at[n][0] + images[n].size[0] - 0.5, at[n][1] + images[n].size[1] - 0.5] for n in images if not n.startswith("tex_")}
    out_trees = []
    for tree, mats, tris in trees:
        y0 = min(c[0][1] for t in tris for c in t[1:])
        pos, uv = [], []
        for mtl, *corners in tris:
            name = "tex_" + mats[mtl]["map"]
            w, h = images[name].size
            ax, ay = at[name]
            for (x, y, z), (u, v) in corners:
                pos += [round(x, 4), round(y - y0, 4), round(z, 4)]
                u = min(max(u, 0.0), 1.0); v = min(max(v, 0.0), 1.0)
                uv += [round(ax + 0.5 + u * (w - 1), 2), round(ay + 0.5 + (1 - v) * (h - 1), 2)]
        out_trees.append({"name": tree, "pos": pos, "uv": uv})
    doc = {"tiles": {k: [round(v, 2) for v in r] for k, r in tiles.items()}, "colours": HOUSE_COLOURS, "pieces": HOUSE_PIECES, "trees": out_trees}
    with open(os.path.join(out_dir, "street.json"), "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    print(f"street: {len(tiles)} tiles, {len(out_trees)} trees, atlas {atlas.size[0]}x{atlas.size[1]}")


def write_sim_data(out_dir):
    """What scripts/mm2cars_sim needs of each car and of the trailer."""
    cars = {}
    for car_id, _, _ in CARS:
        d = json.load(open(os.path.join(out_dir, f"{car_id}.json")))
        cars[car_id] = {"bounds": d["bounds"], "wheels": [{"pivot": p["pivot"], "radius": p["radius"]} for p in d["parts"] if p["kind"] == "wheel"]}
    trailer = json.load(open(os.path.join(out_dir, "trailer.json")))
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mm2cars_sim", "cars.js")
    with open(path, "w") as f:
        f.write("// Generated by scripts/export_mm2_cars.py: what the physics needs of each car and the trailer\n")
        f.write("export const CARS = " + json.dumps(cars, indent=1) + ";\n")
        f.write("export const TRAILER_PROFILE = " + json.dumps(trailer["profile"]) + ";\n")


PAINT_PATTERNS = {}

if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    root = os.path.expanduser(sys.argv[1])
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "mm2cars")
    manifest = json.load(open(os.path.join(root, "cars", "manifest.json")))
    for car in manifest:
        folder = car["path"].strip("/").split("/")[-1]
        PAINT_PATTERNS[folder] = car.get("bodyTextures", {})
    for car_id, folder, paint in CARS:
        export(root, out_dir, car_id, folder, paint)
    export_gate(root, out_dir)
    export_trailer(root, out_dir)
    export_street(root, out_dir)
    write_sim_data(out_dir)
