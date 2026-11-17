package com.xsdanser.tv;

/**
 * 极简 QR Code 编码器（Model 2 / 字节模式 / 纠错等级 M / mask 0 / 版本 1~7）。
 *
 * <p>为 APK 内置「家长扫码管理」而写：URL 是纯 ASCII 且很短（约 45 字节），
 * 版本 1~7 足够，全部版本的数据块都是等长的，因此不需要处理 group2 分块。
 * 纯 Java 实现、零第三方依赖，可用 segno 等标准库逐模块比对校验。
 *
 * <p>mask 固定为 0：QR 规范允许任意 mask，纠错等级 M 足以保证手机相机识别；
 * 固定 mask 省掉惩罚分计算，也让矩阵可被标准库确定性比对。
 */
public final class QrEncoder {

    /** 版本 1~7，等级 M：{数据码字数, 每块纠错码字数, 块数}。 */
    private static final int[][] TABLE = {
            {16, 10, 1}, {28, 16, 1}, {44, 26, 1}, {64, 18, 2},
            {86, 24, 2}, {108, 16, 4}, {124, 18, 4},
    };
    /** 对齐图案中心坐标（版本 1~7）。 */
    private static final int[][] ALIGN = {
            {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}, {6, 22, 38},
    };

    private final int version;
    private final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    private QrEncoder(int ver) {
        version = ver;
        size = ver * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
    }

    /** @return size×size 矩阵，true = 深色模块。 */
    public static boolean[][] encode(String text) {
        byte[] payload;
        try { payload = text.getBytes("UTF-8"); }
        catch (Exception e) { payload = text.getBytes(); }

        int ver = 0;
        for (int v = 1; v <= 7; v++) {
            int capBits = TABLE[v - 1][0] * 8;
            if (4 + 8 + 8L * payload.length <= capBits) { ver = v; break; }
        }
        if (ver == 0) throw new IllegalArgumentException("QR 内容过长（版本 7 装不下）");

        int dataCw = TABLE[ver - 1][0];
        int ecCw = TABLE[ver - 1][1];
        int blocks = TABLE[ver - 1][2];
        byte[] data = padCodewords(payload, dataCw);

        // 分块 + Reed-Solomon 纠错 + 交织
        int blockLen = dataCw / blocks;
        byte[][] d = new byte[blocks][blockLen];
        byte[][] e = new byte[blocks][ecCw];
        byte[] divisor = rsDivisor(ecCw);
        for (int b = 0; b < blocks; b++) {
            System.arraycopy(data, b * blockLen, d[b], 0, blockLen);
            e[b] = rsRemainder(d[b], divisor);
        }
        byte[] all = new byte[dataCw + ecCw * blocks];
        int k = 0;
        for (int i = 0; i < blockLen; i++)
            for (int b = 0; b < blocks; b++) all[k++] = d[b][i];
        for (int i = 0; i < ecCw; i++)
            for (int b = 0; b < blocks; b++) all[k++] = e[b][i];

        QrEncoder q = new QrEncoder(ver);
        q.drawFunctionPatterns();
        q.drawCodewords(all);
        q.applyMask();
        return q.modules;
    }

    // ---------- 位流 ----------

    private static final class Bits {
        private final byte[] out;
        private int n;
        Bits(int bytes) { out = new byte[bytes]; }
        void append(int val, int len) {
            for (int i = len - 1; i >= 0; i--) {
                int bit = (val >>> i) & 1;
                if (bit != 0) out[n >>> 3] |= (byte) (1 << (7 - (n & 7)));
                n++;
            }
        }
        int size() { return n; }
        byte[] bytes() { return out; }
    }

    private static byte[] padCodewords(byte[] payload, int count) {
        Bits b = new Bits(count);
        b.append(4, 4);                    // 字节模式
        b.append(payload.length, 8);      // 字符数（版本 1~9 用 8 位）
        for (byte x : payload) b.append(x & 0xff, 8);
        int cap = count * 8;
        b.append(0, Math.min(4, cap - b.size()));
        while (b.size() % 8 != 0) b.append(0, 1);
        byte[] out = b.bytes();
        for (int i = 0, pad = 0; (b.size() / 8) + i < count; i++, pad++) {
            out[b.size() / 8 + i] = (byte) (pad % 2 == 0 ? 0xEC : 0x11);
        }
        return out;
    }

    // ---------- GF(256) Reed-Solomon ----------

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) mul(result[j] & 0xFF, root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = mul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] rem = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ rem[0]) & 0xFF;
            System.arraycopy(rem, 1, rem, 0, rem.length - 1);
            rem[rem.length - 1] = 0;
            for (int i = 0; i < rem.length; i++) {
                rem[i] ^= (byte) mul(divisor[i] & 0xFF, factor);
            }
        }
        return rem;
    }

    // ---------- 功能图案 ----------

    private void setFunction(int x, int y, boolean dark) {
        if (x < 0 || y < 0 || x >= size || y >= size) return;
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawFinder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFunction(cx + dx, cy + dy, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFunction(cx + dx, cy + dy, dist != 1);
            }
        }
    }

    private void drawFormatBits() {
        // 等级 M 的 2 位格式值为 00；mask = 0
        int data = 0;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true);      // 固定黑模块
    }

    private void drawVersionBits() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean v = bit(bits, i);
            int a = size - 11 + i % 3;
            int b = i / 3;
            setFunction(a, b, v);
            setFunction(b, a, v);
        }
    }

    private static boolean bit(int v, int i) { return ((v >>> i) & 1) != 0; }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);

        int[] pos = ALIGN[version - 1];
        int n = pos.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1)
                        || (i == n - 1 && j == 0)) continue;
                drawAlignment(pos[j], pos[i]);
            }
        }
        drawFormatBits();
        drawVersionBits();
    }

    // ---------- 数据放置 / 掩码 ----------

    private void drawCodewords(byte[] all) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < all.length * 8) {
                        modules[y][x] = bit(all[i >>> 3] & 0xFF, 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask() {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!isFunction[y][x] && (x + y) % 2 == 0) modules[y][x] = !modules[y][x];
            }
        }
    }
}
