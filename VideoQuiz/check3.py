import pymupdf, re
paper = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\paper3.pdf")
ans = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\answers3.pdf")
ptxt = "\n".join(p.get_text() for p in paper); atxt = "\n".join(p.get_text() for p in ans)
open(r"D:\Android\MapMate\VideoQuiz\paper3.txt","w",encoding="utf-8").write(ptxt)
order = [int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", ptxt)]
seen, seq = set(), []
for n in order:
    if 21 <= n <= 65 and n not in seen: seen.add(n); seq.append(n)
lines = [f"试卷 {paper.page_count} 页 / 答案 {ans.page_count} 页"]
lines.append(f"题号升序: {seq == sorted(seq)}，缺号: {[n for n in range(21,66) if n not in seq]}")
bleed = [n for n in seq if '【原' in ptxt.split(f'{n}. ')[-1][:150]]
lines.append(f"题干串【原文】的题: {bleed}")
ansn = [int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s*\S", atxt)]
lines.append(f"答案条数: {len(set(ansn))}，缺答案: {[n for n in range(21,66) if n not in set(ansn)]}")
open(r"D:\Android\MapMate\VideoQuiz\check3.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
