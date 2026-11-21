#!/usr/bin/env python3
"""Generates legacy launcher PNGs (API 21-25) matching the adaptive icon.

Pure stdlib on purpose: no Pillow/ImageMagick required.
Run from the repository root: python3 tools/generate_icons.py
"""

import os
import struct
import zlib

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BACKGROUND = (0x0F, 0x76, 0x6E)
FOREGROUND = (255, 255, 255)
SUPERSAMPLE = 4


def inside_round_rect(x, y, x0, y0, x1, y1, radius):
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = min(max(x, x0 + radius), x1 - radius)
    cy = min(max(y, y0 + radius), y1 - radius)
    dx, dy = x - cx, y - cy
    return dx * dx + dy * dy <= radius * radius


def render(size, round_icon=False):
    n = size * SUPERSAMPLE
    radius = 0.22 * n if not round_icon else n / 2
    bars = [
        (0.20 * n, 0.275 * n, 0.80 * n, 0.365 * n),
        (0.20 * n, 0.455 * n, 0.66 * n, 0.545 * n),
        (0.20 * n, 0.635 * n, 0.80 * n, 0.725 * n),
    ]
    bars = [(x0, y0, x1, y1, (y1 - y0) / 2) for x0, y0, x1, y1 in bars]

    rows = []
    for py in range(n):
        row = []
        for px in range(n):
            x, y = px + 0.5, py + 0.5
            if round_icon:
                # Circular mask for the round launcher icon.
                dx, dy = x - n / 2, y - n / 2
                if dx * dx + dy * dy > (n / 2) ** 2:
                    row.append((0, 0, 0, 0))
                    continue
                inside = True
            elif not inside_round_rect(x, y, 0, 0, n, n, radius):
                row.append((0, 0, 0, 0))
                continue
            else:
                inside = True
            color = BACKGROUND
            if inside:
                for x0, y0, x1, y1, r in bars:
                    if inside_round_rect(x, y, x0, y0, x1, y1, r):
                        color = FOREGROUND
                        break
            row.append((color[0], color[1], color[2], 255))
        rows.append(row)

    out = []
    for oy in range(size):
        row = []
        for ox in range(size):
            r = g = b = a = 0
            for dy in range(SUPERSAMPLE):
                for dx in range(SUPERSAMPLE):
                    pr, pg, pb, pa = rows[oy * SUPERSAMPLE + dy][ox * SUPERSAMPLE + dx]
                    r += pr * pa
                    g += pg * pa
                    b += pb * pa
                    a += pa
            if a == 0:
                row.append((0, 0, 0, 0))
            else:
                row.append((r // a, g // a, b // a, a // (SUPERSAMPLE * SUPERSAMPLE)))
        out.append(row)
    return out


def write_png(path, rows):
    height = len(rows)
    width = len(rows[0])
    raw = b"".join(b"\x00" + bytes(v for px in row for v in px) for row in rows)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as handle:
        handle.write(png)


def main():
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
    for dpi, size in SIZES.items():
        directory = os.path.join(base, f"mipmap-{dpi}")
        os.makedirs(directory, exist_ok=True)
        write_png(os.path.join(directory, "ic_launcher.png"), render(size))
        write_png(os.path.join(directory, "ic_launcher_round.png"), render(size, round_icon=True))
        print(f"mipmap-{dpi}: {size}x{size}")


if __name__ == "__main__":
    main()
