import pymupdf, re
paper = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\paperF.pdf")
ans = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\answersF.pdf")
ptxt = "\n".join(p.get_text() for p in paper); atxt = "\n".join(p.get_text() for p in ans)
open(r"D:\Android\MapMate\VideoQuiz\paperF.txt","w",encoding="utf-8").write(ptxt)
open(r"D:\Android\MapMate\VideoQuiz\answersF.txt","w",encoding="utf-8").write(atxt)
order = [int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", ptxt)]
seen, seq = set(), []
for n in order:
    if 21 <= n <= 65 and n not in seen: seen.add(n); seq.append(n)
qi = ptxt.find("质量提")
warn = ptxt[qi:qi+800] if qi>0 else ""
lines = [f"试卷 {paper.page_count} 页 / 答案 {ans.page_count} 页",
         f"题号升序={seq==sorted(seq)} 缺号={[n for n in range(21,66) if n not in seq]}",
         "--- 质量提示 ---", re.sub(r"\n+", "\n", warn)[:600]]
open(r"D:\Android\MapMate\VideoQuiz\final_check.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
