import pymupdf, re
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\checkF.pdf")
txt = "\n".join(p.get_text() for p in d)
open(r"D:\Android\MapMate\VideoQuiz\checkF.txt","w",encoding="utf-8").write(txt)
i = txt.find("【原文】")
# 找出所有原文块
blocks = re.split(r"【原[文⽂]】", txt)
out = ["pages=%d blocks=%d" % (d.page_count, len(blocks)-1)]
for k, b in enumerate(blocks[1:4], 1):
    head = re.sub(r"\s+", " ", b.strip())[:260]
    out.append("[块%d] %s" % (k, head))
out.append("含 New York City: %s" % ("New York City" in txt))
out.append("含 In line with: %s" % ("In line with" in txt))
open(r"D:\Android\MapMate\VideoQuiz\passages.txt","w",encoding="utf-8").write("\n\n".join(out))
print("ok")
