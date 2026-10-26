import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\checkO.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\checkO.txt","w",encoding="utf-8").write(txt)
qi = txt.find("参考答案"); qt = txt[:qi] if qi>0 else txt
lines = ["pages=%d" % d.page_count]
for n in (36, 37, 38, 39, 40):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,200})" % n, qt, re.S)
    lines.append("[%d] %s" % (n, re.sub(r"\s+"," ", m.group(1))[:190] if m else "缺失"))
open(r"D:\Android\MapMate\VideoQuiz\auditO.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
