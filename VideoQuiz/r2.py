import pymupdf
d = pymupdf.open(r"D:\Android\MapMate\VideoQuiz\math_rendered2.pdf")
print("pages:", d.page_count)
d[0].get_pixmap(dpi=110).save(r"D:\Android\MapMate\VideoQuiz\r2_p1.png")
d[1].get_pixmap(dpi=110).save(r"D:\Android\MapMate\VideoQuiz\r2_p2.png")
