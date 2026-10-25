import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\final5.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\final5.txt","w",encoding="utf-8").write(txt)
print("pages:", d.page_count)
print("原文块:", len(re.findall("【原", txt)))
print("题号数:", len(sorted({int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", txt)})))
for i in range(min(3, d.page_count)):
    d[i].get_pixmap(dpi=100).save(rf"D:\Android\MapMate\VideoQuiz\f5_p{i+1}.png")
