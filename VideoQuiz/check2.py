import pymupdf, re
paper = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\paper2.pdf")
ans = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\answers2.pdf")
ptxt = "\n".join(p.get_text() for p in paper); atxt = "\n".join(p.get_text() for p in ans)
open(r"D:\Android\MapMate\VideoQuiz\paper2.txt","w",encoding="utf-8").write(ptxt)
order = [int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", ptxt)]
seen, seq = set(), []
for n in order:
    if 21 <= n <= 65 and n not in seen:
        seen.add(n); seq.append(n)
lines = [f"试卷页数={paper.page_count} 答案页数={ans.page_count}"]
lines.append(f"题号顺序: {seq}")
lines.append(f"是否升序: {seq == sorted(seq)}")
lines.append(f"缺号: {[n for n in range(21,66) if n not in seq]}")
lines.append(f"题干含【原文】标记的题: {[n for n in seq if '【原' in ptxt.split(f'{n}. ')[-1][:120]]}")
bad = []
for n in (54, 55):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,220})" % n, ptxt, re.S)
    if m: bad.append("[%d] %s" % (n, re.sub(r"\s+"," ", m.group(1))[:200]))
lines.append("\n".join(bad))
open(r"D:\Android\MapMate\VideoQuiz\check2.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
