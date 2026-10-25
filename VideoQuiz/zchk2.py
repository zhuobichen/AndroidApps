import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\finalZ2.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\finalZ2.txt","w",encoding="utf-8").write(txt)
ans = re.findall(r"(?m)^\s*(\d{1,2})\.\s*([A-G])", txt)
print("pages:", d.page_count, "| 选择题答案:", len(ans), "| 置信度:", "识别置信度" in txt, "| 写作:", "写作题" in txt)
