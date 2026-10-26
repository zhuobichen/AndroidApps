import pymupdf, re
paper = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\paperG.pdf")
ans = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\answersG.pdf")
ptxt = "\n".join(p.get_text() for p in paper); atxt = "\n".join(p.get_text() for p in ans)
open(r"D:\Android\MapMate\VideoQuiz\paperG.txt","w",encoding="utf-8").write(ptxt)

blocks = re.split(r"(?m)(?=^\s*\d{1,2}\.\s)", ptxt)
qs = {}
for b in blocks:
    m = re.match(r"\s*(\d{1,2})\.\s*(.*)", b, re.S)
    if not m: continue
    no = int(m.group(1))
    if 21 <= no <= 65:
        body = m.group(2)
        opts = {o for o, _ in re.findall(r"(?m)^\s*([A-D])\.\s*(.+)$", body)}
        stem = re.split(r"(?m)^\s*A\.\s", body)[0]
        qs.setdefault(no, (len(opts), re.sub(r"\s+"," ", stem).strip()))

order = sorted(qs)
lines = [f"试卷 {paper.page_count} 页 / 答案 {ans.page_count} 页"]
lines.append(f"题号: {order[0]}-{order[-1]}，共 {len(order)} 题，缺号 {[n for n in range(21,66) if n not in qs]}")
lines.append(f"无选项的题: {[n for n in order if qs[n][0] < 4]}")
short = [n for n in order if len(qs[n][1]) < 40]
lines.append(f"题干过短的题: {short}")
for n in short[:6]:
    lines.append(f"  [{n}] {qs[n][1][:80]}")
ansn = sorted({int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s*\S", atxt)})
lines.append(f"答案覆盖: {len(ansn)} 条，缺 {[n for n in range(21,66) if n not in ansn]}")
open(r"D:\Android\MapMate\VideoQuiz\fullcheck.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
