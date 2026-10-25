import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\final8.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\final8.txt","w",encoding="utf-8").write(txt)
qi = txt.find("参考答案")
qt = txt[:qi] if qi > 0 else txt
nums = sorted({int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", qt)})
print("pages:", d.page_count)
print("题目区题号:", nums)
print("数量:", len(nums))
ans = re.findall(r"(?m)^\s*(\d{1,2})\.\s*([A-G])\s", txt[qi:]) if qi>0 else []
print("选择题答案数:", len(ans))
print("作文节:", "写作题" in txt, "| 质量节:", "共抽取" in txt)
