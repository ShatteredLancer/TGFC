package net.jejer.hipda.async;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import net.jejer.hipda.R;
import net.jejer.hipda.okhttp.OkHttpHelper;
import net.jejer.hipda.ui.LoginDialog;
import net.jejer.hipda.ui.ThreadListFragment;
import net.jejer.hipda.utils.Constants;

import de.greenrobot.event.EventBus;

/** Coordinates login state; credentials are entered only in the forum WebView. */
public class LoginHelper {

    private final Context mCtx;
    private final Handler mHandler;
    private String mErrorMsg = "";

    public LoginHelper(Context ctx, Handler handler) {
        mCtx = ctx;
        mHandler = handler;
    }

    public int login() {
        return login(false);
    }

    public int login(boolean lionsForum) {
        mErrorMsg = lionsForum
                ? "水区子域未接受当前登录会话，主站登录状态已保留"
                : "请打开登录页面完成网页验证";

        // The cookie may be expired even when it is still present in the persistent store. Let the
        // server response drive re-authentication; callers suppress duplicate UI events when a
        // valid session has just been restored.
        if (LoginDialog.isLoginDialogShown())
            return Constants.STATUS_FAIL_ABORT;

        if (mHandler != null) {
            Message message = Message.obtain();
            message.what = lionsForum
                    ? ThreadListFragment.STAGE_ERROR : ThreadListFragment.STAGE_NOT_LOGIN;
            Bundle bundle = new Bundle();
            bundle.putString(ThreadListFragment.STAGE_ERROR_KEY, mErrorMsg);
            message.setData(bundle);
            mHandler.sendMessage(message);
        } else if (!lionsForum)
            EventBus.getDefault().post(new LoginRequiredEvent());
        return Constants.STATUS_FAIL_ABORT;
    }

    public static boolean checkLoggedin(Context context, String response) {
        return response != null && !response.contains(context.getString(R.string.not_login));
    }

    public static boolean isLoggedIn() {
        return OkHttpHelper.getInstance().isLoggedIn();
    }

    public static void logout() {
        OkHttpHelper.getInstance().clearCookies();
        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
        cookieManager.removeAllCookie();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP)
            cookieManager.flush();
        FavoriteHelper.getInstance().clearAll();
    }

    public String getErrorMsg() {
        return mErrorMsg;
    }
}
