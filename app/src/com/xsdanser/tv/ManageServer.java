package com.xsdanser.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * v31「家长扫码管理」：应用内起一个局域网 HTTP 控制台，家长用手机扫二维码
 * 打开网页即可调整参数、立即出题、锁屏/解锁、暂停/恢复播放、重置观看时间。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>零云端、零账号：手机和电视在同一 Wi-Fi 即可；地址里带一次性随机令牌，
 *       页面与所有接口都校验该令牌，二维码本身只在通过家长密码门禁后显示。</li>
 *   <li>不新增 Activity / 权限：INTERNET 已在 Manifest 声明，界面只是
 *       SettingsActivity 里的一个全屏 View，避免改动二进制 AndroidManifest.xml。</li>
 *   <li>写参数直接落 SharedPreferences（与家长设置同一份），改完通知守护服务
 *       reload，保证和电视端界面显示一致。</li>
 *   <li>二维码用自研 {@link QrEncoder} 现场编码，IP / 端口 / 令牌变化无需预置图片。</li>
 * </ul>
 */
public final class ManageServer {

    private static final int BASE_PORT = 8088;
    private static final int PORT_TRIES = 8;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile ServerSocket sServer;
    private static volatile String sToken = "";
    private static volatile String sIp = "127.0.0.1";
    private static volatile int sPort = BASE_PORT;
    private static volatile String sUrl = "";
    private static volatile Context sApp;

    private ManageServer() { }

    // ---------------------------------------------------------------- 生命周期

    public static synchronized String start(Context c) {
        sApp = c.getApplicationContext();
        if (sServer != null && !sServer.isClosed()) return sUrl;
        sIp = lanIp();
        sToken = randomToken();
        ServerSocket ss = null;
        for (int i = 0; i < PORT_TRIES && ss == null; i++) {
            try {
                sPort = BASE_PORT + i;
                ss = new ServerSocket(sPort, 24);
            } catch (Exception ignored) { }
        }
        if (ss == null) { sUrl = ""; return ""; }
        sServer = ss;
        sUrl = "http://" + sIp + ":" + sPort + "/?k=" + sToken;
        final ServerSocket server = ss;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(server); }
        }, "xsd-manage-http");
        t.setDaemon(true);
        t.start();
        return sUrl;
    }

    public static String url() { return sUrl; }
    public static String ip() { return sIp; }
    public static int port() { return sPort; }
    public static boolean isRunning() { return sServer != null && !sServer.isClosed(); }

    public static Bitmap qrBitmap(String url, int targetPx) {
        boolean[][] m = QrEncoder.encode(url);
        int n = m.length;
        int quiet = 4;
        int total = n + quiet * 2;
        int scale = Math.max(2, targetPx / total);
        int side = total * scale;
        Bitmap bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint white = new Paint();
        white.setColor(0xFFFFFFFF);
        cv.drawRect(0, 0, side, side, white);
        Paint dark = new Paint();
        dark.setColor(0xFF0A101C);
        dark.setStyle(Paint.Style.FILL);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!m[y][x]) continue;
                float l = (x + quiet) * scale, t = (y + quiet) * scale;
                cv.drawRect(l, t, l + scale, t + scale, dark);
            }
        }
        return bmp;
    }

    private static String lanIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return "127.0.0.1";
    }

    private static String randomToken() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    // ---------------------------------------------------------------- HTTP 循环

    private static void loop(ServerSocket server) {
        while (!server.isClosed()) {
            Socket s = null;
            try {
                s = server.accept();
                s.setSoTimeout(15000);
                handle(s);
            } catch (Throwable ignored) {
            } finally {
                if (s != null) { try { s.close(); } catch (Exception ignored) { } }
            }
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        return b.toString("UTF-8");
    }

    private static void handle(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        String first = readLine(in);
        if (first == null || first.length() == 0) return;
        String[] parts = first.split(" ");
        if (parts.length < 2) return;
        String method = parts[0].toUpperCase();
        String full = parts[1];
        int q = full.indexOf('?');
        String path = q >= 0 ? full.substring(0, q) : full;
        String query = q >= 0 ? full.substring(q + 1) : "";

        int contentLen = 0;
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try { contentLen = Integer.parseInt(line.substring(colon + 1).trim()); }
                catch (Exception ignored) { }
            }
        }
        byte[] body = new byte[Math.max(0, Math.min(contentLen, 8192))];
        int off = 0;
        while (off < body.length) {
            int n = in.read(body, off, body.length - off);
            if (n <= 0) break;
            off += n;
        }
        Map<String, String> args = parse(query);
        if ("POST".equals(method)) args.putAll(parse(new String(body, 0, off, "UTF-8")));

        String token = args.get("k");
        boolean authed = token != null && token.length() > 0 && token.equals(sToken);
        String content;
        String type;
        int code = 200;
        if (!authed) {
            code = 403;
            type = "text/plain; charset=utf-8";
            content = "管理令牌无效，请在电视上重新打开扫码管理";
        } else if ("/api/state".equals(path)) {
            type = "application/json; charset=utf-8";
            content = stateJson();
        } else if ("/api/set".equals(path) && "POST".equals(method)) {
            type = "application/json; charset=utf-8";
            content = applySet(args) ? "{\"ok\":true}" : "{\"ok\":false}";
        } else if ("/api/action".equals(path) && "POST".equals(method)) {
            type = "application/json; charset=utf-8";
            content = doAction(args.get("act")) ? "{\"ok\":true}" : "{\"ok\":false}";
        } else if ("/".equals(path) || "/index.html".equals(path)) {
            type = "text/html; charset=utf-8";
            content = ManagePage.html(sToken);
        } else {
            code = 404;
            type = "text/plain; charset=utf-8";
            content = "not found";
        }
        byte[] bytes = content.getBytes("UTF-8");
        String status = code == 200 ? "OK" : (code == 403 ? "Forbidden" : "Not Found");
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes("US-ASCII"));
        out.write(bytes);
        out.flush();
    }

    private static Map<String, String> parse(String raw) {
        HashMap<String, String> m = new HashMap<String, String>();
        if (raw == null || raw.length() == 0) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) { }
        }
        return m;
    }

    // ---------------------------------------------------------------- 状态 / 参数

    private static SharedPreferences prefs() {
        return sApp.getSharedPreferences(SettingsStore.PREF, Context.MODE_PRIVATE);
    }

    private static int asInt(Map<String, String> m, String k, int def) {
        try { return Integer.parseInt(m.get(k)); } catch (Exception e) { return def; }
    }

    private static boolean in(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String intervalLabel(int sec) {
        for (int i = 0; i < SettingsStore.INTERVALS.length; i++) {
            if (SettingsStore.INTERVALS[i] == sec) return SettingsStore.INTERVAL_LABELS[i];
        }
        return sec + " 秒";
    }

    private static String stateJson() {
        SharedPreferences p = prefs();
        int mode = QuizMode.mode(sApp);
        int interval = p.getInt("interval", 600);
        int perRound = Math.max(1, Math.min(30, p.getInt("quizPerRound", 1)));
        int reward = QuizMode.rewardMinutes(sApp);
        int start = QuizMode.startMinutes(sApp);
        int grade = p.getInt(QuizDb.K_GRADE, 0);
        if (grade < 1) grade = p.getInt("grade", 1);
        String term = p.getString(QuizDb.K_TERM, "");
        if (term == null) term = "";
        long bal = QuizMode.balanceMs(sApp);
        String balance = bal < 0L ? "守护模式不计时"
                : ((bal / 60000L) + " 分 " + ((bal / 1000L) % 60L) + " 秒");
        int bank = 0;
        try { if (QuizDb.peek() != null) bank = QuizDb.peek().count(); } catch (Throwable ignored) { }
        return "{\"ok\":true"
                + ",\"mode\":" + mode
                + ",\"modeName\":\"" + esc(QuizMode.MODE_NAMES[mode]) + "\""
                + ",\"interval\":" + interval
                + ",\"intervalLabel\":\"" + esc(intervalLabel(interval)) + "\""
                + ",\"perRound\":" + perRound
                + ",\"reward\":" + reward
                + ",\"start\":" + start
                + ",\"pinyin\":" + (p.getBoolean(Pinyin.KEY, false) ? 1 : 0)
                + ",\"grade\":" + grade
                + ",\"term\":\"" + esc(term.isEmpty() ? "全学年" : term) + "\""
                + ",\"balance\":\"" + esc(balance) + "\""
                + ",\"locked\":" + (ManageLock.isLocked() ? 1 : 0)
                + ",\"lock\":" + VoiceCoach.lockSec(sApp)
                + ",\"bank\":" + bank + "}";
    }

    private static boolean applySet(Map<String, String> m) {
        if (sApp == null) return false;
        SharedPreferences p = prefs();
        try {
            int wantMode = asInt(m, "mode", QuizMode.mode(sApp));
            if (wantMode != QuizMode.mode(sApp)) {
                QuizMode.setMode(sApp, wantMode == QuizMode.MODE_FREE ? 1 : 0);
            }
            SharedPreferences.Editor e = p.edit();
            int iv = asInt(m, "interval", -1);
            if (in(SettingsStore.INTERVALS, iv)) e.putInt("interval", iv);
            int pr = asInt(m, "perRound", -1);
            if (pr >= 1 && pr <= 30) e.putInt("quizPerRound", pr);
            int rw = asInt(m, "reward", -1);
            if (in(QuizMode.REWARD_MINUTES, rw)) e.putInt("quiz_reward_min", rw);
            int st = asInt(m, "start", -1);
            if (in(QuizMode.START_MINUTES, st)) e.putInt("quiz_start_min", st);
            int gr = asInt(m, "grade", 0);
            if (gr >= 1 && gr <= 9) {
                e.putInt(QuizDb.K_GRADE, gr).putInt("grade", gr);
            }
            if (m.containsKey("term")) {
                String t = m.get("term");
                e.putString(QuizDb.K_TERM, t == null ? "" : t);
            }
            if (m.containsKey("pinyin")) {
                e.putBoolean(Pinyin.KEY, "1".equals(m.get("pinyin")));
            }
            if (m.containsKey("lock")) {
                int lk = asInt(m, "lock", -1);
                if (in(VoiceCoach.LOCK_STEPS, lk)) VoiceCoach.setLockSec(sApp, lk);
            }
            e.commit();
            QuizMode.sync(sApp);
            reloadGuard();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void reloadGuard() {
        try {
            if (!QuizOverlayService.isRunning() || sApp == null) return;
            Intent i = new Intent(sApp, QuizOverlayService.class);
            i.setAction(QuizOverlayService.ACTION_RELOAD);
            sApp.startService(i);
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- 快捷控制

    private static boolean doAction(final String act) {
        if (sApp == null || act == null) return false;
        MAIN.post(new Runnable() {
            @Override public void run() {
                try {
                    if ("lock".equals(act)) {
                        ManageLock.show(sApp, "家长已在手机上锁屏 · 休息一下吧");
                    } else if ("unlock".equals(act)) {
                        ManageLock.hide();
                    } else if ("quiz".equals(act)) {
                        QuizMode.requestManualQuiz(sApp);
                    } else if ("reset".equals(act)) {
                        QuizMode.resetBalance(sApp);
                    } else if ("pause".equals(act)) {
                        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
                    } else if ("play".equals(act)) {
                        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
                    }
                } catch (Throwable ignored) { }
            }
        });
        return true;
    }

    /** AudioManager.dispatchMediaKeyEvent 不在老 android-stub 里，与 QuizMode 一致走反射。 */
    private static void dispatchMediaKey(int code) {
        try {
            AudioManager am = (AudioManager) sApp.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            java.lang.reflect.Method m = AudioManager.class.getMethod(
                    "dispatchMediaKeyEvent", KeyEvent.class);
            m.invoke(am, new KeyEvent(KeyEvent.ACTION_DOWN, code));
            m.invoke(am, new KeyEvent(KeyEvent.ACTION_UP, code));
        } catch (Throwable ignored) { }
    }
}
