import pymupdf, re
for tag, path in (("试卷", r"D:\Android\MapMate\VideoQuiz\paper.pdf"),
                  ("答案", r"D:\Android\MapMate\VideoQuiz\answers.pdf")):
    d = pymupdf.open(path)
    txt = "\n".join(p.get_text() for p in d)
    nums = len(re.findall(r"(?m)^\s*(\d{1,2})\.\s", txt))
    ans = len(re.findall(r"(?m)^\s*(\d{1,2})\.\s*([A-G])\s", txt))
    print(f"{tag}: pages={d.page_count} 题号行={nums} 字母答案={ans} 原文={('【原' in txt)} 写作={('写作题' in txt)}")
