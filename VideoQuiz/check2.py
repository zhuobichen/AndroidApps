import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\final2.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\final2.txt","w",encoding="utf-8").write(txt)
print("pages:", d.page_count)
print("原文块数:", txt.count("【原文】"))
nums = sorted({int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", txt)})
print("题号数:", len(nums), nums[:5], "...", nums[-3:])
for i in range(2):
    d[i].get_pixmap(dpi=100).save(rf"D:\Android\MapMate\VideoQuiz\f2_p{i+1}.png")
