import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\final3.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\final3.txt","w",encoding="utf-8").write(txt)
print("pages:", d.page_count)
print("原文标记数:", txt.count("【原文】"))
nums = sorted({int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", txt)})
print("题号数:", len(nums))
print("题号:", nums)
for i in (0, 2):
    if i < d.page_count:
        d[i].get_pixmap(dpi=100).save(rf"D:\Android\MapMate\VideoQuiz\f3_p{i+1}.png")
