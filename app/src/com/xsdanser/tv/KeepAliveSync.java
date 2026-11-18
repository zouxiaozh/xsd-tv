package com.xsdanser.tv;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.AbstractAccountAuthenticator;
import android.accounts.NetworkErrorException;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/**
 * 三管线保活之「管线③账号同步」—— 最硬的系统级长绑定（对齐 Aerial 的
 * RevivalAccountService + RevivalSyncService）。
 *
 * SyncManager 是系统级服务：建立一个隐藏账号并开启自动同步后，即使 App 被强停，
 * 系统到同步周期仍会重新绑定 SyncAdapter，从而拉起进程。三条管线任一存活即可复活。
 *
 * 账号完全本地、隐藏（userVisible=false）、不访问网络、不读取任何用户数据；
 * 只借 SyncManager 的绑定通道做自恢复。
 */
public final class KeepAliveSync {

    static final String ACCOUNT_TYPE = "com.xsdanser.tv.sync";
    static final String ACCOUNT_NAME = "xsd-keepalive";
    /** 用系统内置 settings authority，避免自建 ContentProvider（对齐 Aerial）。 */
    static final String AUTHORITY = "settings";

    private KeepAliveSync() {}

    /** 建立隐藏账号 + 打开自动同步（需 WRITE_SECURE_SETTINGS 之外的账号权限，均普通权限）。 */
    public static void setupSync(Context c) {
        try {
            AccountManager am = AccountManager.get(c);
            Account[] accs = am.getAccountsByType(ACCOUNT_TYPE);
            Account account;
            if (accs == null || accs.length == 0) {
                account = new Account(ACCOUNT_NAME, ACCOUNT_TYPE);
                boolean ok = am.addAccountExplicitly(account, null, null);
                Log.i(KeepAlive.TAG, "建立保活账号：" + ok);
            } else {
                account = accs[0];
            }
            try { ContentResolver.setIsSyncable(account, AUTHORITY, 1); } catch (Throwable ignore) {}
            try { ContentResolver.setSyncAutomatically(account, AUTHORITY, true); } catch (Throwable ignore) {}
            try {
                ContentResolver.addPeriodicSync(account, AUTHORITY, Bundle.EMPTY, 60L);
            } catch (Throwable t) {
                Log.i(KeepAlive.TAG, "周期同步设置跳过：" + t);
            }
            boolean master = ContentResolver.getMasterSyncAutomatically();
            if (!master) {
                Log.w(KeepAlive.TAG, "系统总同步开关关闭，账号同步复活可能受限");
            }
        } catch (Throwable t) {
            Log.e(KeepAlive.TAG, "账号同步自愈失败：" + t);
        }
    }

    /** 账号认证服务：返回一个最小 Authenticator，仅提供 IBinder 供系统绑定。 */
    public static class AccountService extends Service {
        private Authenticator mAuth;

        @Override
        public void onCreate() {
            super.onCreate();
            mAuth = new Authenticator(this);
        }

        @Override
        public IBinder onBind(Intent intent) {
            return mAuth.getIBinder();
        }
    }

    static class Authenticator extends AbstractAccountAuthenticator {
        private final Context mCtx;

        Authenticator(Context ctx) {
            super(ctx);
            mCtx = ctx;
        }

        @Override
        public Bundle editProperties(AccountAuthenticatorResponse r, String accountType) {
            return null;
        }

        @Override
        public Bundle addAccount(AccountAuthenticatorResponse r, String accountType,
                                 String authTokenType, String[] requiredFeatures, Bundle options)
                throws NetworkErrorException {
            return null;
        }

        @Override
        public Bundle confirmCredentials(AccountAuthenticatorResponse r, Account account, Bundle options)
                throws NetworkErrorException {
            return null;
        }

        @Override
        public Bundle getAuthToken(AccountAuthenticatorResponse r, Account account,
                                   String authTokenType, Bundle options) throws NetworkErrorException {
            return null;
        }

        @Override
        public String getAuthTokenLabel(String authTokenType) {
            return null;
        }

        @Override
        public Bundle updateCredentials(AccountAuthenticatorResponse r, Account account,
                                        String authTokenType, Bundle options) throws NetworkErrorException {
            return null;
        }

        @Override
        public Bundle hasFeatures(AccountAuthenticatorResponse r, Account account, String[] features)
                throws NetworkErrorException {
            Bundle b = new Bundle();
            b.putBoolean("result", false);
            return b;
        }

        @SuppressWarnings("unused")
        Context ctx() { return mCtx; }
    }

    /** 同步适配器服务：系统绑定 -> onPerformSync 触发拉起守护 + 自愈。 */
    public static class SyncService extends Service {
        private static final Object LOCK = new Object();
        private static SyncAdapter sAdapter;

        @Override
        public void onCreate() {
            super.onCreate();
            synchronized (LOCK) {
                if (sAdapter == null) {
                    sAdapter = new SyncAdapter(getApplicationContext(), true);
                }
            }
        }

        @Override
        public IBinder onBind(Intent intent) {
            return sAdapter.getSyncAdapterBinder();
        }
    }

    static class SyncAdapter extends android.content.AbstractThreadedSyncAdapter {
        SyncAdapter(Context ctx, boolean autoInitialize) {
            super(ctx, autoInitialize);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority,
                                  android.content.ContentProviderClient provider,
                                  android.content.SyncResult syncResult) {
            Log.i(KeepAlive.TAG, "系统同步触发，复活答题守护");
            KeepAlive.onBound(getContext(), "账号同步");
        }
    }
}
