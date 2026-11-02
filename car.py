"""
CarPlay 状态识别原型（输入：CarPlay 视频帧的灰度/Y 平面）

1. dock_icon(): 侧栏底部图标是 九宫格(grid=Dashboard) 还是 方框(square=全屏App/地图放大)
2. top_buttons(): 地图放大模式下，顶部是否有胶囊按钮（与按钮文字/数量无关）

所有坐标定义在 1200x900 的参考帧上，运行时按实际分辨率等比缩放。
照片输入仅用于验证：先透视校正成 CarPlay 帧（视频流不需要这一步）。
"""
import sys
import time

import cv2
import numpy as np

REF_W, REF_H = 1200, 900

# ---- 参考帧上的 ROI（x0, y0, x1, y1）----
DOCK_ICON_ROI = (15, 810, 105, 895)      # 侧栏底部图标（视频流中可收紧到 ~40x40）
TOP_BAND_ROI = (95, 0, 1200, 110)        # 顶部按钮条（侧栏右侧）

# ---- 按钮几何（参考帧像素）----
PILL_HALF_TEXT = 17          # 文字行半高（字高 ~27-32px）
PILL_MARGIN_IN = 21          # 文字到上/下留白带的距离
PILL_MARGIN_OUT = 29         # 留白带外沿（仍在胶囊内部，胶囊半高 ~37）
TEXT_GAP = 14                # 同一按钮内字间距上限
TEXT_MIN_W, TEXT_MAX_W = 20, 240
TEXT_MIN_H = 16              # 按钮文字最小高度（地图小标注更矮）
PAD = 12                     # 文字两侧需要的均匀留白（画框用）
PAD_IN = 6                   # 文字紧邻的内留白：贴右屏/相邻按钮时 12px 不够，但紧邻的 6px 必须安静
CARD_SECOND_LINE = 30        # 卡片第二行文字的最小跨度（按钮只有一行文字）
HP_K = 9                     # 高通核大小
GLYPH_HP = 30                # 字形高通阈值
QUIET_MAX = 18               # 留白带高通上限


# ---------------------------------------------------------------- utils
def _scale_roi(roi, w, h):
    sx, sy = w / REF_W, h / REF_H
    x0, y0, x1, y1 = roi
    return int(x0 * sx), int(y0 * sy), int(x1 * sx), int(y1 * sy)


def _to_ref(gray, roi):
    """裁剪 ROI 并缩放到参考尺度（视频分辨率固定时可预先算好，这里为通用性）。"""
    h, w = gray.shape
    x0, y0, x1, y1 = _scale_roi(roi, w, h)
    crop = gray[y0:y1, x0:x1]
    rw, rh = roi[2] - roi[0], roi[3] - roi[1]
    if crop.shape != (rh, rw):
        crop = cv2.resize(crop, (rw, rh), interpolation=cv2.INTER_AREA)
    return crop


# ---------------------------------------------------------------- 1. dock icon
def dock_icon(gray, debug=None):
    """返回 ('grid'|'square'|'unknown', info)。"""
    roi = _to_ref(gray, DOCK_ICON_ROI)
    roi = cv2.GaussianBlur(roi, (3, 3), 0)
    bg = np.median(roi)
    fg = (np.abs(roi.astype(np.int16) - int(bg)) > 60).astype(np.uint8)

    n, lab, st, cen = cv2.connectedComponentsWithStats(fg, connectivity=8)
    H, W = fg.shape
    comps = []
    for i in range(1, n):
        x, y, w, h, a = st[i]
        if a < 6 or x == 0 or y == 0 or x + w >= W or y + h >= H:
            continue  # 去掉噪点和贴边的侧栏边框
        comps.append((x, y, w, h, a))

    info = {"n_comp": len(comps)}
    if debug is not None:
        debug["dock_fg"] = fg * 255

    # 九宫格：>=7 个近似等大的小方块，排成 3x3
    small = [c for c in comps if 3 <= c[2] <= 14 and 3 <= c[3] <= 14]
    if len(small) >= 7:
        areas = np.array([c[4] for c in small], float)
        xs = np.array([c[0] + c[2] / 2 for c in small])
        ys = np.array([c[1] + c[3] / 2 for c in small])
        uniform = areas.std() / areas.mean() < 0.5
        span_x, span_y = np.ptp(xs), np.ptp(ys)
        info.update(cells=len(small), uniform=bool(uniform), span=(span_x, span_y))
        if uniform and 8 < span_x < 40 and 8 < span_y < 40 and 0.7 < span_x / max(span_y, 1) < 1.4:
            return "grid", info

    # 方框：一个近似正方形的环（中心空）
    for x, y, w, h, a in comps:
        if 14 <= w <= 45 and 14 <= h <= 45 and 0.75 < w / h < 1.33:
            fill = a / float(w * h)
            cx, cy = x + w // 2, y + h // 2
            center_empty = fg[cy - h // 5:cy + h // 5 + 1, cx - w // 5:cx + w // 5 + 1].mean() < 0.1
            info.update(box=(x, y, w, h), fill=round(fill, 2), center_empty=bool(center_empty))
            if fill < 0.55 and center_empty:
                return "square", info
    return "unknown", info


# ---------------------------------------------------------------- 2. top buttons
def _runs(mask, max_gap=3):
    """mask 中 True 的连续段（允许 <=max_gap 的小缺口），返回 [(s, e)]。"""
    idx = np.flatnonzero(mask)
    if idx.size == 0:
        return []
    out, s, p = [], idx[0], idx[0]
    for i in idx[1:]:
        if i - p > max_gap + 1:
            out.append((s, p + 1))
            s = i
        p = i
    out.append((s, p + 1))
    return out


def highpass(band):
    """|I - box(I)|：保留锐利笔画，抑制半透明胶囊下被模糊的地图（极性无关，日/夜通用）。"""
    b = band.astype(np.int16)
    return np.abs(b - cv2.blur(band, (HP_K, HP_K)).astype(np.int16)).astype(np.uint8)


def find_pills(hp, yc):
    """在高通图 hp 中以 yc 为文字中心行，找“胶囊按钮”候选。

    胶囊按钮共性（与文字内容/数量无关）：
      - 文字上下各有一条“安静”的留白带（胶囊内部，透出的地图被模糊，无锐利边缘）
      - 中间文字行内有高频字形
      - 文字两侧也是安静留白（文字不贴边），文字高度在固定范围
    """
    a0, a1 = yc - PILL_MARGIN_OUT, yc - PILL_MARGIN_IN
    b0, b1 = yc + PILL_MARGIN_IN, yc + PILL_MARGIN_OUT
    t0, t1 = yc - PILL_HALF_TEXT, yc + PILL_HALF_TEXT
    if a0 < 0 or b1 > hp.shape[0]:
        return []

    quiet = np.maximum(hp[a0:a1].max(0), hp[b0:b1].max(0)) < QUIET_MAX   # 每列留白带安静
    glyph_px = hp[t0:t1] > GLYPH_HP
    glyph_col = (glyph_px.sum(0) >= 2) & quiet

    W = hp.shape[1]
    # 行内（文字高度带）无“强边缘”：真按钮边界是半透明过渡（hp≈25），地图白字/纹理更强
    inrow = hp[t0:t1].max(0) <= 60
    pills = []
    # 文字优先：字形列聚成簇（同一按钮内字间距 < TEXT_GAP），再验证两侧留白；
    # 图标（圆环等）笔画间断大，再用大 gap 合并一次，只取图标宽度范围的窄高段
    good = [r for r in _runs(glyph_col, max_gap=TEXT_GAP) if TEXT_MIN_W <= r[1] - r[0] <= TEXT_MAX_W]
    icon = [r for r in _runs(glyph_col, max_gap=TEXT_GAP + 18)
            if TEXT_MIN_W <= r[1] - r[0] <= TEXT_MAX_W // 4 and r not in good]
    for s, e in good + icon:
        tw = e - s
        frac = glyph_col[s:e].mean()
        if frac < 0.3:
            continue
        # 字形纵向高度：按钮文字高度固定，排除小号地图标注
        ri = np.flatnonzero(glyph_px[:, s:e].any(axis=1))
        th = ri[-1] - ri[0] + 1
        if th < TEXT_MIN_H:
            continue
        # 文字两侧用留白带（quiet）判断：字形边缘 1~2 列和按钮自身边缘在行内必然高通，
        # 而留白带（按钮内 padding）必须安静。图标（顶满按钮、比文字窄）天然留白更窄，阈值放宽。
        narrow = th >= TEXT_MIN_H + 14 and tw < TEXT_MAX_W // 5
        need = 0.5 if narrow else 0.8
        sl, sr = slice(max(s - PAD_IN, 0), s), slice(e, min(e + PAD_IN, W))
        lp = quiet[sl] & inrow[sl]
        rp = quiet[sr] & inrow[sr]
        if lp.size < PAD_IN // 2 or lp.mean() < need:
            continue
        if rp.size >= PAD_IN // 2 and rp.mean() < need:     # 文字右侧紧邻处必须安静（按钮内 padding）
            continue
        # 导航卡片（两行文字）不是按钮：候选文字下方还有一行安静背景上的文字
        below = hp[yc + PILL_HALF_TEXT + 21: yc + PILL_HALF_TEXT + 43]
        if below.size:
            row = (below > GLYPH_HP).sum(0) >= 2
            segs = [r for r in _runs(row[s:e], max_gap=30) if r[1] - r[0] >= CARD_SECOND_LINE]
            if segs and quiet[s + segs[0][0]: s + segs[0][1]].mean() > 0.3:
                continue
        ls, re_ = max(s - PAD, 0), min(e + PAD, W)
        pills.append((int(ls), int(re_), round(float(frac), 2)))
    return pills


YC_SEARCH = range(42, 64, 2)   # 照片校正有 ~10px 误差才需要搜索；视频流固定一个值即可


def top_buttons(gray, yc_range=YC_SEARCH):
    """返回 (shown: bool, pills[(x0, x1, text_frac, yc)])。"""
    hp = highpass(cv2.GaussianBlur(_to_ref(gray, TOP_BAND_ROI), (3, 3), 0))
    cands = []
    for yc in yc_range:
        cands += [(s, e, f, yc) for s, e, f in find_pills(hp, yc)]
    cands.sort()
    merged = []                       # 同一按钮在相邻 yc 下会重复命中，按区间去重
    for p in cands:
        if merged and p[0] < merged[-1][1]:
            continue
        merged.append(p)
    return len(merged) >= 1, merged


# ---------------------------------------------------------------- 照片 -> CarPlay 帧（仅验证用）
PHOTO_QUADS = {
    "xx.jpg": [(163, 182), (2615, 225), (2662, 1990), (92, 2052)],
    "xxx.jpg": [(298, 300), (2445, 435), (2430, 1905), (250, 1930)],
}


def rectify(img, quad):
    M = cv2.getPerspectiveTransform(
        np.float32(quad), np.float32([(0, 0), (REF_W, 0), (REF_W, REF_H), (0, REF_H)]))
    return cv2.warpPerspective(img, M, (REF_W, REF_H))


def analyze(gray):
    icon, info = dock_icon(gray)
    res = {"dock_icon": icon, "dock_info": info}
    if icon == "grid":
        res["mode"] = "dashboard"
    elif icon == "square":
        res["mode"] = "fullscreen"
        shown, pills = top_buttons(gray)
        res["top_buttons"] = shown
        res["pills"] = pills
    else:
        res["mode"] = "unknown"
    return res


def draw(frame_bgr, res):
    v = frame_bgr.copy()
    x0, y0, x1, y1 = DOCK_ICON_ROI
    cv2.rectangle(v, (x0, y0), (x1, y1), (0, 0, 255), 2)
    cv2.putText(v, res["dock_icon"], (x0, y0 - 6), 0, 0.6, (0, 0, 255), 2)
    bx0, by0, _, _ = TOP_BAND_ROI
    for s, e, f, yc in res.get("pills", []):
        cv2.rectangle(v, (bx0 + s, by0 + yc - PILL_MARGIN_OUT),
                      (bx0 + e, by0 + yc + PILL_MARGIN_OUT), (0, 200, 0), 2)
    txt = "mode=%s buttons=%s" % (res["mode"], res.get("top_buttons", "-"))
    cv2.putText(v, txt, (300, 880), 0, 0.9, (0, 0, 255), 2)
    return v


def false_positive_scan(gray, name, x0=95, x1=1200, y_from=120, y_to=700):
    """负样本：把按钮检测器滑到没有顶部按钮的区域，统计误报。"""
    hp_full = highpass(cv2.GaussianBlur(gray, (3, 3), 0))
    hits, total = [], 0
    for top in range(y_from, y_to, 10):
        band = hp_full[top:top + 110, x0:x1]
        for yc in YC_SEARCH:
            p = find_pills(band, yc)
            if p:
                hits.append((top + yc, [(x0 + s, x0 + e) for s, e, _ in p]))
                break
        total += 1
    print("  [FP scan %s] 滑窗 %d 个位置，误报 %d %s" % (name, total, len(hits), hits))


if __name__ == "__main__":
    import os
    here = os.path.dirname(os.path.abspath(__file__))
    files = sys.argv[1:] or ["xx.jpg", "xxx.jpg"]
    for f in files:
        img = cv2.imread(os.path.join(here, f))
        frame = rectify(img, PHOTO_QUADS[f]) if f in PHOTO_QUADS else img
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)  # 视频流里直接用 Y 平面

        res = analyze(gray)
        n = 200
        t = time.perf_counter()
        for _ in range(n):
            analyze(gray)
        ms = (time.perf_counter() - t) / n * 1000

        print("%s: mode=%s dock=%s top_buttons=%s" % (
            f, res["mode"], res["dock_icon"], res.get("top_buttons", "-")))
        print("  dock_info:", res["dock_info"])
        if "pills" in res:
            print("  pills(x0,x1,text_frac,yc):", res["pills"])
        t = time.perf_counter()
        for _ in range(n):
            dock_icon(gray)
        ms_dock = (time.perf_counter() - t) / n * 1000
        t = time.perf_counter()
        for _ in range(n):
            top_buttons(gray, yc_range=(52,))
        ms_btn = (time.perf_counter() - t) / n * 1000
        print("  耗时: analyze %.3f ms | dock_icon %.3f ms | top_buttons(固定 yc) %.3f ms  (Python)"
              % (ms, ms_dock, ms_btn))
        if f == "xx.jpg":
            # Dashboard 帧的地图区（含速度圈）也当作负样本
            false_positive_scan(gray, f + " 地图区", x0=95, x1=700, y_from=0, y_to=700)
        else:
            false_positive_scan(gray, f + " 地图中部")
        # 标记图跟输入文件走；draw 的坐标定义在 1200x900 参考帧上，先归一尺度再画
        disp = frame if frame.shape[:2] == (REF_H, REF_W) else cv2.resize(frame, (REF_W, REF_H))
        out = os.path.join(os.path.dirname(os.path.abspath(f)),
                           "out_" + os.path.splitext(os.path.basename(f))[0] + ".png")
        if not cv2.imwrite(out, draw(disp, res)):
            print("  标记图写出失败:", out)
