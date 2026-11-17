package com.xsdanser.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import java.util.HashMap;

/**
 * v31 出题界面中文注音：在每个汉字正上方标出带声调的拼音（ruby 式），
 * 让低年级还不认字的小朋友也能自己读题。
 *
 * <p>读音数据由 {@link PinyinData} 提供：单字取全库出现频次最高的词组感知读音，
 * 多音字另有一张 2~4 字词组覆盖表，运行时最长匹配优先，能正确处理
 *「长大 / 长短」「音乐 / 快乐」「一个」这类常见多音词。
 *
 * <p>实现要点：本工程答题浮层 {@code OverlayQuizView} 来自历史 smali，不能重编译，
 * 所以注音由 {@code AnswerShuffle.onBind()} 在 bind 完成后反射替换题干与四个选项的
 * CharSequence。这里只负责生成带 {@link RubySpan} 的 SpannableStringBuilder，
 * 不碰任何 View，也不改变原题面字符串（判题、错题本、去重键全部不受影响）。
 */
public final class Pinyin {

    /** 家长设置里的注音开关（直接落 SharedPreferences，不依赖历史 smali 的 SettingsStore）。 */
    public static final String KEY = "pinyinOn";
    private static final String PREF = "tvbank";
    /**
     * 拼音不写死颜色：取当前 TextView 的文字色，降到 ~78% 不透明度。
     * 答题浮层由历史 smali 绘制（深色卡片 + 白字，选中项为亮青绿底），
     * 固定色必然在「未选中 / 已选中」两种底上顾此失彼；跟随文字色则两种
     * 状态、深浅主题都自动保持可读，且比汉字本身略淡，视觉层级清楚。
     */

    private static HashMap<Character, String> sChars;
    private static HashMap<String, String[]> sWords;

    private Pinyin() { }

    public static boolean on(Context c) {
        if (c == null) return false;
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY, false);
    }

    public static void setOn(Context c, boolean v) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, v).commit();
    }

    public static String label(Context c) {
        return on(c) ? "开启 · 题干与选项标拼音" : "关闭 · 只显示汉字";
    }

    private static synchronized void ensure() {
        if (sChars != null) return;
        HashMap<Character, String> cm = new HashMap<Character, String>();
        HashMap<String, String[]> wm = new HashMap<String, String[]>();
        try {
            for (String chunk : PinyinData.CHARS) {
                for (String item : chunk.split("\\|")) {
                    int p = item.indexOf('=');
                    if (p == 1 && item.length() > 2) cm.put(item.charAt(0), item.substring(2));
                }
            }
            for (String chunk : PinyinData.WORDS) {
                for (String item : chunk.split("\\|")) {
                    int p = item.indexOf('=');
                    if (p < 2 || p >= item.length() - 1) continue;
                    String word = item.substring(0, p);
                    String[] pys = item.substring(p + 1).split(" ");
                    if (pys.length == word.length()) wm.put(word, pys);
                }
            }
        } catch (Throwable ignored) { }
        sChars = cm;
        sWords = wm;
    }

    private static boolean isHan(char ch) {
        return (ch >= '\u4E00' && ch <= '\u9FFF') || (ch >= '\u3400' && ch <= '\u4DBF');
    }

    /** 生成 ruby 注音文本；没有汉字或字典缺席时原样返回，保证任何情况都不影响判题。 */
    public static CharSequence ruby(String src) {
        if (src == null || src.length() == 0) return src == null ? "" : src;
        boolean hasHan = false;
        for (int i = 0; i < src.length(); i++) {
            if (isHan(src.charAt(i))) { hasHan = true; break; }
        }
        if (!hasHan) return src;
        ensure();
        if (sChars == null || sChars.isEmpty()) return src;

        SpannableStringBuilder b = new SpannableStringBuilder();
        int i = 0;
        while (i < src.length()) {
            char ch = src.charAt(i);
            if (!isHan(ch)) { b.append(ch); i++; continue; }

            // 词组最长匹配优先，修正多音字；命中后逐字加注音。
            boolean matched = false;
            int maxLen = Math.min(4, src.length() - i);
            for (int len = maxLen; len >= 2; len--) {
                boolean allHan = true;
                for (int k = 1; k < len; k++) {
                    if (!isHan(src.charAt(i + k))) { allHan = false; break; }
                }
                if (!allHan) continue;
                String word = src.substring(i, i + len);
                String[] pys = sWords.get(word);
                if (pys == null || pys.length != len) continue;
                for (int k = 0; k < len; k++) {
                    appendRuby(b, src.charAt(i + k), pys[k]);
                }
                i += len;
                matched = true;
                break;
            }
            if (matched) continue;

            String py = sChars.get(Character.valueOf(ch));
            if (py == null || py.length() == 0) { b.append(ch); i++; continue; }
            appendRuby(b, ch, py);
            i++;
        }
        return b;
    }

    private static void appendRuby(SpannableStringBuilder b, char ch, String py) {
        int start = b.length();
        b.append(ch);
        b.setSpan(new RubySpan(String.valueOf(ch), py), start, start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * 单个汉字的 ruby span：在字上方画拼音，字本身仍用 TextView 原有画笔绘制。
     *
     * <p>v66 关键约束：<b>绝不改写 FontMetricsInt</b>。注音开启时的行框必须与关闭时
     * 逐像素一致，拼音只是额外画在「字格上方的行间空隙」里。原因：
     * <ul>
     *   <li>历史浮层的选项按钮（gravity = CENTER_VERTICAL | LEFT）与题干 TextView
     *       都是固定高度（gridPx / maxHeight）。一旦 getSize 抬高 ascent，行框就比容器高，
     *       基线被顶到容器之外——汉字画在框外看不见，而拼音在基线上方仍留在框内，
     *       于是出现「汉字整块消失、只剩一行拼音」「题干只露上半截」。</li>
     *   <li>抬高 ascent 还会把行距撑大，视觉上表现为拼音与汉字「相隔很大」、且与字
     *       左右不居中。</li>
     * </ul>
     * 行框不动后，唯一要求是容器顶部留够拼音高度的内边距（见 QuizLayout / AnswerShuffle），
     * 于是间距恒定（字顶上方 1px）、不重叠、不裁切，且与密度/分辨率/字号/布局类型无关。
     */
    public static final class RubySpan extends ReplacementSpan {
        private final String ch;
        private final String py;
        private Paint pyPaint;
        private float pySize = -1f;

        RubySpan(String ch, String py) { this.ch = ch; this.py = py; }

        private Paint pyPaint(Paint base) {
            float size = base.getTextSize() * 0.48f;
            if (pyPaint == null || pySize != size) {
                pyPaint = new Paint(base);
                pyPaint.setTextSize(size);
                pyPaint.setFakeBoldText(true);
                pySize = size;
            }
            // 每次绘制都跟随当前文字色（选中/未选中按钮颜色不同）。
            // alpha 必须跟随画笔实际 alpha，不能写死 0xC8——答对后会做淡出动画，
            // 写死 alpha 会让「汉字淡没了、拼音还亮着」，看起来拼音悬在空中。
            int c = base.getColor();
            int a = (c >>> 24) & 0xFF;
            int pyA = (int) (a * 0.78f);
            pyPaint.setColor((c & 0x00FFFFFF) | (pyA << 24));
            return pyPaint;
        }

        private int width(Paint base) {
            Paint p = pyPaint(base);
            float w = Math.max(base.measureText(ch), p.measureText(py));
            return (int) Math.ceil(w) + 2;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            // v66: 完全不碰 fm —— 行框与注音关闭时一致，基线位置不会被推移。
            return width(paint);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            Paint p = pyPaint(paint);
            float w = width(paint) - 2f;
            float cw = paint.measureText(ch);
            float pw = p.measureText(py);
            // 拼音锚定「汉字自身字格顶」= 基线 y + 该画笔的 ascent。与行顶 top、布局类型
            //（StaticLayout / BoringLayout）、容器高度都无关，故间距恒定：拼音底边压在
            // 字顶上方 1px —— 不压字、不拉开、不被顶边裁。
            Paint.FontMetricsInt fmi = paint.getFontMetricsInt();
            Paint.FontMetrics pfm = p.getFontMetrics();
            float charTop = y + fmi.ascent;
            float pyBaseline = charTop - 1f - pfm.descent;
            canvas.drawText(py, x + (w - pw) / 2f, pyBaseline, p);
            canvas.drawText(ch, x + (w - cw) / 2f, y, paint);
        }
    }
}
