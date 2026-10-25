import re
txt = open(r"D:\Android\MapMate\VideoQuiz\final8.txt", encoding="utf-8").read()
qi = txt.find("参考答案")
qt = txt[:qi]
out=[]
for n in [36,37,38,39,40,41,45,46,47,48,56,57,58]:
    m = re.search(rf"(?m)^\s*{n}\.\s(.{{0,200}})", qt, re.S)
    if m:
        s = re.sub(r"\s+", " ", m.group(1)).strip()
        out.append(f"[{n}] {s[:180]}")
open(r"D:\Android\MapMate\VideoQuiz\audit_seg.txt","w",encoding="utf-8").write("\n".join(out))
print("ok")
