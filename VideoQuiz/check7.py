import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\final7.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\final7.txt","w",encoding="utf-8").write(txt)
print("pages:", d.page_count)
print("质量提示:", "识别质量提示" in txt)
print("写作题:", "写作题" in txt)
i = txt.find("写作题")
if i > 0:
    print(txt[i:i+700].replace("\n"," ")[:700])
