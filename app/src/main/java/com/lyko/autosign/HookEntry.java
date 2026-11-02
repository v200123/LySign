package com.lyko.autosign;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import java.util.Calendar;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET = "com.lynkco.customer";
    private static final String DAILY_PREFS = "lyko_auto_sign_hook";
    private static final String LAST_OPEN_DAY = "last_open_day";
    private static final Map<WebView, Boolean> WATCHING = new WeakHashMap<>();
    private static Handler mainHandler;
    private static final int MAX_POLLS = 225;
    private static final long POLL_DELAY_MS = 800;
    private static final long STATUS_SETTLE_MS = 15_000;
    private static final String OBSERVE_SCRIPT = "(function(){"
            + "if(location.hash.indexOf('/pages/sign/index')<0)return -1;"
            + "var b=document.querySelector('.sign-btn-box .sign-btn');"
            + "if(!b)return 0;"
            + "var t=(b.textContent||'').replace(/\\s+/g,'');"
            + "if(t.indexOf('已签到')>=0)return 2;"
            + "if(t!=='立即签到')return 0;"
            + "if(b.hasAttribute('disabled')||b.getAttribute('aria-disabled')==='true')return 0;"
            + "return 3;"
            + "})()";
    private static final String CLICK_SCRIPT = "(function(){"
            + "if(location.hash.indexOf('/pages/sign/index')<0)return -1;"
            + "var b=document.querySelector('.sign-btn-box .sign-btn');"
            + "if(!b)return 0;"
            + "var t=(b.textContent||'').replace(/\\s+/g,'');"
            + "if(t.indexOf('已签到')>=0)return 2;"
            + "if(t!=='立即签到')return 0;"
            + "if(b.hasAttribute('disabled')||b.getAttribute('aria-disabled')==='true')return 0;"
            + "var day=new Date().toDateString();"
            + "var a=window.__lykoAutoSignAttempt;var now=Date.now();"
            + "if(!a||a.day!==day)a={day:day,at:0,count:0};"
            + "if(a.count>0&&now-a.at<15000)return 4;"
            + "if(a.count>=3)return 5;"
            + "a.at=now;a.count++;window.__lykoAutoSignAttempt=a;"
            + "b.click();return 1;"
            + "})()";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!TARGET.equals(param.packageName) || !TARGET.equals(param.processName)) {
            return;
        }
        XC_MethodHook watchLoad = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam method) {
                watch((WebView) method.thisObject);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(WebView.class, "loadUrl", String.class, watchLoad);
            XposedHelpers.findAndHookMethod(WebView.class, "loadUrl", String.class,
                    Map.class, watchLoad);
            XposedHelpers.findAndHookMethod(WebView.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam method) {
                            WebView webView = (WebView) method.thisObject;
                            watch(webView);
                        }
                    });
            XposedHelpers.findAndHookMethod(WebView.class, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam method) {
                            watch((WebView) method.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(WebView.class, "onWindowVisibilityChanged",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam method) {
                            if ((int) method.args[0] == android.view.View.VISIBLE) {
                                watch((WebView) method.thisObject);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam method) {
                            Activity activity = (Activity) method.thisObject;
                            mainHandler().postDelayed(() -> {
                                if (activity.isFinishing() || activity.isDestroyed()) {
                                    return;
                                }
                                watchViews(activity.getWindow().getDecorView());
                                openSignPageOnFirstLaunch(activity);
                            }, 1_500);
                        }
                    });
            XposedBridge.log("LynkAutoSign: activity and WebView hooks installed");
        } catch (Throwable error) {
            XposedBridge.log("LynkAutoSign: hook installation failed: " + error);
        }
    }

    private static void openSignPageOnFirstLaunch(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) {
            return;
        }
        Calendar now = Calendar.getInstance();
        int day = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR);
        SharedPreferences prefs = activity.getSharedPreferences(DAILY_PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt(LAST_OPEN_DAY, -1) == day) {
            return;
        }
        Intent current = activity.getIntent();
        String data = current == null ? null : current.getDataString();
        if (isSignUrl(data)) {
            prefs.edit().putInt(LAST_OPEN_DAY, day).apply();
            return;
        }
        try {
            activity.startActivity(ScheduleManager.signIntent());
            prefs.edit().putInt(LAST_OPEN_DAY, day).apply();
            XposedBridge.log("LynkAutoSign: opened sign page on first app launch today");
        } catch (Throwable error) {
            XposedBridge.log("LynkAutoSign: opening sign page failed: " + error);
        }
    }

    private static boolean isSignUrl(String url) {
        return url != null && (url.contains("/pages/sign/index")
                || url.contains("%23/pages/sign/index"));
    }

    private static Handler mainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    private static void watch(WebView webView) {
        synchronized (WATCHING) {
            if (WATCHING.containsKey(webView)) {
                return;
            }
            WATCHING.put(webView, true);
        }
        mainHandler().postDelayed(new Poller(webView), POLL_DELAY_MS);
    }

    // A cached WebView need not call loadUrl/onResume when its Activity returns.
    private static void watchViews(View view) {
        if (view instanceof WebView) {
            watch((WebView) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                watchViews(group.getChildAt(i));
            }
        }
    }

    private static void stop(WebView webView) {
        synchronized (WATCHING) {
            WATCHING.remove(webView);
        }
    }

    private static final class Poller implements Runnable {
        private final WebView webView;
        private int polls;
        private long candidateSince;
        private long signedSince;
        private long clickedAt;
        private String lastState = "not on sign page";

        Poller(WebView webView) {
            this.webView = webView;
        }

        @Override
        public void run() {
            if (++polls > MAX_POLLS) {
                XposedBridge.log("LynkAutoSign: detection timed out; last state=" + lastState);
                stop(webView);
                return;
            }
            if (!webView.isShown() || webView.getWindowVisibility() != View.VISIBLE) {
                candidateSince = 0;
                signedSince = 0;
                mainHandler().postDelayed(this, POLL_DELAY_MS);
                return;
            }
            if (!isSignUrl(webView.getUrl())) {
                candidateSince = 0;
                signedSince = 0;
                mainHandler().postDelayed(this, POLL_DELAY_MS);
                return;
            }
            try {
                webView.evaluateJavascript(OBSERVE_SCRIPT, value -> {
                    lastState = value;
                    long now = SystemClock.uptimeMillis();
                    if ("2".equals(value)) {
                        candidateSince = 0;
                        if (signedSince == 0) {
                            signedSince = now;
                        }
                        if (now - signedSince >= STATUS_SETTLE_MS) {
                            XposedBridge.log("LynkAutoSign: " + (clickedAt == 0
                                    ? "already signed (stable)" : "sign success confirmed"));
                            stop(webView);
                        } else {
                            mainHandler().postDelayed(this, POLL_DELAY_MS);
                        }
                    } else if ("3".equals(value)) {
                        signedSince = 0;
                        if (candidateSince == 0) {
                            candidateSince = now;
                            XposedBridge.log("LynkAutoSign: sign button found; waiting 15s for stable state");
                        }
                        if (now - candidateSince >= STATUS_SETTLE_MS
                                && (clickedAt == 0 || now - clickedAt >= STATUS_SETTLE_MS)) {
                            clickIfStillUnsigned();
                        } else {
                            mainHandler().postDelayed(this, POLL_DELAY_MS);
                        }
                    } else {
                        candidateSince = 0;
                        signedSince = 0;
                        mainHandler().postDelayed(this, POLL_DELAY_MS);
                    }
                });
            } catch (Throwable error) {
                XposedBridge.log("LynkAutoSign: page check failed: " + error);
                stop(webView);
            }
        }

        private void clickIfStillUnsigned() {
            webView.evaluateJavascript(CLICK_SCRIPT, value -> {
                if ("1".equals(value)) {
                    clickedAt = SystemClock.uptimeMillis();
                    XposedBridge.log("LynkAutoSign: clicked sign button; awaiting confirmation");
                    mainHandler().postDelayed(this, POLL_DELAY_MS);
                } else if ("5".equals(value)) {
                    XposedBridge.log("LynkAutoSign: stopped after 3 unconfirmed attempts; check network/login");
                    stop(webView);
                } else {
                    candidateSince = 0;
                    mainHandler().postDelayed(this, POLL_DELAY_MS);
                }
            });
        }
    }
}
