package com.xsdanser.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** v10 界面公共样式：深色卡片 + 青绿强调，不依赖旧 Ui 常量，便于独立编译。 */
public final class V10Ui {
    private V10Ui() {}

    public static final int BG         = 0xFF0A101C;
    public static final int SURFACE    = 0xFF141F33;
    public static final int SURFACE2   = 0xFF1B2942;
    public static final int STROKE     = 0xFF26374F;
    public static final int TEXT       = 0xFFF1F5F9;
    public static final int MUTED      = 0xFF8FA0BC;
    // v22 配色规范：常态按钮/链接一律不用绿色；只有「被选择 / 获得焦点」才用绿色。
    public static final int SELECT       = 0xFF14B8A6;   // 选中态主绿
    public static final int SELECT_LIGHT = 0xFF2DD4BF;   // 选中态描边绿
    public static final int SELECT_DARK  = 0xFF0D9488;   // 选中态底色绿
    public static final int ACCENT       = 0xFF3B82F6;   // 常态强调（蓝）
    public static final int ACCENT_LIGHT = 0xFF93C5FD;   // 常态强调浅蓝
    public static final int ACCENT_DEEP  = 0xFF1D4ED8;   // 常态强调深蓝
    public static final int BLUE       = 0xFF2563EB;
    public static final int AMBER      = 0xFFD97706;
    public static final int RED        = 0xFFEF4444;
    public static final int PURPLE     = 0xFF7C3AED;
    public static final int ORANGE     = 0xFFEA580C;
    public static final int SLATE      = 0xFF334155;
    // v28 快捷操作：五个按钮各一种常态色，全部非绿、白字可读；聚焦仍统一变绿。
    public static final int BTN_RED    = 0xFFDC2626;
    public static final int BTN_BLUE   = 0xFF2563EB;
    public static final int BTN_AMBER  = 0xFFB45309;
    public static final int BTN_PURPLE = 0xFF6D28D9;
    // v32：「关于」由灰蓝改为青蓝，比原色更清楚，且不与聚焦态绿撞色。
    public static final int BTN_SLATE  = 0xFF0E7490;
    // 旧名保留给历史代码兼容，语义上等同「选中态」，不得用于常态按钮。
    public static final int TEAL       = SELECT;
    public static final int TEAL_LIGHT = SELECT_LIGHT;
    public static final int TEAL_DARK  = SELECT_DARK;

    /**
     * v49：Android 15（API 35）边到边适配。
     * targetSdk 升到 35 后系统默认边到边，状态栏/导航栏会覆盖在 Activity 内容之上；
     * 这里把系统栏 inset 转成根视图 padding，使交互内容不被遮挡。
     * 编译期 stub 不含 WindowInsets / OnApplyWindowInsetsListener，故用反射调用；
     * TV 上系统栏 inset 为 0，等同无操作；旧平台（<20）直接跳过。
     * 注：核心答题 UI 是 TYPE_APPLICATION_OVERLAY 悬浮窗，不在此列。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void fitEdgeToEdge(Activity a) {
        if (a == null || Build.VERSION.SDK_INT < 20) return;
        View root = a.findViewById(android.R.id.content);
        if (root == null) return;
        try {
            Class<?> insetsCls = Class.forName("android.view.WindowInsets");
            Class<?> listenerIface = Class.forName("android.view.View$OnApplyWindowInsetsListener");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    V10Ui.class.getClassLoader(), new Class[]{listenerIface},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                            String name = method.getName();
                            if ("onApplyWindowInsets".equals(name)) {
                                View v = (View) args[0];
                                Object insets = args[1];
                                try {
                                    int l = (Integer) insetsCls.getMethod("getSystemWindowInsetLeft").invoke(insets);
                                    int t = (Integer) insetsCls.getMethod("getSystemWindowInsetTop").invoke(insets);
                                    int r = (Integer) insetsCls.getMethod("getSystemWindowInsetRight").invoke(insets);
                                    int b = (Integer) insetsCls.getMethod("getSystemWindowInsetBottom").invoke(insets);
                                    v.setPadding(l, t, r, b);
                                } catch (Exception ignore) { }
                                return insets;
                            }
                            if ("hashCode".equals(name)) return 0;
                            if ("equals".equals(name)) return proxy == (args != null && args.length > 0 ? args[0] : null);
                            if ("toString".equals(name)) return "EdgeToEdgeInsetsListener";
                            return null;
                        }
                    });
            View.class.getMethod("setOnApplyWindowInsetsListener", listenerIface).invoke(root, listener);
            View.class.getMethod("requestApplyInsets").invoke(root);
        } catch (Exception ignore) { /* 旧平台或 stub 缺失：忽略，不影响功能 */ }
    }

    public static int dp(Context c, float v) {
        // v51：懒适配。任何页面第一次取尺寸时就把 density 基准化，
        // 之后 dp 与下面的 sp 字号都按同一比例，720p~8K 视觉一致。
        ScreenFit.apply(c);
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable round(int color, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable outlined(int color, int radiusDp, Context c,
                                            int strokeDp, int strokeColor) {
        GradientDrawable g = round(color, radiusDp, c);
        g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    public static TextView tv(Context c, String text, int sp, int color, int gravity) {
        ScreenFit.apply(c);   // v51：sp 换算依赖 scaledDensity，先基准化
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(gravity);
        return t;
    }

    public static Button button(Context c, String text, int bg, int fg, int sp) {
        ScreenFit.apply(c);   // v51：按钮字号与 padding 同步缩放
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setSingleLine(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        b.setBackground(round(bg, 12, c));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        return b;
    }

    /**
     * 遥控器焦点 / 被选择：统一绿色高亮（绿底 + 亮绿描边），失焦还原传入的常态底色。
     * normalBg 必须来自非绿色系，保证「只有被选择的按钮才是绿色」。
     */
    public static void focus(final Button b, final Context c,
                             final int normalBg, final int normalFg) {
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (has) {
                    b.setBackground(outlined(SELECT_DARK, 12, c, 2, SELECT_LIGHT));
                } else {
                    b.setBackground(round(normalBg, 12, c));
                }
                b.setTextColor(has ? TEXT : normalFg);
            }
        });
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams fill(int weight) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    public static LinearLayout.LayoutParams fixed(Context c, int dp) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, dp));
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 分组卡片：标题 + 内容容器，统一圆角、描边与内边距。 */
    public static LinearLayout card(Context c, String title) {
        LinearLayout card = vbox(c);
        card.setBackground(outlined(SURFACE, 18, c, 1, STROKE));
        card.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 16));
        if (title != null && title.length() > 0) {
            TextView t = tv(c, title, 17, ACCENT_LIGHT, Gravity.LEFT);
            t.setPadding(0, 0, 0, dp(c, 8));
            card.addView(t, matchWrap());
        }
        return card;
    }

    public static void attachCard(LinearLayout parent, LinearLayout card, Context c) {
        LinearLayout.LayoutParams p = matchWrap();
        p.setMargins(0, 0, 0, dp(c, 12));
        parent.addView(card, p);
    }

    public static TextView note(Context c, String text) {
        TextView t = tv(c, text, 12, MUTED, Gravity.LEFT);
        t.setPadding(0, dp(c, 6), 0, 0);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private static int idSeq = 1000;
    public static int ensureId(View v) {
        if (v.getId() == View.NO_ID) v.setId(++idSeq);
        return v.getId();
    }
}
