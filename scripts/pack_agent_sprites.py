#!/usr/bin/env python3
"""
Packs an agent's animations into the format SpriteSheet reads.

Input: a folder of animated WebPs, one per animation, named after it and drawn at 4x with nearest-neighbour
scaling. Output: each distinct frame once, at 1x, in 8x8 grids (sheet<n>.webp), plus animations.json with the
frame indexes (-1 for an empty frame) and durations of every animation.

    python3 scripts/pack_agent_sprites.py <animated webp folder> app/src/main/assets/agents/<agent id>
"""
import hashlib, json, os, struct, sys
import numpy as np
from PIL import Image

SCALE, COLS, ROWS = 4, 8, 8


def durations(path):
    # Frame durations straight from the ANMF chunks, which Pillow doesn't report
    b = open(path, 'rb').read()
    out, i = [], 12
    while i + 8 <= len(b):
        tag, size = b[i:i + 4], struct.unpack('<I', b[i + 4:i + 8])[0]
        if tag == b'ANMF':
            out.append(int.from_bytes(b[i + 20:i + 23], 'little'))
        i += 8 + size + (size & 1)
    return out


def main(src, out):
    frames, index, anims = [], {}, {}
    fw = fh = None
    for name in sorted(n[:-5] for n in os.listdir(src) if n.endswith('.webp')):
        path = os.path.join(src, name + '.webp')
        im = Image.open(path)
        n = getattr(im, 'n_frames', 1)
        durs = durations(path) if n > 1 else [0]
        ids = []
        for i in range(n):
            im.seek(i)
            small = np.array(im.convert('RGBA'))[::SCALE, ::SCALE].copy()
            small[small[..., 3] == 0] = 0
            fh, fw = small.shape[:2]
            if not small[..., 3].any():
                ids.append(-1)
                continue
            key = hashlib.sha1(small.tobytes()).hexdigest()
            if key not in index:
                index[key] = len(frames)
                frames.append(small)
            ids.append(index[key])
        anims[name] = {'frames': ids, 'durations': durs}
    os.makedirs(out, exist_ok=True)
    per = COLS * ROWS
    for s in range((len(frames) + per - 1) // per):
        chunk = frames[s * per:(s + 1) * per]
        rows = (len(chunk) + COLS - 1) // COLS
        page = np.zeros((rows * fh, COLS * fw, 4), np.uint8)
        for i, f in enumerate(chunk):
            r, c = divmod(i, COLS)
            page[r * fh:(r + 1) * fh, c * fw:(c + 1) * fw] = f
        Image.fromarray(page).save(os.path.join(out, f'sheet{s}.webp'), lossless=True, quality=100, method=6)
    with open(os.path.join(out, 'animations.json'), 'w') as f:
        json.dump({'frameWidth': fw, 'frameHeight': fh, 'columns': COLS, 'framesPerSheet': per, 'animations': anims},
                  f, separators=(',', ':'))
    print(f'{src}: {len(frames)} frames')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
