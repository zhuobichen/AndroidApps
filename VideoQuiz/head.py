import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\finalD.pdf")
txt = "\n".join(p.get_text() for p in d)
head = txt[:500]
open(r"D:\Android\MapMate\VideoQuiz\headD.txt","w",encoding="utf-8").write(head)
print("pages:", d.page_count)
