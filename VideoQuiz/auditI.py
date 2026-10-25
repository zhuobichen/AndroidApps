import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\checkI.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\checkI.txt","w",encoding="utf-8").write(txt)
qi = txt.find("参考答案")
qt = txt[:qi] if qi>0 else txt
lines = []
for n in range(28, 36):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,150})" % n, qt, re.S)
    lines.append("[%d] %s" % (n, re.sub(r"\s+"," ", m.group(1))[:140] if m else "缺失"))
lines.append("含 New York City: %s" % ("New York City" in txt))
open(r"D:\Android\MapMate\VideoQuiz\auditI.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
