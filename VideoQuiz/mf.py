import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_final_paper.pdf")
t = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\math_final.txt","w",encoding="utf-8").write(t)
qi = t.find("识别质量提"); qt = t[:qi] if qi>0 else t
lines = ["pages=%d" % d.page_count]
for n in (1, 18, 19):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,300})" % n, qt, re.S)
    seg = re.sub(r"\s+"," ", m.group(1))[:280] if m else "缺失"
    lines.append("[%d] %s" % (n, seg))
open(r"D:\Android\MapMate\VideoQuiz\math_final_check.txt","w",encoding="utf-8").write("\n\n".join(lines))
print("ok")
