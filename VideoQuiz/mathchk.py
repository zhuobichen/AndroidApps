import pymupdf
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_paper.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\math_paper.txt","w",encoding="utf-8").write(txt)
print("paper pages:", d.page_count)
a = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_answers.pdf")
atxt = "\n".join(p.get_text() for p in a)
open(r"D:\Android\MapMate\VideoQuiz\math_answers.txt","w",encoding="utf-8").write(atxt)
print("answers pages:", a.page_count)
