import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\new.pdf")
txt = "\n".join(p.get_text() for p in d)
nums = [int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", txt)]
uniq = sorted(set(nums))
print("页数:", d.page_count)
print("题号:", uniq)
print("数量:", len(uniq))
missing = [n for n in range(min(uniq), max(uniq)+1) if n not in uniq]
print("区间内缺失:", missing)
open(r"D:\Android\MapMate\VideoQuiz\new_text.txt","w",encoding="utf-8").write(txt)
