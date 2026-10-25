import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\finalC.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\finalC.txt","w",encoding="utf-8").write(txt)
lines = ["pages=%d" % d.page_count]
head = txt[:600]
lines.append("--- 抬头 ---")
lines.append(head)
i = txt.find("写作题")
if i > 0:
    lines.append("--- 写作题 ---")
    lines.append(txt[i:i+900])
m = re.search(r"(?m)^\s*36\.\s(.{0,160})", txt, re.S)
lines.append("--- 36 ---")
lines.append(re.sub(r"\s+"," ", m.group(1))[:160] if m else "缺失")
open(r"D:\Android\MapMate\VideoQuiz\auditC.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
