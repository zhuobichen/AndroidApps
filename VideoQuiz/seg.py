import pymupdf
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\final7.pdf")
txt = "\n".join(p.get_text() for p in d)
i = txt.find("写作题")
seg = txt[i:i+1200] if i>0 else "(未找到)"
open(r"D:\Android\MapMate\VideoQuiz\writing_seg.txt","w",encoding="utf-8").write(seg)
print("ok")
