import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\finalA.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\finalA.txt","w",encoding="utf-8").write(txt)
qi = txt.find("参考答案")
qt = txt[:qi] if qi>0 else txt
nums = sorted({int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", qt)})
lines = ["pages=%d" % d.page_count, "nums=%s" % nums, "count=%d" % len(nums)]
for n in (36, 37, 40, 46, 50, 51):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,200})" % n, qt, re.S)
    seg = re.sub(r"\s+", " ", m.group(1)).strip()[:180] if m else "缺失"
    lines.append("[%d] %s" % (n, seg))
lines.append("quality=%s writing=%s" % ("共抽取" in txt, "写作题" in txt))
open(r"D:\Android\MapMate\VideoQuiz\auditA.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
