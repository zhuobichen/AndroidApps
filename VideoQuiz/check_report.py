import pymupdf, re, json

paper = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\paper.pdf")
ans = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\answers.pdf")
ptxt = "\n".join(p.get_text() for p in paper)
atxt = "\n".join(p.get_text() for p in ans)
open(r"D:\Android\MapMate\VideoQuiz\paper.txt","w",encoding="utf-8").write(ptxt)

# 切出题目段（试卷里没有「参考答案」）
blocks = re.split(r"(?m)(?=^\s*\d{1,2}\.\s)", ptxt)
qs = []
for b in blocks:
    m = re.match(r"\s*(\d{1,2})\.\s*(.*)", b, re.S)
    if not m: continue
    no = int(m.group(1))
    body = m.group(2)
    if no < 21 or no > 65: continue
    opts = re.findall(r"(?m)^\s*([A-D])\.\s*(.+)$", body)
    stem = re.split(r"(?m)^\s*A\.\s", body)[0]
    qs.append({"no": no, "stem": re.sub(r"\s+"," ", stem).strip()[:200],
               "n_opts": len({o[0] for o in opts})})

lines = [f"试卷页数={paper.page_count} 答案页数={ans.page_count}"]
lines.append(f"题目数={len(qs)} 题号={[q['no'] for q in qs]}")
bad_opts = [q['no'] for q in qs if q['n_opts'] < 4]
lines.append(f"选项不足 4 个的题: {bad_opts}")
blank_stem = [q['no'] for q in qs if not re.search(r"[A-Za-z]{3}", q['stem'])]
lines.append(f"题干没有英文的题: {blank_stem}")
# 题干里出现别的题号
leak = []
for q in qs:
    for other in qs:
        if other['no'] != q['no'] and re.search(rf"\b{other['no']}\s*[.、]", q['stem']):
            leak.append(q['no']); break
lines.append(f"题干里混入其它题号的题: {sorted(set(leak))}")
# 答案统计
ans_rows = re.findall(r"(?m)^\s*(\d{1,2})\.\s*([A-G]|[a-z][a-z' ]{1,20})", atxt)
letters = [n for n,a in ans_rows if re.fullmatch(r"[A-G]", a)]
lines.append(f"答案行数={len(ans_rows)} 其中字母答案={len(letters)}")
missing_ans = [q['no'] for q in qs if not re.search(rf"(?m)^\s*{q['no']}\.\s*\S", atxt)]
lines.append(f"答案缺失的题号: {missing_ans}")
# 段落关键词
for kw, name in [("SoFi", "A篇"), ("Kevin", "B篇"), ("isopren", "C篇"), ("ceramic", "D篇"),
                 ("Museums", "七选五"), ("Ohio", "完形"), ("Dajis", "语法填空")]:
    lines.append(f"原文 {name}: {'有' if kw.lower() in ptxt.lower() else '无'}")
open(r"D:\Android\MapMate\VideoQuiz\check_report.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
