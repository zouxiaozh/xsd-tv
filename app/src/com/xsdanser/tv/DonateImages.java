package com.xsdanser.tv;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * v33：打赏页收款码改为在线加载。
 *
 * 运营方更换收款码时只需更新服务器上的 wx.png / ali.jpg，无需重新打包 APK。
 * 包内同名 drawable 仅作为断网时的兜底，图片内容与线上一致。
 * 本类不接触金额、账号与订单，只负责把一张图片放进 ImageView。
 *
 * 兼容：xsd.qx47.com 使用 Let's Encrypt 新链（ISRG Root X1），
 * Android 7.1.1 以下的系统信任库没有这个根证书，直接 HTTPS 会握手失败。
 * 故先用系统默认校验，失败后再用内置 ISRG Root X1 的 TLSv1.2 通道重试一次；
 * 主机名校验仍由 HttpsURLConnection 默认完成，不放松为「信任所有证书」。
 *
 * 调用方是历史 smali 类 DonateActivity.setChannel()，故入口必须是静态方法。
 */
public final class DonateImages {

    private static final String TAG = "DonateImg";

    private DonateImages() { }

    private static final int CH_WECHAT = 0;
    private static final int CH_ALIPAY = 1;

    private static final String[] URLS = {
            "https://xsd.qx47.com/app/images/wx.png",
            "https://xsd.qx47.com/app/images/ali.jpg"
    };
    private static final String[] RES_NAMES = { "donate_wechat", "donate_alipay" };

    /** ISRG Root X1（DER 的 Base64），用于老设备补信任根，2035-06-04 到期。 */
    private static final String ISRG_ROOT_X1 =
            "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAwTzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2VhcmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygch77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6UA5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sWT8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyHB5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UCB5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUvKBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWnOlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTnjh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbwqHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CIrU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkqhkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZLubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KKNFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7UrTkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdCjNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVcoyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPAmRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57demyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=";

    /** 进程内缓存，左右键反复切渠道不会重复下载。 */
    private static final Bitmap[] CACHE = new Bitmap[2];
    private static SSLSocketFactory sCompatFactory;
    private static boolean sCompatTried = false;

    /**
     * 供 DonateActivity.setChannel() 调用（主线程）。
     *
     * @param iv      收款码 ImageView，用 tag 记住当前渠道，防止慢回来的旧请求串图
     * @param hint    底部提示 TextView，仅在「在线与兜底全部失败」时改文案
     * @param channel 0 = 微信支付，1 = 支付宝
     */
    public static void load(final ImageView iv, final TextView hint, final int channel) {
        if (iv == null) return;
        final int ch = (channel == CH_ALIPAY) ? CH_ALIPAY : CH_WECHAT;
        iv.setTag(Integer.valueOf(ch));

        Bitmap cached = CACHE[ch];
        if (cached != null && !cached.isRecycled()) {
            iv.setImageBitmap(cached);
            return;
        }

        // 切渠道时先清掉上一张，避免短暂显示错渠道的收款码。
        iv.setImageBitmap(null);

        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap net = fetch(URLS[ch]);
                if (net != null) CACHE[ch] = net;
                final Bitmap show = (net != null) ? net : fallback(iv, ch);
                iv.post(new Runnable() {
                    @Override public void run() {
                        Object tag = iv.getTag();
                        if (!(tag instanceof Integer)
                                || ((Integer) tag).intValue() != ch) return;   // 渠道已切走
                        if (show != null) {
                            iv.setImageBitmap(show);
                        } else if (hint != null) {
                            hint.setText("收款码暂时加载不出来，请检查网络后按 ← → 重试");
                        }
                    }
                });
            }
        }, "donate-img").start();
    }

    /** 先用系统默认校验，失败再用内置根证书的兼容通道重试一次。 */
    private static Bitmap fetch(String url) {
        Bitmap b = fetchOnce(url, null);
        if (b != null) {
            Log.d(TAG, "online ok (system trust)");
            return b;
        }
        SSLSocketFactory f = compatFactory();
        if (f == null) return null;
        b = fetchOnce(url, f);
        if (b != null) {
            Log.d(TAG, "online ok (ISRG Root X1 compat)");
            return b;
        }
        Log.w(TAG, "online failed, use builtin image: " + url);
        return null;
    }

    private static Bitmap fetchOnce(String url, SSLSocketFactory factory) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            if (factory != null && c instanceof HttpsURLConnection) {
                ((HttpsURLConnection) c).setSSLSocketFactory(factory);
            }
            c.setConnectTimeout(7000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "XsdAnser/2.02 AndroidTV");
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            in = c.getInputStream();
            // decodeStream 按内容嗅探格式：ali.jpg 实际是 PNG 数据也能正确解码。
            return BitmapFactory.decodeStream(in);
        } catch (Throwable t) {
            Log.w(TAG, "fetch: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    /** 内置 ISRG Root X1 + 强制 TLSv1.2；只用于这两张图片请求，主机名校验不放松。 */
    private static synchronized SSLSocketFactory compatFactory() {
        if (sCompatTried) return sCompatFactory;
        sCompatTried = true;
        try {
            byte[] der = Base64.decode(ISRG_ROOT_X1, Base64.DEFAULT);
            Certificate ca = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("isrgrootx1", ca);
            TrustManagerFactory tmf = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, tmf.getTrustManagers(), null);
            sCompatFactory = ctx.getSocketFactory();
        } catch (Throwable t) {
            Log.w(TAG, "compat factory: " + t.getClass().getSimpleName());
            sCompatFactory = null;
        }
        return sCompatFactory;
    }

    /** 包内同名资源作为离线兜底；用 getIdentifier 动态取 id，不引用编译期 R 常量。 */
    private static Bitmap fallback(ImageView iv, int ch) {
        try {
            Resources r = iv.getResources();
            int id = r.getIdentifier(RES_NAMES[ch], "drawable",
                    iv.getContext().getPackageName());
            if (id != 0) return BitmapFactory.decodeResource(r, id);
        } catch (Throwable ignored) { }
        return null;
    }
}
