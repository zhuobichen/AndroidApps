import re
t = open(r"D:\Android\MapMate\VideoQuiz\paperH.txt", encoding="utf-8").read()
qi = t.find("参考答案"); qt = t[:qi] if qi>0 else t
out = []
for n in (36, 37, 38, 39, 40):
    m = re.search(r"(?m)^\s*%d\.\s(.{0,260})" % n, qt, re.S)
    seg = re.sub(r"\s+"," ", m.group(1))[:250] if m else "缺失"
    ok = ("前句+空" if re.search(r"[A-Za-z]{6,}.+\b%d\s*_{2,}" % n, seg) else "仅空或后句")
    out.append("[%d] %s\n    判定: %s" % (n, seg, ok))
open(r"D:\Android\MapMate\VideoQuiz\auditH.txt","w",encoding="utf-8").write("\n\n".join(out))
print("ok")
