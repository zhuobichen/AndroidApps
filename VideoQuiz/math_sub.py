import re
t = open(r"D:\Android\MapMate\VideoQuiz\math_paper.txt", encoding="utf-8").read()
qi = t.find("识别质量提"); qt = t[:qi] if qi>0 else t
lines = []
for n in (15, 16, 17, 18, 19):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,320})" % n, qt, re.S)
    seg = re.sub(r"\s+"," ", m.group(1))[:300] if m else "缺失"
    subs = len(re.findall(r"\(\d\)", seg))
    lines.append("[%d] 小问数=%d  %s" % (n, subs, seg))
open(r"D:\Android\MapMate\VideoQuiz\math_sub.txt","w",encoding="utf-8").write("\n\n".join(lines))
print("ok")
