import re
t = open(r"D:\Android\MapMate\VideoQuiz\paper.txt", encoding="utf-8").read()
blocks = re.split(r"(?m)(?=^\s*\d{1,2}\.\s)", t)
d = {}
for b in blocks:
    m = re.match(r"\s*(\d{1,2})\.\s*(.*)", b, re.S)
    if not m: continue
    no = int(m.group(1))
    if 21 <= no <= 65: d[no] = re.sub(r"\s+", " ", m.group(2)).strip()
out = []
for n in (54, 55, 56, 57, 36):
    out.append("[%d] %s" % (n, d.get(n, "缺失")[:260]))
open(r"D:\Android\MapMate\VideoQuiz\sample_q.txt","w",encoding="utf-8").write("\n\n".join(out))
print("ok")
