import pymupdf
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\answers.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\answers.txt","w",encoding="utf-8").write(txt)
print(txt[:900])
