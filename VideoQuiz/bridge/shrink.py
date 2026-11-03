# 把目录里的图片缩到指定长边，供读图模型使用。
# 读图是整套流程里最贵的操作：1920x1080 约 7000 tokens，长边缩到 1000 后约 2900，
# 而实测文字、公式、中文注释依然清晰。
#
# 用法：
#   python shrink.py <目录或文件> [长边=1000] [--inplace]
#
# 默认输出到同目录的 _small/ 下（不改原图），加 --inplace 则覆盖原文件。

import os
import sys

try:
    from PIL import Image
except ImportError:
    print("SKIP: 没有安装 Pillow（pip install pillow）")
    sys.exit(0)

EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def collect(target):
    if os.path.isfile(target):
        return [target]
    found = []
    for root, _dirs, files in os.walk(target):
        if os.path.basename(root) == "_small":
            continue
        for name in files:
            if os.path.splitext(name)[1].lower() in EXTS:
                found.append(os.path.join(root, name))
    return sorted(found)


def main():
    if len(sys.argv) < 2:
        print("用法: python shrink.py <目录或文件> [长边=1000] [--inplace]")
        return 2

    target = sys.argv[1]
    long_edge = 1000
    inplace = "--inplace" in sys.argv

    for arg in sys.argv[2:]:
        if arg.isdigit():
            long_edge = int(arg)

    files = collect(target)
    if not files:
        print("没有找到图片")
        return 1

    before_total = after_total = 0
    done = 0

    for path in files:
        try:
            before = os.path.getsize(path)
            image = Image.open(path)
            width, height = image.size
            longest = max(width, height)

            if longest <= long_edge:
                before_total += before
                after_total += before
                continue

            scale = long_edge / longest
            resized = image.resize((max(1, int(width * scale)), max(1, int(height * scale))), Image.LANCZOS)

            if inplace:
                out = path
            else:
                out_dir = os.path.join(os.path.dirname(path), "_small")
                os.makedirs(out_dir, exist_ok=True)
                out = os.path.join(out_dir, os.path.basename(path))

            if out.lower().endswith(".png"):
                resized.save(out, "PNG", optimize=True)
            else:
                # 转成 RGB 再存 JPEG，避免 RGBA 保存失败
                if resized.mode in ("RGBA", "LA", "P"):
                    resized = resized.convert("RGB")
                resized.save(out, "JPEG", quality=88)

            after = os.path.getsize(out)
            before_total += before
            after_total += after
            done += 1
        except Exception as exc:
            print(f"  跳过 {os.path.basename(path)}: {exc}")

    saved = before_total - after_total
    pct = (saved / before_total * 100) if before_total else 0
    print(f"缩放 {done}/{len(files)} 张到长边 {long_edge}")
    print(f"体积 {before_total // 1024}KB -> {after_total // 1024}KB（省 {pct:.0f}%）")
    print(f"估算读图 token：{before_total and int(before_total / 1024 * 5.9) or 0} -> {int(after_total / 1024 * 5.9)}（粗略）")
    if not inplace:
        print("输出在各自的 _small/ 子目录")
    return 0


if __name__ == "__main__":
    sys.exit(main())
