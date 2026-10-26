import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_pagemode.pdf")
t = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\math_pagemode.txt","w",encoding="utf-8").write(t)
lines = ["pages=%d 字符=%d" % (d.page_count, len(t))]
i = t.find("18.（17分）")
lines.append("Q18 段存在: %s" % (i > 0))
if i > 0:
    seg = re.sub(r"\s+", " ", t[i:i+420])
    lines.append("Q18: " + seg)
j = t.find("19.（17分）")
if j > 0:
    seg = re.sub(r"\s+", " ", t[j:j+520])
    lines.append("Q19: " + seg)
open(r"D:\Android\MapMate\VideoQuiz\math_pm_check.txt","w",encoding="utf-8").write("\n\n".join(lines))
print("ok")
