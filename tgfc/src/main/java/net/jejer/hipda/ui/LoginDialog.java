package net.jejer.hipda.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import net.jejer.hipda.async.LoginEvent;
import net.jejer.hipda.async.FavoriteHelper;
import net.jejer.hipda.async.LoginHelper;
import net.jejer.hipda.bean.HiSettingsHelper;
import net.jejer.hipda.okhttp.OkHttpHelper;
import net.jejer.hipda.utils.ForumSessionUtils;
import net.jejer.hipda.utils.HiUtils;
import net.jejer.hipda.utils.Logger;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import de.greenrobot.event.EventBus;

/** Login dialog backed by the forum's own WebView form and reCAPTCHA flow. */
public class LoginDialog extends Dialog {

    private static boolean isShown = false;
    private static final int MAX_VERIFICATION_ATTEMPTS = 3;
    private static final String MOBILE_LAYOUT_SCRIPT = "javascript:(function(){"
            + "var head=document.head||document.getElementsByTagName('head')[0];"
            + "var viewport=document.querySelector('meta[name=viewport]');"
            + "if(!viewport){viewport=document.createElement('meta');viewport.name='viewport';head.appendChild(viewport);}"
            + "viewport.content='width=device-width,initial-scale=1,maximum-scale=5,user-scalable=yes';"
            + "if(!document.getElementById('tgfc-app-mobile-style')){"
            + "var style=document.createElement('style');style.id='tgfc-app-mobile-style';"
            + "style.textContent='*{box-sizing:border-box!important}'"
            + "+'html,body{min-width:0!important;width:100%!important;margin:0!important;padding:0!important;background:#f3f3f3!important;overflow-x:hidden!important;font-size:16px!important}'"
            + "+'body>.wrap{min-width:0!important;width:100%!important;margin:0!important;padding:8px!important}'"
            + "+'body>.wrap>div:first-child,body>.wrap>.clear,body>.wrap>br,body>.wrap>#nav,.popupmenu_popup,.ad_footerbanner,#footer{display:none!important}'"
            + "+'.mainbox.formbox{min-width:0!important;width:100%!important;margin:0!important;border:1px solid #c8c8c8!important;background:#fff!important;box-shadow:none!important}'"
            + "+'.mainbox.formbox .headactions{display:none!important}'"
            + "+'.mainbox.formbox h1{height:auto!important;margin:0!important;padding:12px!important;font-size:20px!important;line-height:1.4!important;background:#e8e8e8!important;color:#222!important}'"
            + "+'.formbox table,.formbox tbody,.formbox tr,.formbox th,.formbox td{display:block!important;min-width:0!important;width:100%!important;height:auto!important}'"
            + "+'.formbox table{border:0!important;border-collapse:collapse!important}'"
            + "+'.formbox tr{padding:10px 12px!important;border-top:1px solid #e2e2e2!important}'"
            + "+'.formbox th,.formbox td{padding:0!important;border:0!important;text-align:left!important;line-height:1.5!important}'"
            + "+'.formbox th{margin-bottom:6px!important;font-weight:bold!important;color:#222!important}'"
            + "+'.formbox label{display:inline-block!important;margin:2px 12px 2px 0!important;line-height:40px!important}'"
            + "+'.formbox input[type=text],.formbox input[type=password],.formbox select{display:block!important;width:100%!important;max-width:none!important;min-height:46px!important;margin:0 0 6px!important;padding:9px 10px!important;border:1px solid #888!important;border-radius:3px!important;background:#fff!important;color:#111!important;font-size:16px!important}'"
            + "+'.formbox input.radio{width:20px!important;height:20px!important;margin:0 6px 0 0!important;vertical-align:middle!important}'"
            + "+'.formbox button.submit{width:100%!important;min-height:48px!important;margin:2px 0!important;padding:10px 16px!important;border:1px solid #555!important;border-radius:3px!important;font-size:18px!important}'"
            + "+'.formbox tr:first-child{padding-left:4px!important;padding-right:4px!important}'"
            + "+'.formbox tr:first-child th{padding-left:8px!important}'"
            + "+'.g-recaptcha{width:304px!important;max-width:100%!important;margin:4px auto!important;overflow:visible!important}'"
            + "+'iframe{max-width:100%!important}'"
            + "+'@media(max-width:335px){.g-recaptcha{transform:scale(.92)!important;transform-origin:0 0!important;height:72px!important;margin-left:0!important;margin-right:0!important}}';"
            + "head.appendChild(style);}})()";

    private final Context mCtx;
    private Handler mHandler;
    private WebView mWebView;
    private boolean verifying;
    private int verificationAttempts;
    private AsyncTask<Void, Void, SessionResult> mVerifyTask;

    private LoginDialog(Context context) {
        super(context);
        mCtx = context;
    }

    public static LoginDialog getInstance(Context context) {
        if (context != null && !isShown) {
            isShown = true;
            return new LoginDialog(context);
        }
        return null;
    }

    /** Returns whether the single WebView login flow is currently active. */
    public static boolean isLoginDialogShown() {
        return isShown;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mWebView = new WebView(mCtx);
        OkHttpHelper.getInstance().clearCookies();
        HiSettingsHelper.getInstance().setPassword("");
        HiSettingsHelper.getInstance().setSecQuestion("");
        HiSettingsHelper.getInstance().setSecAnswer("");
        HiSettingsHelper.getInstance().setGoogleVerifyCode("");
        WebSettings settings = mWebView.getSettings();
        // Discuz derives the tgc_auth encryption key from the complete User-Agent. Native
        // requests must therefore reuse the WebView UA that submitted the login form.
        HiUtils.setSessionUserAgent(settings.getUserAgentString());
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.removeAllCookie();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            cookieManager.setAcceptThirdPartyCookies(mWebView, true);

        mWebView.setWebChromeClient(new WebChromeClient());
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                verificationAttempts = 0;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (needsMobileLayout(url))
                    view.loadUrl(MOBILE_LAYOUT_SCRIPT);
                flushWebViewCookies();
                if (!verifying && verificationAttempts < MAX_VERIFICATION_ATTEMPTS
                        && hasAuthCookie() && isLoginSuccessCandidate(url))
                    scheduleSessionVerification();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Toast.makeText(mCtx, "登录页面加载失败：" + description, Toast.LENGTH_SHORT).show();
            }
        });

        setContentView(mWebView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setTitle("用户登录");
        mWebView.loadUrl(HiUtils.LoginGetFormHash);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void verifySession() {
        verifying = true;
        mVerifyTask = new AsyncTask<Void, Void, SessionResult>() {
            @Override
            protected SessionResult doInBackground(Void... ignored) {
                try {
                    OkHttpHelper.getInstance().importWebViewCookies(true, HiUtils.BaseUrl);
                    boolean bbsAuthCookieImported = OkHttpHelper.getInstance()
                            .hasAuthCookieForUrl(HiUtils.BaseUrl);
                    String response = OkHttpHelper.getInstance().get(HiUtils.BaseUrl + "index.php");
                    Document document = Jsoup.parse(response);
                    boolean loginLinkAbsent = LoginHelper.checkLoggedin(mCtx, response);
                    boolean logoutLinkPresent = ForumSessionUtils.hasLogoutLink(document);
                    // The forum has changed its header markup more than once. The absence of
                    // the anonymous login marker is the stable server-side signal; a logout
                    // link is useful diagnostics but is not required for accepting the session.
                    // Require both the imported BBS cookie and the authenticated response so a
                    // markup-only change cannot produce a false-positive login.
                    boolean bbsLoggedIn = bbsAuthCookieImported && loginLinkAbsent;
                    ForumSessionUtils.Identity identity = ForumSessionUtils.findIdentity(document);
                    Logger.i("Login verification: scope=bbs, authCookiePresent="
                            + bbsAuthCookieImported + ", serverLoggedIn=" + bbsLoggedIn
                            + ", logoutLinkPresent=" + logoutLinkPresent
                            + ", identitySelector=" + identity.selector);

                    if (!bbsLoggedIn) {
                        String message = bbsAuthCookieImported
                                ? "Cookie 已同步，但论坛仍返回未登录状态"
                                : "未从网页读取到登录 Cookie（tgc_auth）";
                        return SessionResult.failure(message, true);
                    }

                    // The authenticated BBS response is authoritative. Username/UID parsing is
                    // best-effort because the forum header markup has changed independently before.
                    return SessionResult.success(identity.username, identity.uid);
                } catch (Exception e) {
                    Logger.w("Login verification request failed: " + e.getClass().getSimpleName());
                    return SessionResult.failure(OkHttpHelper.getErrorMessage(e), true);
                }
            }

            @Override
            protected void onPostExecute(SessionResult result) {
                mVerifyTask = null;
                verifying = false;
                if (!isShowing())
                    return;
                if (!result.success) {
                    if (result.retryable && verificationAttempts < MAX_VERIFICATION_ATTEMPTS) {
                        scheduleSessionVerification(1000);
                        return;
                    }
                    Toast.makeText(mCtx, "登录状态确认失败：" + result.message, Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!TextUtils.isEmpty(result.username))
                    HiSettingsHelper.getInstance().setUsername(result.username);
                if (!TextUtils.isEmpty(result.uid))
                    HiSettingsHelper.getInstance().setUid(result.uid);
                HiSettingsHelper.getInstance().setPassword("");
                HiSettingsHelper.getInstance().setSecQuestion("");
                HiSettingsHelper.getInstance().setSecAnswer("");
                HiSettingsHelper.getInstance().setGoogleVerifyCode("");
                EventBus.getDefault().post(new LoginEvent());
                FavoriteHelper.getInstance().updateCache();
                if (mHandler != null) {
                    Message message = Message.obtain();
                    message.what = ThreadListFragment.STAGE_REFRESH;
                    mHandler.sendMessage(message);
                }
                dismiss();
            }

            @Override
            protected void onCancelled() {
                mVerifyTask = null;
                verifying = false;
            }
        }.execute();
    }

    private boolean isForumUrl(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme())
                && ("bbs.tgfcer.com".equalsIgnoreCase(host)
                || "s.tgfcer.com".equalsIgnoreCase(host));
    }

    private boolean needsMobileLayout(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme())
                && ("bbs.tgfcer.com".equalsIgnoreCase(host)
                || "s.tgfcer.com".equalsIgnoreCase(host));
    }

    private boolean hasAuthCookie() {
        CookieManager cookieManager = CookieManager.getInstance();
        return hasNonEmptyCookie(cookieManager.getCookie(HiUtils.BaseUrl), "tgc_auth")
                || hasNonEmptyCookie(cookieManager.getCookie(HiUtils.LionsUrl), "tgc_auth");
    }

    private boolean isLoginSuccessCandidate(String url) {
        if (hasAuthCookie())
            return true;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (!"bbs.tgfcer.com".equalsIgnoreCase(host)
                && !"s.tgfcer.com".equalsIgnoreCase(host))
            return false;
        String action = uri.getQueryParameter("action");
        return !"login".equalsIgnoreCase(action);
    }

    private void scheduleSessionVerification() {
        scheduleSessionVerification(250);
    }

    private void scheduleSessionVerification(long delayMillis) {
        if (mWebView == null || verifying)
            return;
        mWebView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!verifying && mWebView != null
                        && verificationAttempts < MAX_VERIFICATION_ATTEMPTS) {
                    verificationAttempts++;
                    verifySession();
                }
            }
        }, delayMillis);
    }

    private void flushWebViewCookies() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            CookieManager.getInstance().flush();
    }

    private boolean hasNonEmptyCookie(String cookieHeader, String expectedName) {
        if (TextUtils.isEmpty(cookieHeader))
            return false;
        for (String cookiePart : cookieHeader.split(";")) {
            int separator = cookiePart.indexOf('=');
            if (separator <= 0)
                continue;
            String name = cookiePart.substring(0, separator).trim();
            String value = cookiePart.substring(separator + 1).trim();
            if (expectedName.equals(name) && !TextUtils.isEmpty(value))
                return true;
        }
        return false;
    }

    @Override
    protected void onStop() {
        if (mVerifyTask != null) {
            mVerifyTask.cancel(true);
            mVerifyTask = null;
        }
        verifying = false;
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null);
            mWebView.clearHistory();
            if (mWebView.getParent() instanceof ViewGroup)
                ((ViewGroup) mWebView.getParent()).removeView(mWebView);
            mWebView.destroy();
            mWebView = null;
        }
        super.onStop();
        isShown = false;
    }

    public void setHandler(Handler handler) {
        mHandler = handler;
    }

    private static class SessionResult {
        final boolean success;
        final String username;
        final String uid;
        final String message;
        final boolean retryable;
        private SessionResult(boolean success, String username, String uid, String message,
                              boolean retryable) {
            this.success = success;
            this.username = username;
            this.uid = uid;
            this.message = message;
            this.retryable = retryable;
        }

        static SessionResult success(String username, String uid) {
            return new SessionResult(true, username, uid, "", false);
        }

        static SessionResult failure(String message, boolean retryable) {
            return new SessionResult(false, "", "", message, retryable);
        }
    }
}
