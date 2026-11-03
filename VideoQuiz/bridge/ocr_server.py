# 常驻 OCR 服务：模型只加载一次、CUDA 只预热一次，之后每次请求直接出结果。
#
# 为什么需要常驻：RapidOCR 每次冷启动要付 50 秒 CUDA 初始化（CPU 模式约 0.15 秒）。
# 一个任务里会话往往要调好几次 OCR，每次都重启进程就白付好几次 50 秒。
#
# 用法：
#   python ocr_server.py [--port 8766] [--cuda auto|on|off]
#
# 接口（只监听 127.0.0.1，不对外）：
#   GET  /health   -> {"ok":true,"engine":"cuda","ready":true,...}
#   POST /ocr      -> {"paths":["a.jpg",...]}  或  {"dir":"D:/x","pattern":"*.jpg"}
#                     返回 {"engine":"cuda","results":[{"file":"a.jpg","lines":[...]}]}
#   POST /ocr      -> {"images":[{"name":"a.jpg","b64":"..."}]}   直接传图，不落盘
#   POST /shutdown -> 关闭服务

import argparse
import base64
import glob
import json
import logging
import os
import site
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

EXTS = (".jpg", ".jpeg", ".png", ".webp", ".bmp")

_engine = None
_engine_kind = "none"
_lock = threading.Lock()
_started_at = time.time()
_stats = {"requests": 0, "images": 0, "ocr_seconds": 0.0}


def enable_cuda_dlls():
    """把 pip 装的 nvidia 运行库加进 DLL 搜索路径，否则 CUDA provider 会静默失败。"""
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


def build_engine(use_cuda):
    from rapidocr_onnxruntime import RapidOCR
    flag = bool(use_cuda)
    return RapidOCR(det_use_cuda=flag, cls_use_cuda=flag, rec_use_cuda=flag)


def warmup_sample():
    """造一张有字的图用来预热，避免依赖外部文件。"""
    try:
        import tempfile
        from PIL import Image, ImageDraw
        img = Image.new("RGB", (640, 200), "white")
        draw = ImageDraw.Draw(img)
        draw.text((20, 60), "warmup 12345 ABCDEF", fill="black")
        handle, path = tempfile.mkstemp(suffix=".jpg")
        os.close(handle)
        img.save(path, "JPEG", quality=80)
        return path
    except Exception as exc:
        print(f"预热图生成失败：{type(exc).__name__} {exc}", file=sys.stderr, flush=True)
        return None


def init_engine(mode):
    global _engine, _engine_kind

    want_cuda = mode != "off"
    if want_cuda:
        added = enable_cuda_dlls()
        print(f"已注册 {added} 个 CUDA 运行库目录", file=sys.stderr, flush=True)

    if want_cuda:
        try:
            engine = build_engine(True)
            sample = warmup_sample()

            # provider 失败时不会抛异常，只会静默回退 CPU，所以只能靠速度判定
            probe = 99.0
            if sample:
                t0 = time.time()
                engine(sample)          # 第一次：触发 CUDA 初始化，很慢
                warm = time.time() - t0
                t0 = time.time()
                engine(sample)          # 第二次：测稳定性
                probe = time.time() - t0
                print(f"预热完成：首次 {warm:.1f}s，稳定 {probe*1000:.0f}ms/帧", file=sys.stderr, flush=True)
                try:
                    os.remove(sample)
                except Exception:
                    pass

            if sample and probe < 1.0:
                _engine = engine
                _engine_kind = "cuda"
                print(f"引擎就绪：CUDA（预热后 {probe*1000:.0f}ms/帧）", file=sys.stderr, flush=True)
                return
            print(f"CUDA 未生效（{probe:.2f}s/帧），回落 CPU", file=sys.stderr, flush=True)
        except Exception as exc:
            print(f"CUDA 初始化失败，回落 CPU：{type(exc).__name__} {exc}", file=sys.stderr, flush=True)

    _engine = build_engine(False)
    _engine_kind = "cpu"
    print("引擎就绪：CPU", file=sys.stderr, flush=True)


def run_ocr(paths):
    """对一组图片跑 OCR，返回结果列表。调用方必须持锁。"""
    results = []
    for path in paths:
        try:
            res, _ = _engine(path)
            lines = []
            if res:
                for box, text, score in res:
                    lines.append({
                        "text": text,
                        "score": round(float(score), 4),
                        "box": [[round(float(x), 1), round(float(y), 1)] for x, y in box],
                    })
            results.append({"file": os.path.basename(path), "lines": lines})
        except Exception as exc:
            results.append({"file": os.path.basename(path), "error": str(exc)[:200]})
    return results


def collect_from_dir(directory, pattern):
    if not os.path.isdir(directory):
        return []
    files = []
    for name in sorted(os.listdir(directory)):
        full = os.path.join(directory, name)
        if os.path.isfile(full) and name.lower().endswith(EXTS):
            files.append(full)
    return files


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # 静默，避免刷屏

    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            with _lock:
                busy = _lock.locked() and False
            self._send(200, {
                "ok": _engine is not None,
                "engine": _engine_kind,
                "ready": _engine is not None,
                "uptime_s": round(time.time() - _started_at, 1),
                "stats": dict(_stats),
            })
            return
        self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""

        if self.path.startswith("/shutdown"):
            self._send(200, {"ok": True, "message": "shutting down"})
            threading.Thread(target=lambda: (time.sleep(0.3), os._exit(0)), daemon=True).start()
            return

        if not self.path.startswith("/ocr"):
            self._send(404, {"ok": False, "error": "not found"})
            return

        try:
            payload = json.loads(raw.decode("utf-8")) if raw else {}
        except Exception as exc:
            self._send(400, {"ok": False, "error": f"bad json: {exc}"})
            return

        if _engine is None:
            self._send(503, {"ok": False, "error": "engine not ready"})
            return

        try:
            # 三种输入方式：本地路径列表 / 目录 / base64 图片
            paths = []
            temp_files = []

            if isinstance(payload.get("images"), list):
                import tempfile
                for item in payload["images"]:
                    data = base64.b64decode(item.get("b64", ""))
                    suffix = os.path.splitext(item.get("name", "x.jpg"))[1] or ".jpg"
                    handle, path = tempfile.mkstemp(suffix=suffix)
                    with os.fdopen(handle, "wb") as fh:
                        fh.write(data)
                    paths.append(path)
                    temp_files.append(path)
            elif isinstance(payload.get("paths"), list):
                paths = [p for p in payload["paths"] if isinstance(p, str)]
            elif isinstance(payload.get("dir"), str):
                paths = collect_from_dir(payload["dir"], payload.get("pattern", "*"))

            if not paths:
                self._send(400, {"ok": False, "error": "no images"})
                return

            started = time.time()
            with _lock:
                results = run_ocr(paths)
            elapsed = time.time() - started

            _stats["requests"] += 1
            _stats["images"] += len(paths)
            _stats["ocr_seconds"] = round(_stats["ocr_seconds"] + elapsed, 1)

            for path in temp_files:
                try:
                    os.remove(path)
                except Exception:
                    pass

            self._send(200, {
                "ok": True,
                "engine": _engine_kind,
                "files": len(paths),
                "elapsed_s": round(elapsed, 2),
                "ms_per_frame": round(elapsed / len(paths) * 1000),
                "results": results,
            })
        except Exception as exc:
            self._send(500, {"ok": False, "error": f"{type(exc).__name__}: {exc}"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8766)
    ap.add_argument("--cuda", default="auto", choices=["auto", "on", "off"])
    args = ap.parse_args()

    logging.disable(logging.WARNING)
    init_engine(args.cuda)

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"OCR 服务已启动：http://127.0.0.1:{args.port}  引擎={_engine_kind}", file=sys.stderr, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
