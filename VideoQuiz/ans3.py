import pymupdf
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_answers3.pdf")
t = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\math_answers3.txt","w",encoding="utf-8").write(t)
print("pages:", d.page_count)
