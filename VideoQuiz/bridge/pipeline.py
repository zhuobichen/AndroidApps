#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
卷灵读卷流水线 (videoquiz-pipeline)

把「视频 -> 帧 -> 校正 -> OCR -> 题目结构化」这条链固化成可复用工具，
避免每个任务重写一遍脚本、反复摸索参数。

子命令:
  run      一键跑完全流程（推荐）
  probe    先摸清视频性质（尺寸/帧数/亮区占比）
  frames   抽帧
  dedup    按纵向位移去重
  rectify  透视校正
  ocr      调常驻 OCR 服务
  stitch   拼成长图
  parse    题目结构化 + 跨帧投票
  gap      对指定帧区间加密回补

设计原则:
  - 参数有默认值，不必每次试探；但每个阶段都输出诊断指标，
    越过红线时明确报警，而不是静默给出烂结果。
  - 任何阶段都能单独重跑，中间产物落盘可复用。
  - OCR 优先走常驻 HTTP 服务 (8766, CUDA 常驻)，失败自动回退本地。
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

DEFAULT = {
    "step": 10,
    "warp_width": 2000,
    "content_max_x": 1120,
    "dedup_keep_ratio": 0.10,
    "ncc_min": 0.90,
    "text_match_ratio": 0.92,
    "ocr_server": "http://127.0.0.1:8766",
}

NOISE_PATTERN = (
    r"^(正在监控|答题剩余时间|答题卡|已作答|未作答|更新|在线测评|保存|提交|"
    r"\d{1,2}:\d{2}:\d{2}|\d{1,4}[)）]?)$"
)


# ---- 基础工具 ----

def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def emit(obj):
    print("PIPELINE " + json.dumps(obj, ensure_ascii=False), flush=True)


def work_dir(job):
    d = os.path.join(job, "work")
    os.makedirs(d, exist_ok=True)
    return d


def write_json(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def read_json(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def need_cv2():
    try:
        import cv2
        import numpy as np
        return cv2, np
    except ImportError as exc:
        raise SystemExit("缺少 opencv/numpy: %s\n请先 pip install opencv-python numpy" % exc)


def _order_pts(np, pts):
    pts = np.array(pts, dtype=np.float32).reshape(4, 2)
    s = pts.sum(axis=1)
    d = np.diff(pts, axis=1).ravel()
    return np.array([pts[np.argmin(s)], pts[np.argmin(d)],
                     pts[np.argmax(s)], pts[np.argmax(d)]], dtype=np.float32)


def _find_page(cv2, np, gray, min_area=0.18):
    h, w = gray.shape
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    _, th = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    th = cv2.morphologyEx(th, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
    th = cv2.morphologyEx(th, cv2.MORPH_OPEN, np.ones((9, 9), np.uint8))
    cnts, _ = cv2.findContours(th, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return None, 0.0
    best = max(cnts, key=cv2.contourArea)
    area = cv2.contourArea(best) / (h * w)
    if area < min_area:
        return None, area
    return _order_pts(np, cv2.boxPoints(cv2.minAreaRect(best))), area


def _warp_to(cv2, np, img, quad, out_w):
    tl, tr, br, bl = quad
    mx = max(np.linalg.norm(br - bl), np.linalg.norm(tr - tl))
    my = max(np.linalg.norm(tr - br), np.linalg.norm(tl - bl))
    out_h = max(1, int(round(out_w * my / mx)))
    dst = np.array([[0, 0], [out_w - 1, 0], [out_w - 1, out_h - 1], [0, out_h - 1]], dtype=np.float32)
    return cv2.warpPerspective(img, cv2.getPerspectiveTransform(quad, dst), (out_w, out_h),
                               flags=cv2.INTER_CUBIC)


# ---- 1. 探针 ----

def cmd_probe(args):
    cv2, np = need_cv2()
    video = os.path.join(args.job, "video.mp4")
    if not os.path.exists(video):
        raise SystemExit("找不到视频: %s" % video)

    cap = cv2.VideoCapture(video)
    fps = cap.get(cv2.CAP_PROP_FPS) or 0
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)

    samples, means = [], []
    for i in range(9):
        if total <= 0:
            break
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(total * i / 9))
        ok, frame = cap.read()
        if not ok:
            continue
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        means.append(float(gray.mean()))
        hi = float(np.percentile(gray, 60))
        top = float(gray.max())
        thr = max(hi, top * 0.82)
        samples.append(float((gray >= thr).mean()))
    cap.release()

    bright_mean = sum(samples) / len(samples) if samples else 0.0
    mean_lum = sum(means) / len(means) if means else 0.0

    if bright_mean > 0.20 and mean_lum > 60:
        guess = "handheld"
    elif bright_mean > 0.05:
        guess = "mixed"
    else:
        guess = "dark"

    info = {
        "video": video,
        "size_mb": round(os.path.getsize(video) / 1048576, 1),
        "width": w, "height": h, "fps": round(fps, 2),
        "frames": total,
        "duration_s": round(total / fps, 1) if fps else None,
        "bright_ratio": round(bright_mean, 3),
        "mean_luma": round(mean_lum, 1),
        "orientation": "portrait" if h > w else "landscape",
        "guess_profile": guess,
    }
    emit(dict(stage="probe", ok=True, **info))
    write_json(os.path.join(work_dir(args.job), "probe.json"), info)
    return info


# ---- 2. 抽帧 ----

def cmd_frames(args):
    cv2, _ = need_cv2()
    video = os.path.join(args.job, "video.mp4")
    out = os.path.join(work_dir(args.job), "frames")
    os.makedirs(out, exist_ok=True)
    for name in os.listdir(out):
        os.remove(os.path.join(out, name))

    cap = cv2.VideoCapture(video)
    idx = saved = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if idx % max(1, args.step) == 0:
            cv2.imwrite(os.path.join(out, "f%05d.png" % idx), frame)
            saved += 1
        idx += 1
    cap.release()

    emit({"stage": "frames", "ok": saved > 0, "total": idx, "step": args.step, "saved": saved})
    return {"saved": saved}


# ---- 3. 去重 ----

def cmd_dedup(args):
    cv2, np = need_cv2()
    src = os.path.join(work_dir(args.job), "frames")
    files = sorted(f for f in os.listdir(src) if f.endswith(".png"))
    if not files:
        raise SystemExit("没有帧可去重，先跑 frames")

    prev = None
    keep, report = [], []
    for name in files:
        gray = cv2.cvtColor(cv2.imread(os.path.join(src, name)), cv2.COLOR_BGR2GRAY)
        scale = 480.0 / gray.shape[1]
        small = cv2.resize(gray, (480, max(1, int(gray.shape[0] * scale))))
        if prev is None:
            keep.append(name)
            report.append({"file": name, "ncc": 1.0, "dy": 0, "new": True})
            prev = small
            continue
        hh = min(prev.shape[0], small.shape[0])
        a = prev[:hh].astype(np.float32)
        best_ncc, best_dy = -1.0, 0
        for dy in range(-hh // 2, hh // 2, 8):
            if dy >= 0:
                aa, bb = a[:hh - dy], small[dy:hh]
            else:
                aa, bb = a[-dy:hh], small[:hh + dy]
            if aa.shape != bb.shape or aa.size == 0:
                continue
            ncc = float(np.corrcoef(aa.ravel(), bb.ravel())[0, 1])
            if ncc > best_ncc:
                best_ncc, best_dy = ncc, dy
        is_new = not (best_ncc >= args.ncc_min and abs(best_dy) < 8)
        report.append({"file": name, "ncc": round(best_ncc, 3), "dy": best_dy, "new": is_new})
        if is_new:
            keep.append(name)
        prev = small

    ratio = len(keep) / len(files)
    write_json(os.path.join(work_dir(args.job), "dedup_report.json"),
               {"total": len(files), "keep": len(keep), "ratio": round(ratio, 3), "rows": report})

    warn = None
    if ratio < args.min_ratio:
        warn = ("去重保留率 %.1f%% 低于红线 %.0f%%：这段视频不适合按位移去重，"
                "改用逐帧全留 + OCR 文本去重" % (ratio * 100, args.min_ratio * 100))
        log("警告: " + warn)

    emit({"stage": "dedup", "ok": True, "total": len(files), "keep": len(keep),
          "ratio": round(ratio, 3), "warn": warn})
    return {"keep": keep, "warn": warn}


# ---- 4. 透视校正 ----

def cmd_rectify(args):
    cv2, np = need_cv2()
    src = os.path.join(work_dir(args.job), "frames")
    out = os.path.join(work_dir(args.job), "warp")
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        os.remove(os.path.join(out, f))

    files = sorted(f for f in os.listdir(src) if f.endswith(".png"))
    if not files:
        raise SystemExit("没有帧可校正，先跑 frames")

    W = args.warp_width
    rows, ok = [], 0
    for name in files:
        img = cv2.imread(os.path.join(src, name))
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        quad, area = _find_page(cv2, np, gray)
        if quad is None:
            out_img = cv2.resize(img, (W, max(1, int(img.shape[0] * W / img.shape[1]))))
            rows.append({"file": name, "ok": False, "fallback": "resize", "area": round(area, 3)})
        else:
            out_img = _warp_to(cv2, np, img, quad, W)
            rows.append({"file": name, "ok": True, "area": round(area, 3),
                         "size": [out_img.shape[1], out_img.shape[0]]})
            ok += 1
        cv2.imwrite(os.path.join(out, name), out_img)

    ratio = ok / float(len(files))
    warn = None
    if ratio < 0.6:
        warn = ("仅 %.0f%% 帧找到文档页：可能是纯录屏（无需校正）或拍摄角度太斜，"
                "建议 OCR 直接读 frames" % (ratio * 100))
        log("警告: " + warn)

    write_json(os.path.join(out, "warp_report.json"),
               {"files": len(files), "warped": ok, "ratio": round(ratio, 3), "rows": rows, "warn": warn})
    emit({"stage": "rectify", "ok": True, "files": len(files), "warped": ok,
          "ratio": round(ratio, 3), "warn": warn})
    return {"warped": ok, "warn": warn}


# ---- 5. OCR ----

_OCR_PORTS = [8766, 8767, 8768, 8769, 8770]


def discover_ocr_server(preferred=None, timeout=3):
    """找出真正在跑的 OCR 服务。

    不能只看端口是否监听：本机 8766 曾被别的程序长期占用，
    连接看似成功但 /health 返回 404。必须验证 health 里带 engine/ready 字段。
    """
    candidates = []
    if preferred:
        candidates.append(preferred)
    for p in _OCR_PORTS:
        url = "http://127.0.0.1:%d" % p
        if url not in candidates:
            candidates.append(url)

    for base in candidates:
        try:
            with urllib.request.urlopen(base.rstrip("/") + "/health", timeout=timeout) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            if data.get("engine") or data.get("ready") is not None:
                log("OCR 服务: %s (engine=%s)" % (base, data.get("engine")))
                return base
        except Exception:
            continue
    return None


def _ocr_via_server(paths, server, timeout=1800):
    body = json.dumps({"paths": paths}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(server.rstrip("/") + "/ocr", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as exc:
        log("OCR 请求失败 (%s)" % exc)
        return None


def cmd_ocr(args):
    sub = "warp" if args.src == "warp" else "frames"
    src = os.path.join(work_dir(args.job), sub)
    if not os.path.isdir(src) or not any(f.endswith(".png") for f in os.listdir(src)):
        sub = "frames"
        src = os.path.join(work_dir(args.job), "frames")
    files = sorted(f for f in os.listdir(src) if f.endswith(".png"))
    if not files:
        raise SystemExit("没有图片可 OCR")

    paths = [os.path.join(src, f) for f in files]
    server = discover_ocr_server(args.ocr_server)
    if server is None:
        raise SystemExit("找不到在跑的 OCR 服务：先在设置里启动桥接（它会拉起 OCR），"
                         "或手工执行 python ocr_server.py --port 8767")
    args.ocr_server = server
    t0 = time.time()
    result = _ocr_via_server(paths, server)
    engine = "server"
    if result is None:
        bridge = os.path.dirname(os.path.abspath(__file__))
        ocr_py = os.path.join(bridge, "ocr.py")
        out_json = os.path.join(work_dir(args.job), "ocr_%s.json" % sub)
        cmd = [sys.executable, ocr_py, "--server", args.ocr_server, "--out", out_json, src]
        log("回退执行: " + " ".join(cmd))
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            raise SystemExit("OCR 失败: %s" % proc.stderr[-800:])
        result = read_json(out_json, {})
        engine = "local"

    elapsed = time.time() - t0
    out_path = os.path.join(work_dir(args.job), "ocr_%s.json" % sub)
    write_json(out_path, result)

    if isinstance(result, list):
        results = result
    elif isinstance(result, dict):
        results = result.get("results") or result.get("items") or []
    else:
        results = []

    n = len(results)
    per = (elapsed / n) if n else None
    accel = "cuda" if (per is not None and per < 1.0) else "cpu_or_slow"
    warn = None
    if engine == "server" and accel != "cuda" and n >= 10:
        warn = "每帧 %.2fs，疑似退到 CPU（真 CUDA 应 <1.0s/帧）" % per
        log("警告: " + warn)

    emit({"stage": "ocr", "ok": n > 0, "images": n, "seconds": round(elapsed, 1),
          "per_frame_s": round(per, 2) if per else None,
          "engine": engine, "accel_guess": accel, "src": sub, "warn": warn})
    return {"results": results, "src": sub}


# ---- 6. 拼接长图 ----

def cmd_stitch(args):
    cv2, np = need_cv2()
    work = work_dir(args.job)
    data = read_json(os.path.join(work, "ocr_warp.json")) or read_json(os.path.join(work, "ocr_frames.json"))
    if not data:
        raise SystemExit("没有 OCR 结果，先跑 ocr")
    results = data.get("results", data if isinstance(data, list) else [])
    src = os.path.join(work, "warp")
    if not os.path.isdir(src):
        src = os.path.join(work, "frames")

    def fkey(name):
        m = re.search(r"f(\d+)", name)
        return int(m.group(1)) if m else 0

    def norm(s):
        return re.sub(r"[\s，。、；：（）()\[\]【】\"'·.,:;!?—\-_/\\|]+", "", s)

    by_file = {r["file"]: r for r in results if isinstance(r, dict) and "error" not in r and "file" in r}
    names = sorted([n for n in by_file if os.path.exists(os.path.join(src, n))], key=fkey)
    if len(names) < 2:
        emit({"stage": "stitch", "ok": False, "reason": "有效帧不足以拼接"})
        return {"ok": False}

    def rows_of(lines):
        out = []
        for l in lines:
            b = l["box"]
            if (b[0][0] + b[2][0]) / 2 >= DEFAULT["content_max_x"]:
                continue
            t = l["text"].strip()
            if len(norm(t)) < 3:
                continue
            out.append((b[0][1], t))
        out.sort()
        return out

    per = {n: rows_of(by_file[n].get("lines", [])) for n in names}
    first = cv2.imread(os.path.join(src, names[0]))
    H, W = first.shape[:2]

    dys, diag, prev_dy = [], [], 0
    for i in range(1, len(names)):
        a, b = per[names[i - 1]], per[names[i]]
        cand = []
        for ya, ta in a:
            na = norm(ta)
            best, bestr = None, 0.0
            for yb, tb in b:
                r = difflib.SequenceMatcher(None, na, norm(tb)).ratio()
                if r > bestr:
                    best, bestr = yb, r
            if bestr >= DEFAULT["text_match_ratio"] and best is not None:
                cand.append(best - ya)
        dy = int(np.median(cand)) if cand else prev_dy
        dys.append(dy)
        diag.append({"pair": [names[i - 1], names[i]], "dy": dy, "matches": len(cand)})
        prev_dy = dy

    offset = [0]
    for d in dys:
        offset.append(offset[-1] + d)
    base = min(0, min(offset))
    offsets = [o - base for o in offset]
    total_h = max(offsets) + H
    canvas = np.zeros((total_h, W, 3), np.uint8)
    for i, n in enumerate(names):
        im = cv2.imread(os.path.join(src, n))
        if im.shape[0] != H or im.shape[1] != W:
            im = cv2.resize(im, (W, H), interpolation=cv2.INTER_AREA)
        canvas[offsets[i]:offsets[i] + H, :] = im

    cv2.imwrite(os.path.join(work, "panorama.png"), canvas)

    sl = os.path.join(work, "slices")
    os.makedirs(sl, exist_ok=True)
    for f in os.listdir(sl):
        os.remove(os.path.join(sl, f))
    step, k = 2200, 0
    for y in range(0, canvas.shape[0], step):
        cv2.imwrite(os.path.join(sl, "slice_%02d.png" % k), canvas[y:y + step])
        k += 1

    report = {"frames": len(names), "canvas": [canvas.shape[1], canvas.shape[0]],
              "slices": k, "dys": dys, "diag": diag, "offsets": offsets}
    write_json(os.path.join(work, "panorama_report.json"), report)

    nz = sum(1 for d in dys if d != 0)
    warn = None
    if len(dys) >= 5 and nz / float(len(dys)) < 0.2:
        warn = "只有 %d/%d 帧发生位移：可能没滚动，或抽取的帧来自同一页" % (nz, len(dys))
        log("警告: " + warn)

    emit({"stage": "stitch", "ok": True, "frames": len(names), "canvas_h": canvas.shape[0],
          "slices": k, "moved_pairs": nz, "warn": warn})
    return {"ok": True, "report": report}


# ---- 7. 题目结构化 ----

# 题号必须是 1 开头（0 号不存在），且后面紧跟点/顿号；避免把 "D. 选项" 之类误判进来
QRE = re.compile(r"^\s*([1-9]\d{0,2})\s*[.．、]\s*(\S.*)$")
OPTRE = re.compile(r"^\s*([A-Da-d])\s*[.．、]\s*(\S.*)$")


def _norm_text(s):
    return re.sub(r"\s+", "", s)


def _medoid(variants):
    if not variants:
        return "", 0.0
    if len(variants) == 1:
        return variants[0], 1.0
    best, best_score = variants[0], -1.0
    for a in variants:
        s = sum(1 for b in variants
                if difflib.SequenceMatcher(None, _norm_text(a), _norm_text(b)).ratio() >= 0.9)
        s -= len(_norm_text(a)) / 1000.0
        if s > best_score:
            best, best_score = a, s
    support = sum(1 for b in variants
                  if difflib.SequenceMatcher(None, _norm_text(best), _norm_text(b)).ratio() >= 0.9)
    return best, round(support / float(len(variants)), 2)


def cmd_parse(args):
    work = work_dir(args.job)
    data = read_json(os.path.join(work, "ocr_warp.json")) or read_json(os.path.join(work, "ocr_frames.json"))
    if not data:
        raise SystemExit("没有 OCR 结果，先跑 ocr")
    results = data.get("results", data if isinstance(data, list) else [])

    noise = re.compile(NOISE_PATTERN)
    bucket = {}

    for r in results:
        if not isinstance(r, dict) or "error" in r:
            continue
        body = []
        for l in r.get("lines", []):
            b = l["box"]
            if (b[0][0] + b[2][0]) / 2 >= DEFAULT["content_max_x"]:
                continue
            t = l["text"].strip()
            if not t or noise.match(t):
                continue
            body.append((b[0][1], b[0][0], t))
        body.sort()

        cur = None
        for _y, _x, t in body:
            m = QRE.match(t)
            if m:
                qid = m.group(1)
                bucket.setdefault(qid, {"stems": [], "opts": {}})
                bucket[qid]["stems"].append(m.group(2))
                cur = qid
                continue
            om = OPTRE.match(t)
            if om and cur is not None:
                bucket[cur]["opts"].setdefault(om.group(1).upper(), []).append(om.group(2))

    questions, low = [], []
    for qid in sorted(bucket, key=lambda x: int(x)):
        info = bucket[qid]
        stem, sup = _medoid(info["stems"])
        # 同一题号的多种题干如果差异过大，说明是跨页串号（视频里不同页出现了相同编号），
        # 此时只保留支持度最高的那一个来源，避免把两页内容搅在一起。
        stems = info["stems"]
        if len(stems) > 1:
            best_group = []
            for a in stems:
                grp = [b for b in stems
                       if difflib.SequenceMatcher(None, _norm_text(a), _norm_text(b)).ratio() >= 0.8]
                if len(grp) > len(best_group):
                    best_group = grp
            if len(best_group) * 2 < len(stems):
                low.append("%s(跨页串号)" % qid)
                stems = best_group
                stem, sup = _medoid(stems)
        opts = {}
        for k, vs in sorted(info["opts"].items()):
            v, s = _medoid(vs)
            # 选项长度悬殊（如 "300%%" 与一整句）多半也是串号，取支持度最高者并标注
            if s < 0.5:
                low.append("%s%s" % (qid, k))
            opts[k] = v
        if sup < 0.6:
            low.append(qid)
        questions.append({
            "id": qid,
            "type": "choice" if opts else "blank",
            "stem": stem,
            "options": opts or None,
            "support": sup,
            "incomplete": bool(opts) and len(opts) < 4,
        })

    out = {"questionCount": len(questions), "questions": questions, "lowConfidence": low}
    write_json(os.path.join(work, "questions_draft.json"), out)

    incomplete = [q["id"] for q in questions if q["incomplete"]]
    warn = None
    if incomplete:
        warn = "这些题选项不全（<4 项）: %s" % ", ".join(incomplete[:12])
        log("警告: " + warn)
    if low:
        log("警告: 低置信条目 %d 处: %s" % (len(low), ", ".join(low[:12])))

    emit({"stage": "parse", "ok": len(questions) > 0, "questions": len(questions),
          "incomplete": len(incomplete), "low_confidence": len(low), "warn": warn})
    return out


# ---- 8. 回补 ----

def cmd_gap(args):
    cv2, np = need_cv2()
    ranges = json.loads(args.ranges)
    work = work_dir(args.job)
    outdir = os.path.join(work, "gap")
    os.makedirs(outdir, exist_ok=True)
    for f in os.listdir(outdir):
        os.remove(os.path.join(outdir, f))

    want = set()
    for start, end, step in ranges:
        for f in range(int(start), int(end) + 1, max(1, int(step))):
            want.add(f)

    cap = cv2.VideoCapture(os.path.join(args.job, "video.mp4"))
    saved, idx = 0, 0
    while want:
        ok, frame = cap.read()
        if not ok:
            break
        if idx in want:
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            quad, _area = _find_page(cv2, np, gray)
            img = frame if quad is None else _warp_to(cv2, np, frame, quad, DEFAULT["warp_width"])
            cv2.imwrite(os.path.join(outdir, "g%05d.png" % idx), img)
            saved += 1
            want.discard(idx)
        idx += 1
    cap.release()

    if saved == 0:
        emit({"stage": "gap", "ok": False, "reason": "在给定区间里没抽到帧"})
        return {"ok": False}

    paths = sorted(os.path.join(outdir, f) for f in os.listdir(outdir))
    server = discover_ocr_server(args.ocr_server)
    if server is None:
        raise SystemExit("回补阶段需要 OCR 服务在线（启动 bridge 即可拉起）")
    result = _ocr_via_server(paths, server)
    if result is None:
        raise SystemExit("回补阶段 OCR 请求失败")
    write_json(os.path.join(work, "ocr_gap.json"), result)

    emit({"stage": "gap", "ok": True, "frames": saved, "ranges": ranges})
    return {"ok": True}


# ---- run: 一键 ----

def cmd_run(args):
    log("1/7 探针")
    info = cmd_probe(args)
    prof = args.profile if args.profile != "auto" else info.get("guess_profile", "handheld")
    log("  判定 %s, %s 帧 / %ss / %sMB" % (prof, info["frames"], info["duration_s"], info["size_mb"]))

    log("2/7 抽帧")
    cmd_frames(args)

    log("3/7 去重")
    dd = cmd_dedup(args)
    if dd.get("warn"):
        log("  （记录告警，继续用全部帧）")

    log("4/7 透视校正")
    rc = cmd_rectify(args)
    src = "frames" if rc.get("warn") else "warp"
    if rc.get("warn"):
        log("  （校正效果差，OCR 改读原始帧）")

    log("5/7 OCR")
    args.src = src
    cmd_ocr(args)

    log("6/7 拼长图")
    cmd_stitch(args)

    log("7/7 题目结构化")
    parsed = cmd_parse(args)

    summary = {
        "profile": prof,
        "questions": parsed.get("questionCount", 0),
        "incomplete": len([q for q in parsed.get("questions", []) if q.get("incomplete")]),
        "lowConfidence": parsed.get("lowConfidence", []),
        "artifacts": {
            "questions": os.path.join(work_dir(args.job), "questions_draft.json"),
            "panorama": os.path.join(work_dir(args.job), "panorama.png"),
            "ocr": os.path.join(work_dir(args.job), "ocr_%s.json" % src),
        },
    }
    write_json(os.path.join(work_dir(args.job), "pipeline_summary.json"), summary)
    emit(dict(stage="run", ok=summary["questions"] > 0, **summary))
    return summary


# ---- CLI ----

def main():
    ap = argparse.ArgumentParser(description="卷灵读卷流水线")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def add(name, fn, **kw):
        p = sub.add_parser(name)
        p.add_argument("--job", required=True, help="任务目录（含 video.mp4）")
        p.set_defaults(func=fn)
        for k, v in kw.items():
            flag = "--" + k.replace("_", "-")
            if isinstance(v, bool):
                p.add_argument(flag, action="store_true")
            else:
                p.add_argument(flag, type=type(v), default=v)
        return p

    add("probe", cmd_probe)
    add("frames", cmd_frames, step=DEFAULT["step"])
    add("dedup", cmd_dedup, ncc_min=DEFAULT["ncc_min"], min_ratio=DEFAULT["dedup_keep_ratio"])
    add("rectify", cmd_rectify, warp_width=DEFAULT["warp_width"])
    add("ocr", cmd_ocr, src="warp", ocr_server=DEFAULT["ocr_server"])
    add("stitch", cmd_stitch)
    add("parse", cmd_parse)
    add("gap", cmd_gap, ranges="[]", ocr_server=DEFAULT["ocr_server"])
    add("run", cmd_run, step=DEFAULT["step"], ncc_min=DEFAULT["ncc_min"],
        min_ratio=DEFAULT["dedup_keep_ratio"], warp_width=DEFAULT["warp_width"],
        profile="auto", src="warp", ocr_server=DEFAULT["ocr_server"])

    args = ap.parse_args()
    t0 = time.time()
    try:
        args.func(args)
    except SystemExit:
        raise
    except Exception as exc:
        emit({"stage": args.cmd, "ok": False, "error": str(exc)})
        raise SystemExit(1)
    log("完成，用时 %.1fs" % (time.time() - t0))


if __name__ == "__main__":
    main()