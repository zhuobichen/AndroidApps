import pymupdf
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\finalD.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\finalD.txt","w",encoding="utf-8").write(txt)
i = txt.find("写作题")
seg = txt[i:i+2600] if i>0 else "(未找到)"
open(r"D:\Android\MapMate\VideoQuiz\writingD.txt","w",encoding="utf-8").write(seg)
print("ok")
