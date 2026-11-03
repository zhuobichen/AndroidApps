# GPU 加速的批量 OCR：把图片目录里的所有图片识别成文字，输出 JSON。
#
# 为什么需要这个脚本：RapidOCR 默认走 CPU（实测 2.4 秒/帧），
# 显式打开 CUDA 后降到 0.38 秒/帧。但 CUDA 有前置条件：
#   1. pip 装的 nvidia 运行库不在 PATH，必须手动加目录
#   2. 首次推理要 50 秒 CUDA 初始化，必须预热掉，否则第一次很慢
# 这两件事都由脚本处理，调用方只管传目录。
#
# 用法：
#   python ocr.py <目录或单张图> [--out ocr.json] [--cuda auto|on|off] [--limit N]
#
# 输出 JSON 结构：
#   {"engine": "cuda|cpu", "files": N, "elapsed_s": x.x,
#    "results": [{"file": "a.jpg", "lines": [{"text": "...", "score": 0.98, "box": [[x,y],...]}]}]}

import argparse
import glob
import json
import logging
import os
import site
import sys
import time

EXTS = (".jpg", ".jpeg", ".png", ".webp", ".bmp")


def enable_cuda_dlls():
    """把 pip 装的 CUDA 运行库目录加进 DLL 搜索路径，返回加了几处。"""
    bases = []
    try:
        bases.extend(site.getsitepackages())
    except Exception:
        pass
    try:
        bases.append(site.getusersitepackages())
    except Exception:
        pass

    added = 0
    for base in bases:
        nvidia = os.path.join(base, "nvidia")
        if not os.path.isdir(nvidia):
            continue
        for sub in os.listdir(nvidia):
            bin_dir = os.path.join(nvidia, sub, "bin")
            if os.path.isdir(bin_dir):
                os.environ["PATH"] = bin_dir + os.pathsep + os.environ.get("PATH", "")
                try:
                    os.add_dll_directory(bin_dir)
                except Exception:
                    pass
                added += 1
    return added


def collect(target):
    if os.path.isfile(target):
        return [target]
    found = []
    for name in sorted(os.listdir(target)):
        if name.lower().endswith(EXTS):
            found.append(os.path.join(target, name))
    return found


def build_engine(use_cuda):
    from rapidocr_onnxruntime import RapidOCR
    flag = bool(use_cuda)
    return RapidOCR(det_use_cuda=flag, cls_use_cuda=flag, rec_use_cuda=flag)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("target")
    ap.add_argument("--out", default="")
    ap.add_argument("--cuda", default="auto", choices=["auto", "on", "off"])
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    logging.disable(logging.WARNING)

    files = collect(args.target)
    if not files:
        print("没有找到图片", file=sys.stderr)
        return 1
    if args.limit > 0:
        files = files[: args.limit]

    # 帧太少时 CUDA 的 50 秒初始化不划算（CPU 约 2.4 秒/帧，40 帧才追平）
    want_cuda = args.cuda != "off"
    if want_cuda and args.cuda == "auto" and len(files) < 40:
        print(f"只有 {len(files)} 张，CUDA 初始化不划算，直接用 CPU", file=sys.stderr)
        want_cuda = False

    if want_cuda:
        # auto 和 on 都要加 DLL 路径，否则 CUDA provider 静默失败
        added = enable_cuda_dlls()
        if added:
            print(f"已注册 {added} 个 CUDA 运行库目录", file=sys.stderr)

    engine = None
    engine_kind = "cpu"

    if want_cuda:
        try:
            engine = build_engine(True)
            # 预热：首次推理触发 CUDA 初始化（可达 50 秒），必须吃掉；
            # 同时用它测速判断 CUDA 是否真的生效——provider 失败时不会抛异常，
            # 只会静默回退 CPU，所以只能靠速度判定。
            t0 = time.time()
            engine(files[0])
            warm = time.time() - t0

            t0 = time.time()
            engine(files[0])
            single = time.time() - t0

            if single < 1.0:
                engine_kind = "cuda"
                print(f"CUDA 生效（预热 {warm:.1f}s，稳定 {single*1000:.0f}ms/帧）", file=sys.stderr)
            else:
                print(f"CUDA 未生效（预热 {warm:.1f}s，但仍 {single*1000:.0f}ms/帧），回落 CPU", file=sys.stderr)
                engine = None
        except Exception as exc:
            print(f"CUDA 异常，回落 CPU：{type(exc).__name__} {exc}", file=sys.stderr)
            engine = None

    if engine is None:
        engine = build_engine(False)
        engine_kind = "cpu"
        print("使用 CPU", file=sys.stderr)

    started = time.time()
    results = []
    total_lines = 0

    for index, path in enumerate(files, 1):
        try:
            res, _ = engine(path)
            lines = []
            if res:
                for box, text, score in res:
                    lines.append({
                        "text": text,
                        "score": round(float(score), 4),
                        "box": [[round(float(x), 1), round(float(y), 1)] for x, y in box],
                    })
            total_lines += len(lines)
            results.append({"file": os.path.basename(path), "lines": lines})
        except Exception as exc:
            results.append({"file": os.path.basename(path), "error": str(exc)[:200]})

        if index % 20 == 0 or index == len(files):
            print(f"  {index}/{len(files)}", file=sys.stderr)

    elapsed = time.time() - started
    payload = {
        "engine": engine_kind,
        "files": len(files),
        "elapsed_s": round(elapsed, 2),
        "ms_per_frame": round(elapsed / len(files) * 1000),
        "total_lines": total_lines,
        "results": results,
    }

    out_path = args.out or os.path.join(args.target if os.path.isdir(args.target) else os.path.dirname(args.target), "ocr.json")
    with open(out_path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=1)

    print(json.dumps({
        "engine": engine_kind,
        "files": len(files),
        "elapsed_s": payload["elapsed_s"],
        "ms_per_frame": payload["ms_per_frame"],
        "total_lines": total_lines,
        "out": out_path,
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
