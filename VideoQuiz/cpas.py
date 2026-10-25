import re
t = open(r"D:\Android\MapMate\VideoQuiz\checkG.txt", encoding="utf-8").read()
i = t.find("New York City is planting")
j = t.find("28.", i)
seg = t[i:j] if i>0 and j>i else t[i:i+2000]
open(r"D:\Android\MapMate\VideoQuiz\cpas.txt","w",encoding="utf-8").write(seg)
print(len(seg))
