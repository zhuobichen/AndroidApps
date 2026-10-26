import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_page2.pdf")
t = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\math_page2.txt","w",encoding="utf-8").write(t)
lines = ["pages=%d 字符=%d" % (d.page_count, len(t)),
         "整页标记=%d" % len(re.findall("第 \\d+ 页", t)),
         "含质量提示=%s" % ("识别质量提" in t)]
for n in (18, 19):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,700})" % n, t, re.S)
    seg = re.sub(r"\s+"," ", m.group(1))[:650] if m else "缺失"
    lines.append("[%d] %s" % (n, seg))
open(r"D:\Android\MapMate\VideoQuiz\math_page2_check.txt","w",encoding="utf-8").write("\n\n".join(lines))
print("ok")
