import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_paper2.pdf")
t = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\math_paper2.txt","w",encoding="utf-8").write(t)
qi = t.find("识别质量提"); qt = t[:qi] if qi>0 else t
nums = sorted({int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", qt)})
lines = ["pages=%d 题号=%s" % (d.page_count, nums)]
for n in (18, 19):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,400})" % n, qt, re.S)
    seg = re.sub(r"\s+"," ", m.group(1))[:380] if m else "缺失"
    lines.append("[%d] %s" % (n, seg))
open(r"D:\Android\MapMate\VideoQuiz\math2_check.txt","w",encoding="utf-8").write("\n\n".join(lines))
print("ok")
