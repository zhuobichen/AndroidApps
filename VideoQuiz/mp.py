import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_pages.pdf")
t = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\math_pages.txt","w",encoding="utf-8").write(t)
lines = ["pages=%d 字符=%d" % (d.page_count, len(t))]
for n in (18, 19):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,600})" % n, t, re.S)
    seg = re.sub(r"\s+"," ", m.group(1))[:560] if m else "缺失"
    lines.append("[%d] %s" % (n, seg))
open(r"D:\Android\MapMate\VideoQuiz\math_pages_check.txt","w",encoding="utf-8").write("\n\n".join(lines))
print("ok")
