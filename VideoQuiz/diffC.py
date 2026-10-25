import re, difflib

ours = open(r"D:\Android\MapMate\VideoQuiz\checkG.txt", encoding="utf-8").read()
norm = lambda s: re.sub(r"[^a-z0-9 ]", " ", s.lower())
ours_n = norm(ours)
ours_sents = [s.strip() for s in re.split(r"(?<=[.!?])\s+", ours) if len(s.strip()) > 25]

ref = """In line with longstanding initiatives to expand the green spaces, New York City is planting tens of thousands of trees each year. They provide shade, lower surface temperatures, absorb a surprising amount of airborne carbon, remove tiny pollutants, and provide wildlife habitat along with just plain beauty. Something could go wrong, though, according to a new study. Oaks and sweetgums, which currently account for a majority of the city's trees, produce huge amounts of a chemical substance called isoprene. Harmless by itself, isoprene interacts rapidly with polluting nitrogen oxides released by vehicles, buildings and industry to form ground-level ozone a major factor in many breathing problems. The research, carried out by scientists at the Columbia Climate School and other institutions, found that if the city maintains past species patterns in new plantings, isoprene production in Manhattan in coming decades will go up by about 140%, and resulting summer ozone levels as much as 30%. We're all for planting more trees. They bring so many good things, said study coauthor Roisin Commane. But if we're not careful, we could make air quality worse. The Parks Department is not oblivious to the issue. A study carried out by some of its researchers several years ago concluded that city trees did release isoprene. We didn't make a big deal of that, said Novem Auyeung, a Parks Department senior scientist. She said trees should not be viewed as the enemy. Oaks are keystone species, she pointed out, providing food and habitat for native insects, birds and other animals."""

ref_sents = [s.strip() for s in re.split(r"(?<=[.!?])\s+", ref) if len(s.strip()) > 25]
lines = ["参考句数: %d" % len(ref_sents)]
missing = 0
for s in ref_sents:
    best = 0.0
    for o in ours_sents:
        r = difflib.SequenceMatcher(None, norm(s), norm(o)).ratio()
        if r > best: best = r
    flag = "OK " if best >= 0.75 else "缺 "
    if best < 0.75: missing += 1
    lines.append("%s (%.2f) %s" % (flag, best, s[:90]))
lines.append("缺失/差异较大: %d 句" % missing)
open(r"D:\Android\MapMate\VideoQuiz\diffC.txt","w",encoding="utf-8").write("\n".join(lines))
print("ok")
