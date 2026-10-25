import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\final.pdf")
txt = "\n".join(p.get_text() for p in d)
nums = sorted({int(n) for n in re.findall(r"(?m)^\s*(\d{1,2})\.\s", txt)})
ans = re.findall(r"(?m)^\s*(\d{1,2})\.\s*([A-D])\s+(.{0,40})", txt)
report = []
report.append(f"页数: {d.page_count}")
report.append(f"题号: {nums}")
report.append(f"数量: {len(nums)}")
report.append(f"带答案条目: {len(ans)}")
for no in ("41","50","58","65"):
    m = re.search(rf"(?m)^{no}\.\s(.{{0,260}})", txt, re.S)
    if m: report.append(f"\n[{no}] " + m.group(1).replace("\n"," ")[:260])
open(r"D:\Android\MapMate\VideoQuiz\final_report.txt","w",encoding="utf-8").write("\n".join(report))
print("ok")
