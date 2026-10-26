import pymupdf
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_final_rendered.pdf")
print("pages:", d.page_count)
d[0].get_pixmap(dpi=110).save(r"D:\Android\MapMate\VideoQuiz\final_p1.png")
d[3].get_pixmap(dpi=110).save(r"D:\Android\MapMate\VideoQuiz\final_p4.png")
