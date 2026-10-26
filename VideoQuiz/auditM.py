import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\checkM.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\checkM.txt","w",encoding="utf-8").write(txt)
qi = txt.find("参考答案"); qt = txt[:qi] if qi>0 else txt
lines = []
for n in (36, 37, 38, 39, 40):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,190})" % n, qt, re.S)
    lines.append("[%d] %s" % (n, re.sub(r"\s+"," ", m.group(1))[:180] if m else "缺失"))
open(r"D:\Android\MapMate\VideoQuiz\auditM.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
