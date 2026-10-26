import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_paper3.pdf")
t = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\math_paper3.txt","w",encoding="utf-8").write(t)
qi = t.find("识别质量提"); qt = t[:qi] if qi>0 else t
lines = ["pages=%d" % d.page_count]
for n in (18, 19):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,500})" % n, qt, re.S)
    seg = re.sub(r"\s+"," ", m.group(1))[:460] if m else "缺失"
    subs = len(re.findall(r"\(\d\)", seg))
    lines.append("[%d] 小问=%d %s" % (n, subs, seg))
open(r"D:\Android\MapMate\VideoQuiz\math3_check.txt","w",encoding="utf-8").write("\n\n".join(lines))
print("ok")
