import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\checkP.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\checkP.txt","w",encoding="utf-8").write(txt)
qi = txt.find("参考答案"); qt = txt[:qi] if qi>0 else txt
lines = ["pages=%d" % d.page_count]
for n in (36, 37, 38, 39, 40):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,210})" % n, qt, re.S)
    lines.append("[%d] %s" % (n, re.sub(r"\s+"," ", m.group(1))[:200] if m else "缺失"))
open(r"D:\Android\MapMate\VideoQuiz\auditP.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
