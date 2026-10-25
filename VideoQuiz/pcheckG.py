import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\checkG.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\checkG.txt","w",encoding="utf-8").write(txt)
out = ["pages=%d" % d.page_count]
out.append("含 New York City: %s" % ("New York City" in txt))
out.append("含 What could go wrong: %s" % ("could go wrong" in txt))
i = txt.find("What could go wrong")
if i > 0:
    out.append("上下文: " + re.sub(r"\s+"," ", txt[max(0,i-700):i+80]))
open(r"D:\Android\MapMate\VideoQuiz\passagesG.txt","w",encoding="utf-8").write("\n\n".join(out))
print("ok")
