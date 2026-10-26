import pymupdf, re
paper = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\paperG.pdf")
txt = "\n".join(p.get_text() for p in paper)
open(r"D:\Android\MapMate\VideoQuiz\paperG.txt","w",encoding="utf-8").write(txt)
qi = txt.find("参考答案"); qt = txt[:qi] if qi>0 else txt
lines = ["pages=%d" % paper.page_count]
for n in (36, 37, 38, 39, 40):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,300})" % n, qt, re.S)
    seg = re.sub(r"\s+"," ", m.group(1))[:290] if m else "缺失"
    lines.append("[%d] %s" % (n, seg))
open(r"D:\Android\MapMate\VideoQuiz\auditG2.txt","w",encoding="utf-8").write("\n\n".join(lines))
print("ok")
