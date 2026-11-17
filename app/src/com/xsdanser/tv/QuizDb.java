package com.xsdanser.tv;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 内置离线 SQLite 题库 · v12（schema=3）。
 *
 * <p>题库表按用户新 JSON 格式重设计：questions 增 edition / topic_group 两列，
 * taxonomy 表记录「库里真实存在的分类组合」，家长设置的可选值全部由该表数据驱动，
 * 不再硬编码 1–9 年级、8 种教材、K12 单元表。
 *
 * <p><b>出题规则（v12）</b>
 * <ol>
 *   <li>硬条件：edition = 家长选的教材版本，grade = 家长选的年级，
 *       subject = 学科轮盘赌结果（权重 0 不出题）。</li>
 *   <li>可选条件：term 非「全学年」且库里确有该册时加 term 过滤；
 *       该学科的知识点大类非「全部」时加 topic_group 过滤。</li>
 *   <li>兜底链逐级放宽，但每一层都保留 edition：
 *       全条件 → 去大类 → 去学期 → 去年级 → 去学科 → 全库（仅极端异常）。</li>
 * </ol>
 * 全程只读本地 assets 复制出来的库，零网络；任何异常返回 null 由 QuizEngine 回退。
 *
 * <p>兼容性：所有 public 方法签名与 v9~v11 完全一致，QuizOverlayService 中带轮次补丁的
 * smali 不需要改动。random(6 参) 的 syllabus / termIndex / unitLimit 仅保留参数占位，
 * 新规则改从 SharedPreferences 读取，避免旧 K12 单元表影响新题库。
 */
public final class QuizDb {

    private static final String TAG = "XsdAnser.QuizDb";
    private static final String NAME = "xsdanser_bank.db";
    private static final String VERSION_ASSET = "xsdanser_bank.db.version";
    private static final String PREF = "tvbank";   // 必须与 SettingsStore.PREF 一致

    // v12 新增设置键（与 SettingsActivity 共用）
    public static final String K_EDITION = "qb_edition";
    public static final String K_GRADE = "qb_grade";
    public static final String K_TERM = "qb_term";
    public static final String K_GROUP_PREFIX = "qb_group_";
    public static final String TERM_ALL = "全学年";
    /** v47：家长设置「知识点范围 / 学科配比」的第 4 项，对应所有非语数英题目。 */
    public static final String OTHER_GROUP = "其它";

    private static final String SELECT =
            "SELECT topic,text,opt_a,opt_b,opt_c,opt_d,answer_index,explain,subject "
                    + "FROM questions ";

    private static QuizDb instance;
    private final SQLiteDatabase db;
    private final Context app;
    private final int cachedCount;
    private final int cachedKnowledge;
    private final int cachedSubjects;
    private final int cachedBands;
    private final String cachedVersion;
    private final String cachedNote;
    private final String[] editions;
    private final int[] grades;
    private final String[] gradeLabels;
    private final String[] terms;
    private final String[] topicGroups;
    private final int topicCount;
    private final java.util.HashMap<String, Integer> countCache =
            new java.util.HashMap<String, Integer>();

    public static synchronized QuizDb peek() { return instance; }

    private QuizDb(Context c, SQLiteDatabase db, int count, int knowledge,
                   int subjects, int bands, String version, String note) {
        this.app = c.getApplicationContext();
        this.db = db;
        this.cachedCount = count;
        this.cachedKnowledge = knowledge;
        this.cachedSubjects = subjects;
        this.cachedBands = bands;
        this.cachedVersion = version;
        this.cachedNote = note;
        this.editions = strings(db, "SELECT DISTINCT edition FROM questions "
                + "WHERE edition<>'' ORDER BY edition");
        // 年级选择按 grade 整数唯一，避免导入的非标准标签（如「3年级难」）与内置「3年级」
        // 并列成两个 grade=3 的项：否则切换年级时数字停在 3 不变、显示永远「三年级」，
        // 家长设置里看起来就像「无法更换年级」。标签取该年级的规范写法
        // （优先「N年级」/「中文数字+年级」，否则该年级首条标签）。
        this.grades = ints(db, "SELECT DISTINCT grade FROM questions "
                + "WHERE grade>=1 AND grade<=9 ORDER BY grade", new String[0]);
        this.gradeLabels = new String[this.grades.length];
        for (int i = 0; i < this.grades.length; i++) {
            this.gradeLabels[i] = canonicalGradeLabel(db, this.grades[i]);
        }
        this.terms = strings(db, "SELECT DISTINCT term FROM questions "
                + "WHERE term<>'' AND term<>? ORDER BY term", new String[]{TERM_ALL});
        this.topicGroups = strings(db, "SELECT DISTINCT subject || '·' || topic_group "
                + "FROM questions WHERE topic_group<>'' ORDER BY subject,topic_group");
        this.topicCount = scalar(db, "SELECT COUNT(DISTINCT topic) FROM questions");
        syncLegacyKeys(c);
    }

    /**
     * 首次打开时把库内真实版本 / 年级写回 v11 之前的旧键。
     * 答题页标签仍由 QuizOverlayService（带轮次补丁的 smali）拼装，
     * 它读的是 SettingsStore 的 edition / grade，不重编译该类也能让标签变正确。
     */
    private void syncLegacyKeys(Context c) {
        try {
            String ed = scopeEdition();
            int idx = 1;                     // 人教版
            for (int i = 0; i < SettingsStore.EDITIONS.length; i++) {
                if (SettingsStore.EDITIONS[i].equals(ed)) { idx = i; break; }
            }
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            SharedPreferences.Editor e = p.edit()
                    .putInt("edition", idx)
                    .putInt("grade", scopeGrade())
                    // v47：全学科开关没有 UI，按「其它」配比联动。历史 smali 的
                    // SubjectWeights 要 allSubjects=true 才让第 4 槽参与轮盘赌；
                    // 与 SettingsActivity.save() 的写法保持一致，冷启动也不会被重置掉。
                    .putBoolean("allSubjects", p.getInt("w" + Subjects.OTHER, 0) > 0)
                    .putInt("termIdx", 0)
                    .putInt("unitLimit", 0);
            for (int s = 0; s < Subjects.COUNT; s++) {
                e.putInt(Subjects.editionKey(Subjects.norm(s)), idx);
            }
            e.commit();
        } catch (Exception ignored) {
        }
    }

    /** 打开内置题库；失败返回 null，调用方必须能优雅回退到生成器。 */
    public static synchronized QuizDb open(Context c) {
        if (instance != null) {
            // v49：前台服务可能让进程在「关闭 App」后继续存活，此时单例被复用、
            // 构造器里的 syncLegacyKeys 不会重跑；若期间导入过题库，持久化年级 / 版本等
            // 可能与库内真实内容不一致。重开应用时主动再同步一次，保证首页 / 家长设置不空白。
            instance.syncLegacyKeys(c.getApplicationContext());
            return instance;
        }
        try {
            Context app = c.getApplicationContext();
            File dest = app.getDatabasePath(NAME);
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return null;

            String assetV = assetVersion(app);
            String localV = dest.exists() ? localVersion(dest) : null;
            if (localV == null || !localV.equals(assetV)) {
                if (!copyAtomically(app, dest)) return null;
            }
            SQLiteDatabase d = SQLiteDatabase.openDatabase(
                    dest.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            int qCount = scalar(d, "SELECT COUNT(*) FROM questions");
            if (qCount <= 0) {
                d.close();
                return null;
            }
            instance = new QuizDb(app, d, qCount,
                    scalar(d, "SELECT COUNT(*) FROM knowledge"),
                    // v47：学科数改从 questions 统计，导入的非三科题库才会体现在「题库信息」里。
                    scalar(d, "SELECT COUNT(*) FROM (SELECT DISTINCT subject "
                            + "FROM questions WHERE subject<>'')"),
                    scalar(d, "SELECT COUNT(*) FROM (SELECT DISTINCT grade_label FROM knowledge)"),
                    readString(d, "SELECT v FROM meta WHERE k='version'"),
                    readString(d, "SELECT v FROM meta WHERE k='licenseNote'"));
            return instance;
        } catch (Exception e) {
            Log.e(TAG, "内置题库打开失败，回退生成器", e);
            instance = null;
            return null;
        }
    }

    private static String assetVersion(Context c) {
        InputStream in = null;
        try {
            in = c.getAssets().open(VERSION_ASSET);
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n > 0) {
                String v = new String(buf, 0, n, "UTF-8").trim();
                if (!v.isEmpty()) return v;
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(in);
        }
        return "size:" + assetSize(c);
    }

    private static long assetSize(Context c) {
        InputStream in = null;
        long total = 0L;
        try {
            in = c.getAssets().open(NAME);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) total += n;
        } catch (Exception e) {
            return -1L;
        } finally {
            closeQuietly(in);
        }
        return total;
    }

    private static String localVersion(File f) {
        SQLiteDatabase d = null;
        Cursor c = null;
        try {
            d = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            c = d.rawQuery("SELECT v FROM meta WHERE k='version'", null);
            return c.moveToFirst() ? c.getString(0) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) { try { c.close(); } catch (Exception ignored) { } }
            if (d != null) { try { d.close(); } catch (Exception ignored) { } }
        }
    }

    private static boolean copyAtomically(Context c, File dest) {
        File tmp = new File(dest.getParentFile(), NAME + ".tmp");
        InputStream in = null;
        OutputStream out = null;
        SQLiteDatabase probe = null;
        try {
            in = c.getAssets().open(NAME);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            ((FileOutputStream) out).getFD().sync();
            out.close();
            out = null;
            in.close();
            in = null;

            probe = SQLiteDatabase.openDatabase(tmp.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            if (scalar(probe, "SELECT COUNT(*) FROM questions") <= 0) {
                tmp.delete();
                return false;
            }
            probe.close();
            probe = null;
            if (dest.exists() && !dest.delete()) {
                tmp.delete();
                return false;
            }
            return tmp.renameTo(dest);
        } catch (Exception e) {
            Log.e(TAG, "题库复制失败", e);
            tmp.delete();
            return false;
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            if (probe != null) { try { probe.close(); } catch (Exception ignored) { } }
        }
    }

    private static int scalar(SQLiteDatabase d, String sql) {
        try (Cursor c = d.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String[] strings(SQLiteDatabase d, String sql, String... args) {
        List<String> out = new ArrayList<String>();
        Cursor c = null;
        try {
            c = d.rawQuery(sql, args.length == 0 ? null : args);
            while (c.moveToNext()) out.add(c.getString(0));
        } catch (Exception e) {
            Log.w(TAG, "分类字典读取失败: " + sql, e);
        } finally {
            if (c != null) { try { c.close(); } catch (Exception ignored) { } }
        }
        return out.toArray(new String[0]);
    }

    private static int[] ints(SQLiteDatabase d, String sql, String[] args) {
        String[] s = strings(d, sql, args);
        int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) {
            try { out[i] = Integer.parseInt(s[i]); } catch (Exception e) { out[i] = 0; }
        }
        return out;
    }

    private static void closeQuietly(java.io.Closeable x) {
        if (x != null) { try { x.close(); } catch (Exception ignored) { } }
    }

    // ---------- 基础元数据（签名与旧版一致） ----------

    public int count() { return cachedCount; }
    public int knowledgeCount() { return cachedKnowledge; }
    public int subjectCount() { return cachedSubjects; }
    public int gradeBandCount() { return cachedBands; }
    public String version() { return cachedVersion; }
    public String note() { return cachedNote; }
    public int topicCount() { return topicCount; }
    public int topicGroupCount() { return topicGroups.length; }
    public String[] editions() { return editions.clone(); }
    public int[] grades() { return grades.clone(); }
    public String[] gradeLabels() { return gradeLabels.clone(); }
    public String[] terms() { return terms.clone(); }
    public String[] topicGroups() { return topicGroups.clone(); }

    /** 题库信息一行：教材版本 · 年级 · 题数 · 学科 · 知识点 · 大类。 */
    public String bankSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(editions.length == 1 ? editions[0] : (editions.length + " 个版本"));
        sb.append(" · ");
        sb.append(gradeLabels.length == 1 ? gradeLabels[0] : (gradeLabels.length + " 个年级"));
        sb.append(" · ").append(cachedCount).append(" 题 · ")
                .append(cachedSubjects).append(" 学科 · ")
                .append(topicCount).append(" 知识点 · ")
                .append(topicGroups.length).append(" 大类");
        return sb.toString();
    }

    /** 某学科可选的大类（不含「全部」，由界面自行加）。 */
    public String[] groupsFor(String subject) {
        if (OTHER_GROUP.equals(subject) || "其他学科".equals(subject)) return groupsForOther();
        List<String> out = new ArrayList<String>();
        String prefix = subject + "·";
        for (String g : topicGroups) {
            if (g.startsWith(prefix)) out.add(g.substring(prefix.length()));
        }
        return out.toArray(new String[0]);
    }

    /**
     * v47：「其它」没有单一 subject，取所有非语数英题目的 topic_group 去重。
     * topicGroups 缓存项格式为 "subject·group"，导入的非三科题缺省大类会被
     * importDownload() 补成学科名，因此家长可在这里按学科（物理 / 历史 …）筛选。
     */
    private String[] groupsForOther() {
        java.util.TreeSet<String> set = new java.util.TreeSet<String>();
        for (String g : topicGroups) {
            int p = g.indexOf('·');
            if (p < 0) continue;
            if (isCoreSubject(g.substring(0, p))) continue;
            String grp = g.substring(p + 1);
            if (!grp.isEmpty()) set.add(grp);
        }
        return set.toArray(new String[0]);
    }

    private static String readString(SQLiteDatabase d, String sql) {
        try (Cursor c = d.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getString(0) : "";
        } catch (Exception e) {
            return "";
        }
    }

    public int countFor(int grade, int subject) {
        String key = grade + ":" + subject;
        synchronized (countCache) {
            Integer v = countCache.get(key);
            if (v != null) return v.intValue();
        }
        int result;
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM questions WHERE grade=? AND subject=?",
                new String[]{Integer.toString(grade), subjectName(subject)})) {
            result = c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            result = 0;
        }
        synchronized (countCache) { countCache.put(key, Integer.valueOf(result)); }
        return result;
    }

    public int countForGrade(int grade) {
        String key = grade + ":*";
        synchronized (countCache) {
            Integer v = countCache.get(key);
            if (v != null) return v.intValue();
        }
        int result;
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM questions WHERE grade=?",
                new String[]{Integer.toString(grade)})) {
            result = c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            result = 0;
        }
        synchronized (countCache) { countCache.put(key, Integer.valueOf(result)); }
        return result;
    }

    public boolean hasOtherQuestions() {
        synchronized (countCache) {
            Integer v = countCache.get("other_pool");
            if (v != null) return v.intValue() > 0;
        }
        int n = scalar(db, "SELECT COUNT(*) FROM questions "
                + "WHERE subject NOT IN ('数学','语文','英语')");
        synchronized (countCache) { countCache.put("other_pool", Integer.valueOf(n)); }
        return n > 0;
    }

    // ---------- v12 出题范围（全部数据驱动） ----------

    private SharedPreferences sp() {
        return app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static boolean contains(String[] a, String v) {
        if (v == null || a == null) return false;
        for (String s : a) if (v.equals(s)) return true;
        return false;
    }

    private static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int s : a) if (s == v) return true;
        return false;
    }

    /** 家长选的教材版本；设置值不在库中时自动回落到库里第一个版本。 */
    public String scopeEdition() {
        String want = sp().getString(K_EDITION, "");
        if (contains(editions, want)) return want;
        return editions.length > 0 ? editions[0] : "";
    }

    /** 家长选的年级；设置值不在库中时自动回落到库里第一个年级。 */
    public int scopeGrade() {
        int want = sp().getInt(K_GRADE, 0);
        if (contains(grades, want)) return want;
        return grades.length > 0 ? grades[0] : 3;
    }

    /** 学期过滤值；全学年 / 库中无该册 / 未设置 → null（不加条件）。 */
    public String scopeTerm() {
        String want = sp().getString(K_TERM, "");
        if (want.isEmpty() || TERM_ALL.equals(want) || !contains(terms, want)) return null;
        return want;
    }

    /** 该学科的知识点大类；「全部」或未设置 → null。 */
    public String scopeGroup(String subject) {
        String key = K_GROUP_PREFIX + subjectKey(subject);
        String want = sp().getString(key, "");
        if (want.isEmpty() || !contains(groupsFor(subject), want)) return null;
        return want;
    }

    private static String subjectKey(String subject) {
        if ("数学".equals(subject)) return "math";
        if ("语文".equals(subject)) return "chinese";
        if ("英语".equals(subject)) return "english";
        return "other";
    }

    // ---------- 抽题 ----------

    public QuizEngine.Question random(int grade, int subject, boolean allSubjects) {
        return random(grade, subject, allSubjects, null, Syllabus.TERM_ALL, 0);
    }

    /**
     * v12 抽题：忽略旧 K12 的 syllabus / termIndex / unitLimit 参数（仅保留签名兼容），
     * 改按 edition + grade + subject 硬条件、term + topic_group 可选条件逐级放宽。
     */
    public QuizEngine.Question random(int ignoredGrade, int subject, boolean allSubjects,
                                      Syllabus ignoredSyllabus, int ignoredTerm,
                                      int ignoredUnitLimit) {
        String edition = scopeEdition();
        int grade = scopeGrade();
        String term = scopeTerm();
        int s = Subjects.norm(subject);
        boolean other = s == Subjects.OTHER && allSubjects;
        String name = other ? null : subjectName(s);
        if (!other && name == null) name = "数学";
        // v47：其它学科也能按「知识点范围 → 其它」选大类筛选（键 qb_group_other）。
        String grp = other ? scopeGroup(OTHER_GROUP) : scopeGroup(name);

        // 基础池：edition 永远保留，避免跨教材出题
        StringBuilder base = new StringBuilder("WHERE edition=? AND grade=? AND ");
        if (other) base.append("subject NOT IN ('数学','语文','英语')");
        else base.append("subject=?");
        List<String> a = new ArrayList<String>();
        a.add(edition);
        a.add(Integer.toString(grade));
        if (!other) a.add(name);

        String termW = "";
        if (term != null) termW = " AND term IN (?,'" + TERM_ALL + "')";
        String l1 = base.toString() + termW;
        List<String> a1 = new ArrayList<String>(a);
        if (term != null) a1.add(term);
        if (grp != null) {
            l1 += " AND topic_group=?";
            a1.add(grp);
        }

        QuizEngine.Question q = pick(l1, a1.toArray(new String[0]));
        // L2 放宽知识点大类
        if (q == null && grp != null) {
            List<String> ax = new ArrayList<String>(a);
            if (term != null) ax.add(term);
            q = pick(base.toString() + termW, ax.toArray(new String[0]));
        }
        // L3 放宽学期
        if (q == null && term != null) {
            q = pick(base.toString(), a.toArray(new String[0]));
        }
        // L4 放宽年级（保留版本 + 学科）
        if (q == null) {
            String w = "WHERE edition=? AND "
                    + (other ? "subject NOT IN ('数学','语文','英语')" : "subject=?");
            List<String> ax = new ArrayList<String>();
            ax.add(edition);
            if (!other) ax.add(name);
            q = pick(w, ax.toArray(new String[0]));
        }
        // L5 放宽学科（仍保留版本）
        if (q == null) q = pick("WHERE edition=?", new String[]{edition});
        // L6 极端兜底：库结构异常时才全库抽，避免回退参数化生成器
        if (q == null) q = pick("", null);
        return q;
    }

    /** WHERE 里的 ? 在主查询与 COUNT 子查询各出现一次，参数必须绑定两遍。 */
    private QuizEngine.Question pick(String where, String[] args) {
        String[] bind = null;
        if (args != null) {
            bind = new String[args.length * 2];
            System.arraycopy(args, 0, bind, 0, args.length);
            System.arraycopy(args, 0, bind, args.length, args.length);
        }
        String sql = SELECT + where
                + " LIMIT 1 OFFSET (ABS(RANDOM()) % MAX(1, (SELECT COUNT(*) FROM questions "
                + where + ")))";
        try (Cursor c = db.rawQuery(sql, bind)) {
            if (!c.moveToFirst()) return null;
            String bankSubject = c.getString(8);
            String topic = c.getString(0);
            if (bankSubject != null && !isCoreSubject(bankSubject)) {
                topic = bankSubject + " · " + (topic == null ? "" : topic);
            }
            String[] opts = new String[]{
                    c.getString(2), c.getString(3), c.getString(4), c.getString(5)};
            return QuizEngine.Question.fromBank(subjectFromBank(bankSubject), topic,
                    c.getString(1), opts, c.getInt(6), c.getString(7));
        } catch (Exception e) {
            Log.e(TAG, "抽题查询失败，回退下一层兜底", e);
            return null;
        }
    }

    private static boolean isCoreSubject(String s) {
        return "数学".equals(s) || "语文".equals(s) || "英语".equals(s);
    }

    private static int subjectFromBank(String s) {
        if ("数学".equals(s)) return QuizEngine.MATH;
        if ("语文".equals(s)) return QuizEngine.CHINESE;
        if ("英语".equals(s)) return QuizEngine.ENGLISH;
        return Subjects.OTHER;
    }

    private static String subjectName(int subject) {
        if (subject == QuizEngine.CHINESE) return "语文";
        if (subject == QuizEngine.ENGLISH) return "英语";
        if (subject == QuizEngine.MATH) return "数学";
        return null;
    }

    // ---------- v39：下载码导入（写入本地题库） ----------

    /** 下载码导入结果。 */
    public static final class ImportReport {
        public int inserted;
        public int skipped;
        public String error;   // null = 成功
        public String version;
        /** 插入题中属「其它」（非数学/语文/英语）的题数，用于智能提示。 */
        public int otherInserted;
        /** 插入题中属语数英三科的题数。 */
        public int coreInserted;
    }

    /**
     * 把一个下载码对应的 JSON 题库写入本地 quiz.db 的 questions 表。
     *
     * <p>约定：
     * <ul>
     *   <li>同一下载码重复导入是幂等的：导入前先 DELETE 掉上次该码写入的行
     *       （用 source = "dl:&lt;code&gt;" 标记），再插入新行。</li>
     *   <li>edition / grade / grade_label / subject 是 NOT NULL 且引擎按
     *       edition+grade 过滤，因此缺失时回落到当前家长选中的范围（保证立即可见）；
     *       term 缺失默认「全学年」（任何学期筛选下都命中）。</li>
     *   <li>不改动 meta.version，避免触发 open() 的「版本不符→从 assets 重拷」而抹掉导入数据。</li>
     *   <li>导入后把单例置空，下次 open() 重建缓存；UI 提示「重启应用后生效」。</li>
     * </ul>
     */
    public static ImportReport importDownload(Context ctx, String code, String json) {
        ImportReport r = new ImportReport();
        SQLiteDatabase d = null;
        try {
            File f = ctx.getApplicationContext().getDatabasePath(NAME);
            d = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            // 当前范围默认值：直接从本方法的读写连接 d 取（首版 / 首年级），保证导入题可见。
            // 注意：导入期间不再调用 open(ctx) 去拿只读单例——否则同一 db 文件会同时存在
            // 「读写 + 只读」两个独立句柄，旧的只读句柄在下面 instance=null 时又不会关闭，
            // 留下句柄泄漏 / 锁竞争，是反复开关 App 卡死的根因之一。
            String defEdition = firstEdition(d);
            int defGrade = firstGrade(d);
            String defGradeLabel = gradeLabelOf(d, defGrade);
            if (defGradeLabel == null || defGradeLabel.isEmpty())
                defGradeLabel = defGrade + "年级";

            // 用项目自带的纯 JVM JSON 解析器（编译 stub 不含 org.json）。
            // 顶层支持两种：{"version":...,"questions":[...]} 或 qbank-spec 的裸数组 [...]。
            Object parsed = Json.parse(json);
            Object rawQs;
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> root = (Map<String, Object>) parsed;
                r.version = dlStr(root, "version", "");
                rawQs = root.get("questions");
            } else {
                rawQs = parsed;
            }
            if (!(rawQs instanceof List)) { r.error = "JSON 缺少 questions 数组"; return r; }
            @SuppressWarnings("unchecked")
            List<Object> qs = (List<Object>) rawQs;

            d.beginTransaction();
            try {
                String src = "dl:" + code;
                d.delete("questions", "source=?", new String[]{src});
                int ins = 0, skip = 0;
                for (int i = 0; i < qs.size(); i++) {
                    Object rawQ = qs.get(i);
                    if (!(rawQ instanceof Map)) { skip++; continue; }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> q = (Map<String, Object>) rawQ;

                    // 选项：简化格式用 options 数组，qbank-spec 用 opt_a..opt_d。
                    String[] o = new String[4];
                    Object rawOpts = q.get("options");
                    if (rawOpts instanceof List) {
                        List<?> opts = (List<?>) rawOpts;
                        for (int k = 0; k < 4; k++)
                            o[k] = k < opts.size() ? dlText(opts.get(k)).trim() : "";
                    } else {
                        String[] keys = {"opt_a", "opt_b", "opt_c", "opt_d"};
                        for (int k = 0; k < 4; k++) o[k] = dlStr(q, keys[k], "");
                    }
                    // 4 项都必须非空且互不相同（与 replace_bank_v12 / check_bank 一致）。
                    HashSet<String> seen = new HashSet<String>();
                    boolean bad = false;
                    for (String x : o) {
                        if (x.isEmpty() || !seen.add(x)) { bad = true; break; }
                    }
                    if (bad) { skip++; continue; }

                    int ai = dlInt(q, "answerIndex", -1);
                    if (ai < 0) ai = dlInt(q, "answer_index", -1);
                    if (ai < 0 || ai > 3) { skip++; continue; }
                    String text = dlStr(q, "text", "").trim();
                    if (text.length() < 2) { skip++; continue; }

                    String topic = dlStr(q, "topic", "");
                    String explain = dlStr(q, "explain", "");
                    String subject = dlStr(q, "subject", "数学");
                    String edition = dlStr(q, "edition", defEdition);
                    String gradeLabel = dlStr2(q, "gradeLabel", "grade_label", "");
                    int grade = dlInt(q, "grade", 0);
                    if (grade <= 0) grade = gradeFromLabel(gradeLabel);
                    if (grade <= 0) grade = defGrade;
                    if (gradeLabel.isEmpty()) gradeLabel = grade + "年级";
                    String term = dlStr(q, "term", TERM_ALL);
                    String topicGroup = dlStr2(q, "topicGroup", "topic_group", "");
                    // v47：非三科题没写大类时用学科名兜底，家长设置「知识点范围 → 其它」
                    // 才会有可选项（物理 / 历史 / 科学 …），否则永远显示「暂无知识点大类」。
                    if (topicGroup.isEmpty() && !isCoreSubject(subject)) topicGroup = subject;
                    String chapter = dlStr(q, "chapter", "");
                    String subjectGroup = dlStr2(q, "subjectGroup", "subject_group", "");

                    ContentValues cv = new ContentValues();
                    cv.put("edition", edition);
                    cv.put("grade", grade);
                    cv.put("grade_label", gradeLabel);
                    cv.put("term", term);
                    cv.put("subject", subject);
                    cv.put("subject_group", subjectGroup);
                    cv.put("chapter", chapter);
                    cv.put("topic_group", topicGroup);
                    cv.put("topic", topic);
                    cv.put("text", text);
                    cv.put("opt_a", o[0]); cv.put("opt_b", o[1]);
                    cv.put("opt_c", o[2]); cv.put("opt_d", o[3]);
                    cv.put("answer_index", ai);
                    cv.put("explain", explain);
                    cv.put("source", src);
                    cv.put("qhash", dlHash(text, o));
                    // UNIQUE(qhash)：与内置库或别的下载码重复时忽略，不虚报成功数。
                    long id = d.insertWithOnConflict(
                            "questions", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                    if (id < 0) { skip++; }
                    else {
                        ins++;
                        if (isCoreSubject(subject)) r.coreInserted++;
                        else r.otherInserted++;
                    }
                }
                d.setTransactionSuccessful();
                r.inserted = ins;
                r.skipped = skip;
            } finally {
                d.endTransaction();
            }
            // 导入后把旧只读单例的底层连接一并关掉，再清空引用。
            // 旧连接（守护出题引擎仍持有）若不关，下次 open() 会再开一个连接，
            // 两个句柄同时指向同一 db 文件，在刚写入过的库上易触发 native SQLite
            // 冲突——表现为「强关 App 再打开进程起不来 / failed to attach / 卡死」，
            // 且不留 Java ANR 栈。这正是「只有下载导入后才卡死、原始库不卡」的根因。
            // 关闭后由 SettingsActivity 导入成功回调里的 open() 建新实例，再经
            // sendReload→reload→engine.setDb(新实例) 立即换上；旧引擎引用只在极短
            // 窗口内存在，且抽题异常已被上层捕获，风险可控。
            if (instance != null) {
                try { instance.db.close(); } catch (Exception ignored) { }
            }
            instance = null;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + "："
                    + (e.getMessage() == null ? "" : e.getMessage());
        } finally {
            if (d != null) { try { d.close(); } catch (Exception ignored) { } }
        }
        return r;
    }

    /** JSON 值转字符串；对象 / 数组 / null 一律视为缺省。 */
    private static String dlText(Object v) {
        if (v == null || v instanceof Map || v instanceof List) return "";
        return v instanceof String ? (String) v : v.toString();
    }

    private static String dlStr(Map<String, Object> m, String key, String def) {
        String s = dlText(m.get(key)).trim();
        return s.isEmpty() ? def : s;
    }

    private static int dlInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt(((String) v).trim()); }
            catch (Exception ignored) { }
        }
        return def;
    }

    /** 同一字段的 camelCase / snake_case 两种写法都接受。 */
    private static String dlStr2(Map<String, Object> m, String camel,
                                 String snake, String def) {
        String s = dlStr(m, camel, "");
        return s.isEmpty() ? dlStr(m, snake, def) : s;
    }

    /** "3年级" / "三年级上" 之类取第一个数字段；qbank-spec 只有 grade_label。 */
    private static int gradeFromLabel(String label) {
        if (label == null) return 0;
        StringBuilder n = new StringBuilder();
        for (int i = 0; i < label.length(); i++) {
            char ch = label.charAt(i);
            if (ch >= '0' && ch <= '9') n.append(ch);
            else if (n.length() > 0) break;
        }
        if (n.length() == 0) return 0;
        try { return Integer.parseInt(n.toString()); } catch (Exception e) { return 0; }
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /** 与 qbank-spec/check_bank.py 完全一致的去重键：SHA1(去空白题干 + 4 个去空白选项)。 */
    private static String dlHash(String text, String[] o) {
        try {
            StringBuilder sb = new StringBuilder(norm(text));
            for (String x : o) sb.append('|').append(norm(x));
            byte[] b = MessageDigest.getInstance("SHA-1")
                    .digest(sb.toString().getBytes("UTF-8"));
            StringBuilder h = new StringBuilder();
            for (byte x : b) h.append(String.format("%02x", x & 0xFF));
            return h.toString();
        } catch (Exception e) {
            return null;   // NULL 不参与 UNIQUE 约束，最坏情况只是少一次去重
        }
    }

    /** 该年级的规范标签：优先「N年级」/「中文数字+年级」，否则取该年级第一条标签。 */
    private static String canonicalGradeLabel(SQLiteDatabase d, int grade) {
        String[] ls = strings(d, "SELECT DISTINCT grade_label FROM questions "
                + "WHERE grade=? ORDER BY length(grade_label), grade_label",
                new String[]{Integer.toString(grade)});
        for (String s : ls) {
            if (s != null && s.matches("^[0-9]+年级$")) return s;
        }
        for (String s : ls) {
            if (s != null && s.matches("^[一二三四五六七八九]年级$")) return s;
        }
        return ls.length > 0 ? ls[0] : (grade + "年级");
    }

    private static String firstEdition(SQLiteDatabase d) {
        try (Cursor c = d.rawQuery(
                "SELECT edition FROM questions WHERE edition<>'' ORDER BY edition LIMIT 1", null)) {
            return c.moveToFirst() ? c.getString(0) : "";
        } catch (Exception e) { return ""; }
    }

    private static int firstGrade(SQLiteDatabase d) {
        try (Cursor c = d.rawQuery("SELECT grade FROM questions ORDER BY grade LIMIT 1", null)) {
            return c.moveToFirst() ? c.getInt(0) : 1;
        } catch (Exception e) { return 1; }
    }

    private static String gradeLabelOf(SQLiteDatabase d, int grade) {
        try (Cursor c = d.rawQuery(
                "SELECT grade_label FROM questions WHERE grade=? LIMIT 1",
                new String[]{Integer.toString(grade)})) {
            return c.moveToFirst() ? c.getString(0) : "";
        } catch (Exception e) { return ""; }
    }
}
