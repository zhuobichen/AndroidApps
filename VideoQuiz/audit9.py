import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\final9.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\final9.txt","w",encoding="utf-8").write(txt)
qi = txt.find("参考答案")
qt = txt[:qi] if qi>0 else txt
nums = sorted({int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", qt)})
out = ["pages=%d" % d.page_count, "nums=%s" % nums]
for n in (36, 37, 38, 39, 40):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,220})" % n, qt, re.S)
    seg = re.sub(r"\s+", " ", m.group(1)).strip()[:200] if m else "缺失"
    out.append("[%d] %s" % (n, seg))
out.append("quality=%s" % ("共抽取" in txt))
out.append("writing=%s" % ("写作题" in txt))
open(r"D:\Android\MapMate\VideoQuiz\audit9.txt","w",encoding="utf-8").write("\n".join(out))
print("ok")
