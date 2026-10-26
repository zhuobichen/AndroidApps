import pymupdf, re
paper = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\paperH.pdf")
ans = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\answersH.pdf")
ptxt = "\n".join(p.get_text() for p in paper); atxt = "\n".join(p.get_text() for p in ans)
open(r"D:\Android\MapMate\VideoQuiz\paperH.txt","w",encoding="utf-8").write(ptxt)

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
lines = [f"试卷 {paper.page_count} 页 / 答案 {ans.page_count} 页",
         f"题号 {order[0]}-{order[-1]} 共 {len(order)} 题，缺号 {[n for n in range(21,66) if n not in qs]}"]
need = [n for n in order if (21 <= n <= 35 or 41 <= n <= 55) and qs[n][0] < 4]
lines.append(f"应有选项但缺失: {need}")
i = ptxt.find("质量提")
warn = re.sub(r"\n+", "\n", ptxt[i:i+600]) if i > 0 else "无"
lines.append("--- 质量提示 ---")
lines.append(warn)
open(r"D:\Android\MapMate\VideoQuiz\checkH.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
