package com.zhaowen.mapmate;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 三层记忆（参照 weflow-cli 的 assistantMemory 设计）：
 *   L1 工作窗口  最近若干轮原文，条数 + 字符双闸
 *   L2 滚动摘要  滑出的旧轮次压成摘要，保留脉络
 *   L3 长期事实  从对话抽取的持久信息，带溯源，按相关度注入系统提示
 *
 * 持久化：filesDir/memory.json，带 version；读不出来时改名留档，绝不静默丢弃。
 */
public class MemoryStore {

    private static final String TAG = "MapMateMemory";
    private static final int FORMAT_VERSION = 1;

    static final int WORKING_MAX = 12;              // 条数闸
    static final int WORKING_TRIGGER_CHARS = 4000;  // 字符闸
    static final int WORKING_MIN_TURNS = 4;
    static final int SUMMARY_MAX = 400;
    static final int FACTS_MAX = 30;
    static final int FACT_MAX_LEN = 60;
    static final int FACT_EVERY = 4;                // 每 4 个用户轮抽一次
    static final int FACTS_INJECT_BUDGET = 600;     // 注入字符预算
    static final double FACT_RELEVANCE_FLOOR = 0.2;
    static final int FACTS_MIN_RECENT = 6;

    public static class Turn {
        public String role;
        public String content;
        Turn(String r, String c) { role = r; content = c; }
    }

    public static class Place {
        public String name = "";
        public String address = "";
        public double lat, lng;
    }

    public static class Fact {
        public String content;
        public long ts;
        public int sourceTurn;
        public String sourceQuote;
        public long usedAt;
    }

    public interface LlmCaller {
        String call(String prompt, int maxTokens) throws Exception;
    }

    private final File file;
    private final Context ctx;
    private final List<Turn> working = new ArrayList<>();
    private final List<Fact> facts = new ArrayList<>();
    private final java.util.Map<String, Place> places = new java.util.LinkedHashMap<>();
    private final java.util.List<Place> favorites = new java.util.ArrayList<>();
    private String summary = "";
    private int turnCount = 0;
    private String loadIssue = "";

    public MemoryStore(Context ctx) {
        this.ctx = ctx;
        this.file = new File(ctx.getFilesDir(), "memory.json");
        load();
    }

    public String problem() { return loadIssue; }

    // ---------- 持久化 ----------

    private void load() {
        if (!file.exists()) return;
        try {
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(raw);
            int v = o.optInt("version", -1);
            if (v != FORMAT_VERSION) {
                quarantine("记忆文件版本是 " + v + "，本程序只认 " + FORMAT_VERSION);
                return;
            }
            summary = o.optString("summary", "");
            turnCount = o.optInt("turnCount", 0);
            JSONArray w = o.optJSONArray("working");
            if (w != null) {
                for (int i = 0; i < w.length(); i++) {
                    JSONObject t = w.getJSONObject(i);
                    working.add(new Turn(t.optString("role"), t.optString("content")));
                }
            }
            JSONArray fav = o.optJSONArray("favorites");
            if (fav != null) {
                for (int i = 0; i < fav.length(); i++) {
                    JSONObject fo = fav.getJSONObject(i);
                    Place p = new Place();
                    p.address = fo.optString("address", "");
                    p.lat = fo.optDouble("lat", 0);
                    p.lng = fo.optDouble("lng", 0);
                    p.name = fo.optString("name", p.address);
                    if (p.lat != 0) favorites.add(p);
                }
            }
            JSONObject pl = o.optJSONObject("places");
            if (pl != null) {
                java.util.Iterator<String> it = pl.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject po = pl.optJSONObject(k);
                    if (po == null) continue;
                    Place p = new Place();
                    p.address = po.optString("address", "");
                    p.lat = po.optDouble("lat", 0);
                    p.lng = po.optDouble("lng", 0);
                    places.put(k, p);
                }
            }
            JSONArray f = o.optJSONArray("facts");
            if (f != null) {
                for (int i = 0; i < f.length(); i++) {
                    JSONObject x = f.getJSONObject(i);
                    Fact fa = new Fact();
                    fa.content = x.optString("content");
                    fa.ts = x.optLong("ts");
                    fa.sourceTurn = x.optInt("sourceTurn", -1);
                    fa.sourceQuote = x.optString("sourceQuote", "");
                    fa.usedAt = x.optLong("usedAt", 0);
                    if (!fa.content.isEmpty()) facts.add(fa);
                }
            }
            Log.i(TAG, "loaded: working=" + working.size() + " facts=" + facts.size() + " turns=" + turnCount);
        } catch (Throwable t) {
            quarantine("记忆文件无法解析（" + t.getMessage() + "）");
        }
    }

    /** 读不出来就改名留档，不许静默清空 */
    private void quarantine(String reason) {
        File bad = new File(file.getParentFile(), "memory.unreadable-" + System.currentTimeMillis() + ".json");
        boolean ok = file.renameTo(bad);
        loadIssue = reason + (ok ? "；原文件已留档为 " + bad.getName() : "；留档失败，未改动原文件");
        working.clear();
        facts.clear();
        summary = "";
        turnCount = 0;
        Log.w(TAG, loadIssue);
    }

    public void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", FORMAT_VERSION);
            o.put("summary", summary);
            o.put("turnCount", turnCount);
            JSONArray w = new JSONArray();
            for (Turn t : working) w.put(new JSONObject().put("role", t.role).put("content", t.content));
            o.put("working", w);
            JSONArray f = new JSONArray();
            for (Fact fa : facts) {
                JSONObject x = new JSONObject()
                        .put("content", fa.content)
                        .put("ts", fa.ts)
                        .put("sourceTurn", fa.sourceTurn)
                        .put("sourceQuote", fa.sourceQuote)
                        .put("usedAt", fa.usedAt);
                f.put(x);
            }
            o.put("facts", f);
            JSONObject pl = new JSONObject();
            for (java.util.Map.Entry<String, Place> e : places.entrySet()) {
                pl.put(e.getKey(), new JSONObject()
                        .put("address", e.getValue().address)
                        .put("lat", e.getValue().lat)
                        .put("lng", e.getValue().lng));
            }
            o.put("places", pl);
            JSONArray favArr = new JSONArray();
            for (Place p : favorites) {
                favArr.put(new JSONObject().put("name", p.name).put("address", p.address)
                        .put("lat", p.lat).put("lng", p.lng));
            }
            o.put("favorites", favArr);

            // 原子写：先写临时文件再改名
            File tmp = new File(file.getParentFile(), "memory.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (file.exists() && !file.delete()) Log.w(TAG, "delete old memory failed");
            if (!tmp.renameTo(file)) {
                Files.copy(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.e(TAG, "save failed", t);
        }
    }

    // ---------- L1 ----------

    public void addTurn(String role, String content) {
        working.add(new Turn(role, content));
        HistoryArchive.append(ctx, role, content);
        if ("user".equals(role)) turnCount++;
    }

    public List<Turn> working() { return working; }
    public String summary() { return summary; }
    public List<Fact> facts() { return facts; }
    public int turnCount() { return turnCount; }

    private int chars(List<Turn> turns) {
        int n = 0;
        for (Turn t : turns) n += t.content == null ? 0 : t.content.length();
        return n;
    }

    public boolean needsCompression() {
        return working.size() > WORKING_MAX || chars(working) > WORKING_TRIGGER_CHARS;
    }

    // ---------- L2 ----------

    public void compressIfNeeded(LlmCaller llm) {
        if (!needsCompression()) return;

        // 按字符预算保留尾部，条数保底
        int keep = 0, used = 0;
        for (int i = working.size() - 1; i >= 0; i--) {
            int len = working.get(i).content == null ? 0 : working.get(i).content.length();
            if (keep > 0 && used + len > WORKING_TRIGGER_CHARS / 2) break;
            used += len;
            keep++;
            if (used >= WORKING_TRIGGER_CHARS / 2) break;
        }
        keep = Math.max(WORKING_MIN_TURNS, keep);
        keep = Math.min(Math.max(keep, 1), Math.max(working.size() - 1, 1));

        List<Turn> out = new ArrayList<>(working.subList(0, working.size() - keep));
        List<Turn> kept = new ArrayList<>(working.subList(working.size() - keep, working.size()));

        try {
            String merged = llm.call(buildSummaryPrompt(summary, out), 300);
            if (merged != null && !merged.trim().isEmpty()) {
                summary = merged.trim();
                if (summary.length() > SUMMARY_MAX) summary = summary.substring(summary.length() - SUMMARY_MAX);
            }
        } catch (Throwable e) {
            // LLM 失败降级：粗暴拼接，保住不丢
            StringBuilder sb = new StringBuilder(summary);
            for (Turn outTurn : out) sb.append(" / ").append(outTurn.content, 0, Math.min(40, outTurn.content.length()));
            summary = sb.length() > SUMMARY_MAX ? sb.substring(sb.length() - SUMMARY_MAX) : sb.toString();
        }
        working.clear();
        working.addAll(kept);
        save();
        Log.i(TAG, "compressed: out=" + out.size() + " kept=" + keep);
    }

    private String buildSummaryPrompt(String prev, List<Turn> turns) {
        StringBuilder sb = new StringBuilder();
        for (Turn t : turns) sb.append("用户".equals(t.role) || "user".equals(t.role) ? "用户" : "助手")
                .append(": ").append(t.content).append("\n");
        return "把下面的对话压缩进已有摘要。保留仍然成立的信息，丢掉过期的，合成一份，不要逐字复制旧摘要。"
                + "输出 4 个节：## 用户诉求 / ## 已确认的信息 / ## 待办 / ## 当前进展。每节没内容写 (none)。"
                + "总长不超过 300 字，只输出摘要正文。\n\n[已有摘要]\n"
                + (prev == null || prev.isEmpty() ? "(无)" : prev)
                + "\n\n[新增对话]\n" + sb;
    }

    // ---------- L3 ----------

    /** 每 FACT_EVERY 个用户轮抽一次事实 */
    public void extractFactsIfNeeded(LlmCaller llm) {
        if (turnCount == 0 || turnCount % FACT_EVERY != 0) return;
        int from = Math.max(0, working.size() - FACT_EVERY * 2);
        List<Turn> recent = new ArrayList<>(working.subList(from, working.size()));
        if (recent.isEmpty()) return;

        String lastUser = "";
        for (int i = recent.size() - 1; i >= 0; i--) {
            Turn t = recent.get(i);
            if ("user".equals(t.role)) { lastUser = t.content; break; }
        }
        final int srcTurn = turnCount;
        final String srcQuote = lastUser.replaceAll("\\s+", " ");

        try {
            String raw = llm.call(buildFactPrompt(recent), 200);
            if (raw == null) return;
            int l = raw.indexOf('['), r = raw.lastIndexOf(']');
            if (l < 0 || r <= l) return;
            JSONArray arr = new JSONArray(raw.substring(l, r + 1));
            int added = 0;
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (s.isEmpty()) continue;
                if (s.length() > FACT_MAX_LEN) s = s.substring(0, FACT_MAX_LEN);
                if (rememberFact(s, srcTurn, srcQuote)) added++;
            }
            if (added > 0) { save(); Log.i(TAG, "facts +" + added + " total=" + facts.size()); }
        } catch (Throwable t) {
            Log.w(TAG, "extract facts failed: " + t.getMessage());
        }
    }

    private String buildFactPrompt(List<Turn> recent) {
        StringBuilder sb = new StringBuilder();
        for (Turn t : recent) sb.append("user".equals(t.role) ? "用户: " : "助手: ").append(t.content).append("\n");
        return "从对话中提取关于用户的持久事实（身份/偏好/常去地点/人际关系/惯例）。每条不超过40字。"
                + "只提取用户自己说过的、长期成立的信息，助手推测不算，临时内容不算。"
                + "只输出 JSON 字符串数组，最多 3 条，没有就输出 []。\n\n[对话]\n" + sb;
    }

    public boolean addFavorite(String name, String address, double lat, double lng) {
        for (Place f : favorites) {
            if (Math.abs(f.lat - lat) < 1e-6 && Math.abs(f.lng - lng) < 1e-6) return false;
        }
        Place p = new Place();
        p.name = name;
        p.address = address;
        p.lat = lat;
        p.lng = lng;
        favorites.add(0, p);
        while (favorites.size() > 50) favorites.remove(favorites.size() - 1);
        save();
        return true;
    }

    public java.util.List<Place> favorites() { return favorites; }

    /** 家/公司等固定地点，坐标直接存，不经过地理编码 */
    public void setPlace(String key, String address, double lat, double lng) {
        Place p = new Place();
        p.address = address;
        p.lat = lat;
        p.lng = lng;
        places.put(key, p);
        save();
    }

    public Place getPlace(String key) { return places.get(key); }

    public java.util.Set<String> placeKeys() { return places.keySet(); }

    /** 从一句话里认出固定地点：家 / 公司 */
    public Place findPlaceByText(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.contains("家") || t.toLowerCase().contains("home")) return places.get("home");
        if (t.contains("公司") || t.contains("单位") || t.contains("上班")) return places.get("company");
        return null;
    }

    /** 用一条新事实替换掉所有提到 keyword 的旧事实，避免「家」有两个版本 */
    public void replaceFactsAbout(String keyword, String newFact) {
        for (int i = facts.size() - 1; i >= 0; i--) {
            if (facts.get(i).content.contains(keyword)) facts.remove(i);
        }
        rememberFact(newFact, turnCount, "");
        save();
    }

    public boolean addFact(String content) {
        boolean added = rememberFact(content.trim(), turnCount, "");
        if (added) save();
        return added;
    }

    /** 归一化只用于比较 */
    static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", "")
                .replaceAll("[，。、；：！？,.;:!?（）()【】\\[\\]{}'\"「」『』·—～~]", "")
                .toLowerCase();
    }

    /** 相同 → 忽略；包含且相似度 ≥ 0.5 → 长的胜；否则新增 */
    private boolean rememberFact(String content, int srcTurn, String srcQuote) {
        if (content == null || content.trim().isEmpty()) return false;
        String text = content.trim();
        String norm = normalize(text);
        for (int i = 0; i < facts.size(); i++) {
            Fact e = facts.get(i);
            String other = normalize(e.content);
            if (norm.equals(other)) {
                if (e.sourceTurn < 0 && srcTurn > 0) { e.sourceTurn = srcTurn; e.sourceQuote = srcQuote; }
                return false;
            }
            boolean contains = other.contains(norm) || norm.contains(other);
            if (!contains) continue;
            double ratio = (double) Math.min(norm.length(), other.length())
                    / Math.max(norm.length(), other.length());
            if (ratio < 0.5) continue;
            if (norm.length() > other.length()) {
                Fact nf = new Fact();
                nf.content = text;
                nf.ts = e.ts != 0 ? e.ts : System.currentTimeMillis();
                nf.sourceTurn = srcTurn;
                nf.sourceQuote = srcQuote;
                facts.set(i, nf);
                return true;
            }
            return false;
        }
        Fact f = new Fact();
        f.content = text;
        f.ts = System.currentTimeMillis();
        f.sourceTurn = srcTurn;
        f.sourceQuote = srcQuote;
        facts.add(f);
        while (facts.size() > FACTS_MAX) facts.remove(0);
        return true;
    }

    /** 字符二元组：中文没有词边界 */
    private static java.util.Set<String> bigrams(String s) {
        String n = normalize(s);
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i + 1 < n.length(); i++) out.add(n.substring(i, i + 2));
        return out;
    }

    private static double overlap(java.util.Set<String> a, java.util.Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int hit = 0;
        for (String g : a) if (b.contains(g)) hit++;
        return (double) hit / Math.min(a.size(), b.size());
    }

    /** 先按相关度筛，再按预算裁；一条都不相关时给最近的几条 */
    public List<Fact> selectFactsForInjection(String question) {
        List<Fact> out = new ArrayList<>();
        if (facts.isEmpty()) return out;

        java.util.Set<String> q = bigrams(question == null ? "" : question);
        String qText = normalize(question == null ? "" : question);

        List<int[]> order = new ArrayList<>();
        double[] sims = new double[facts.size()];
        for (int i = 0; i < facts.size(); i++) {
            String ft = normalize(facts.get(i).content);
            boolean direct = qText.length() >= 2 && (ft.contains(qText) || qText.contains(ft));
            sims[i] = direct ? 1 : overlap(q, bigrams(facts.get(i).content));
            order.add(new int[]{i});
        }
        final double[] s = sims;
        order.sort((a, b) -> Double.compare(s[b[0]], s[a[0]]));

        List<Integer> pool = new ArrayList<>();
        for (int[] item : order) if (sims[item[0]] >= FACT_RELEVANCE_FLOOR) pool.add(item[0]);
        if (pool.isEmpty()) {
            for (int i = Math.max(0, facts.size() - FACTS_MIN_RECENT); i < facts.size(); i++) pool.add(i);
        }

        int used = 0;
        List<Integer> chosen = new ArrayList<>();
        for (int idx : pool) {
            int size = facts.get(idx).content.length() + 2;
            if (!chosen.isEmpty() && used + size > FACTS_INJECT_BUDGET) break;
            chosen.add(idx);
            used += size;
        }
        chosen.sort(Integer::compareTo);
        long now = System.currentTimeMillis();
        for (int idx : chosen) {
            facts.get(idx).usedAt = now;
            out.add(facts.get(idx));
        }
        return out;
    }

    /** 组装系统提示里的记忆块 */
    public String memoryBlock(String question) {
        StringBuilder sb = new StringBuilder();
        if (!summary.isEmpty()) sb.append("[历史摘要]\n").append(summary).append("\n");
        List<Fact> sel = selectFactsForInjection(question);
        if (!sel.isEmpty()) {
            sb.append("[长期记忆]\n");
            for (Fact f : sel) sb.append("- ").append(f.content).append("\n");
            int withheld = facts.size() - sel.size();
            if (withheld > 0) sb.append("（另有 ").append(withheld).append(" 条未列出）\n");
        }
        return sb.toString();
    }
}