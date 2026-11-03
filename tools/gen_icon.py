#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 FFCompress 启动图标的旧版位图（API 24~25 用；API 26+ 走 mipmap-anydpi-v26 的自适应矢量图标）。

设计：紫罗兰渐变底 + 白色视频画框 + 左右对向箭头（「把视频往里压」）。
几何与 res/drawable/ic_launcher_foreground.xml 完全一致，保证新旧图标观感统一。

用法：python tools/gen_icon.py
输出：app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher[_round].png
"""
import math
import os
import struct
import zlib

# ---------- 几何（归一化坐标 0~1，与矢量图 108dp 画布中的 72dp 内容区对应） ----------
FRAME = (0.18, 0.28, 0.82, 0.72)   # 视频画框外接矩形
FRAME_RADIUS = 0.08
FRAME_STROKE = 0.048
ARROW_STROKE = 0.012               # 箭头自身的描边半径（让尖角更敦实）

# 箭头多边形：杆(0.293→0.41, 高0.025) + 三角头(0.41→0.48, 半高0.08)，与矢量图路径一致
LEFT_ARROW = [
    (0.293, 0.475), (0.41, 0.475),
    (0.41, 0.42), (0.48, 0.50),
    (0.41, 0.58), (0.41, 0.525),
    (0.293, 0.525),
]
RIGHT_ARROW = [(1 - x, y) for (x, y) in LEFT_ARROW]

GRAD_START = (0x8B, 0x6C, 0xF0)
GRAD_END = (0x43, 0x31, 0x8F)

# ---------- 基础判定 ----------


def in_rrect(x, y, x0, y0, x1, y1, r):
    """点是否落在圆角矩形内"""
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    dx, dy = x - cx, y - cy
    return dx * dx + dy * dy <= r * r


def in_circle(x, y, cx=0.5, cy=0.5, r=0.5):
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def dist_seg(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def in_polygon(px, py, pts):
    inside = False
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        if (y1 > py) != (y2 > py):
            xt = (py - y1) * (x2 - x1) / (y2 - y1) + x1
            if px < xt:
                inside = not inside
    return inside


def in_arrow(px, py, pts):
    if in_polygon(px, py, pts):
        return True
    n = len(pts)
    return any(dist_seg(px, py, *pts[i], *pts[(i + 1) % n]) <= ARROW_STROKE for i in range(n))


def in_frame_ring(px, py):
    x0, y0, x1, y1 = FRAME
    h = FRAME_STROKE / 2
    outer = in_rrect(px, py, x0 - h, y0 - h, x1 + h, y1 + h, FRAME_RADIUS + h)
    inner = in_rrect(px, py, x0 + h, y0 + h, x1 - h, y1 - h, max(0.0, FRAME_RADIUS - h))
    return outer and not inner


# ---------- 渲染 ----------

SS = 4  # 每个像素 4x4 超采样


def render(size, round_icon):
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            bg_cov = glyph_cov = 0.0
            rgb = [0.0, 0.0, 0.0]
            for sy in range(SS):
                for sx in range(SS):
                    x = (px + (sx + 0.5) / SS) / size
                    y = (py + (sy + 0.5) / SS) / size
                    hit_bg = in_circle(x, y) if round_icon else in_rrect(x, y, 0.0, 0.0, 1.0, 1.0, 0.22)
                    if not hit_bg:
                        continue
                    bg_cov += 1
                    t = (x + y) / 2
                    for i in range(3):
                        rgb[i] += GRAD_START[i] + (GRAD_END[i] - GRAD_START[i]) * t
                    if in_frame_ring(x, y) or in_arrow(x, y, LEFT_ARROW) or in_arrow(x, y, RIGHT_ARROW):
                        glyph_cov += 1
            total = SS * SS
            if bg_cov == 0:
                row += bytes((0, 0, 0, 0))
                continue
            r = rgb[0] / bg_cov
            g = rgb[1] / bg_cov
            b = rgb[2] / bg_cov
            a = bg_cov / total
            w = glyph_cov / bg_cov  # 白色字形在该像素内的占比
            r = r * (1 - w) + 255 * w
            g = g * (1 - w) + 255 * w
            b = b * (1 - w) + 255 * w
            row += bytes((int(r + 0.5), int(g + 0.5), int(b + 0.5), int(a * 255 + 0.5)))
        rows.append(bytes(row))
    return write_png(size, size, rows)


def write_png(w, h, rows):
    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
            chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, size in dens.items():
        d = os.path.join(root, "app", "src", "main", "res", f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        for round_icon, fname in ((False, "ic_launcher.png"), (True, "ic_launcher_round.png")):
            data = render(size, round_icon)
            with open(os.path.join(d, fname), "wb") as f:
                f.write(data)
            print(f"  {d}/{fname}  {size}x{size}")


if __name__ == "__main__":
    main()
