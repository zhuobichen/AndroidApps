import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\final.pdf")
txt = "\n".join(p.get_text() for p in d)
nums = sorted({int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", txt)})
print("题号:", nums)
print("数量:", len(nums))
print("缺:", [n for n in range(1, max(nums)+1) if n not in nums])
for n in [1,2,3,6,7]:
    m = re.search(rf"(?m)^\s*{n}\.\s(.{{0,100}})", txt)
    if m: print(f"[{n}]", m.group(1).replace("\n"," ")[:100])
open(r"D:\Android\MapMate\VideoQuiz\final_text.txt","w",encoding="utf-8").write(txt)
