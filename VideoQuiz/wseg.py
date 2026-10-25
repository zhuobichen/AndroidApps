import re
txt = open(r"D:\Android\MapMate\VideoQuiz\finalA.txt", encoding="utf-8").read()
i = txt.find("写作题")
j = txt.find("识别质量提示")
seg = txt[i:j] if i>=0 and j>i else txt[i:i+2500]
open(r"D:\Android\MapMate\VideoQuiz\writing_now.txt","w",encoding="utf-8").write(seg)
print("len:", len(seg))
