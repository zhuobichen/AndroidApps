import pymupdf
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_rendered.pdf")
print("pages:", d.page_count)
d[0].get_pixmap(dpi=110).save(r"D:\Android\MapMate\VideoQuiz\rendered_p1.png")
d[2].get_pixmap(dpi=110).save(r"D:\Android\MapMate\VideoQuiz\rendered_p3.png")
t = "\n".join(p.get_text() for p in d)
print("文本字符数:", len(t), "（位图渲染时文本层会很少）")
