import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\final6.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\final6.txt","w",encoding="utf-8").write(txt)
print("pages:", d.page_count)
print("有质量提示:", "识别质量提示" in txt)
print("原文块:", len(re.findall("【原", txt)))
qi = txt.find("参考答案")
qt = txt[:qi] if qi>0 else txt
for n in (50, 51, 52, 54, 55):
    m = re.search(rf"(?m)^\s*{n}\.\s(.{{0,160}})", qt)
    print(f"[{n}]", (m.group(1) if m else "缺失").replace("\n"," ")[:160])
