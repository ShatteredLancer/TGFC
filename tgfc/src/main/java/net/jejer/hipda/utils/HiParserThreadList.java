package net.jejer.hipda.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;

import net.jejer.hipda.bean.HiSettingsHelper;
import net.jejer.hipda.bean.ThreadBean;
import net.jejer.hipda.bean.ThreadListBean;
import net.jejer.hipda.ui.ThreadListFragment;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class HiParserThreadList {

    private static long HOLD_FETCH_NOTIFY = 0;

    public static ThreadListBean parse(Context context, Handler handler, Document doc) {

        // Update UI
        Message msgStartParse = Message.obtain();
        msgStartParse.what = ThreadListFragment.STAGE_PARSE;
        handler.sendMessage(msgStartParse);

        // Async check notify
//        new parseNotifyRunnable(context, doc).run();
        if (doc.toString().contains("新短消息")){
            NotificationMgr.fetchNotification(doc);
            NotificationMgr.showNotification(context);
        };
        HiSettingsHelper.updateMobileNetworkStatus(context);

        ThreadListBean threads = new ThreadListBean();

        //parse uid and re-set username if necessary
        if (TextUtils.isEmpty(HiSettingsHelper.getInstance().getUid())) {
            ForumSessionUtils.Identity identity = ForumSessionUtils.findIdentity(doc);
            if (!TextUtils.isEmpty(identity.uid)
                    && TextUtils.isDigitsOnly(identity.uid)
                    && !HiSettingsHelper.getInstance().getUid().equals(identity.uid)) {
                // Re-set username if it is not exactly the same as the previous case-insensitive value.
                if (!HiSettingsHelper.getInstance().getUsername().equals(identity.username)
                        && HiSettingsHelper.getInstance().getUsername().equalsIgnoreCase(identity.username))
                    HiSettingsHelper.getInstance().setUsername(identity.username);
                // UID will be persisted after the loader returns.
                threads.setUid(identity.uid);
            }
        }

        Elements tbodyES = doc.select("tbody[id]");
        for (int i = 0; i < tbodyES.size(); ++i) {

            threads.parsed = true;

            Element tbodyE = tbodyES.get(i);
            ThreadBean thread = new ThreadBean();

			/* title and tid */
            String[] idSpil = tbodyE.attr("id").split("_");
            if (idSpil.length != 2) {
                continue;
            }
            String idType = idSpil[0];
            String idNum = idSpil[1];
            String idThread = "thread_" + idNum;
            thread.setTid(idNum);
            // is stick thread or normal thread
            Boolean isStick = idType.startsWith("stickthread");
            thread.setIsStick(isStick);

            if (isStick && !HiSettingsHelper.getInstance().isShowStickThreads()) {
                continue;
            }

            // is vote thread
            Boolean isVote = false;
            if (tbodyE.select("td.icon img").size() > 0) {
                isVote = tbodyE.select("td.icon img").attr("alt").contains("投票");
            }

            Elements titleES = tbodyE.select("span#" + idThread);
            if (titleES.size() == 0) {
                continue;
            }
            String title = isVote ? "[投票]"+titleES.first().text() : titleES.first().text();
            thread.setTitle(title);

            Elements typeES = tbodyE.select("th.subject em a");
            if (typeES.size() > 0) {
                thread.setType(typeES.text());
            }

            Elements threadIsNewES = tbodyE.select("td.folder img");
            if (threadIsNewES.size() > 0) {
                String imgSrc = Utils.nullToText(threadIsNewES.first().attr("src"));
                thread.setNew(imgSrc.contains("new"));
            }

			/*  author, authorId and create_time  */
            Elements authorES = tbodyE.select("td.author");
            if (authorES.size() == 0) {
                continue;
            }
            Elements authorciteAES = authorES.first().select("cite a");
            if (authorciteAES.size() == 0) {
                continue;
            }
            String author = authorciteAES.first().text();
            if (!thread.setAuthor(author)) {
                //author in blacklist
                continue;
            }

            String userLink = authorciteAES.first().attr("href");
            if (userLink.length() < "space.php?uid=".length()) {
                continue;
            }
            String authorId = HttpUtils.getMiddleString(userLink, "uid=", "&");
            thread.setAuthorId(authorId);

            thread.setAvatarUrl(HiUtils.getAvatarUrlByUid(authorId));

            Elements threadCreateTimeES = authorES.first().select("em");
            if (threadCreateTimeES.size() == 0) {
                continue;
            }
            String threadCreateTime = threadCreateTimeES.first().text();
            thread.setTimeCreate(threadCreateTime);

            Elements threadUpdateTimeES = tbodyE.select("td.lastpost em a");
            if (threadUpdateTimeES.size() > 0) {
                String threadUpdateTime = threadUpdateTimeES.first().text();
                thread.setTimeUpdate(threadUpdateTime);
            }

			/*  comments and views  */
            Elements nums = tbodyE.select("td.nums");
            if (nums.size() == 0) {
                continue;
            }
            Elements comentsES = nums.first().select("strong");
            if (comentsES.size() == 0) {
                continue;
            }
            String comments = comentsES.first().text();
            thread.setCountCmts(comments);

            Elements viewsES = nums.first().select("em");
            if (viewsES.size() == 0) {
                continue;
            }
            String views = viewsES.first().text();
            thread.setCountViews(views);

            // lastpost
            Elements lastpostciteES = tbodyE.select("td.lastpost cite");
            if (lastpostciteES.size() == 0) {
                continue;
            }
            String lastpost = lastpostciteES.first().text();
            thread.setLastPost(lastpost);

            // attachment and picture
            Elements attachs = tbodyE.select("img.attach");
            for (int j = 0; j < attachs.size(); j++) {
                Element attach = attachs.get(j);
                String attach_img_url = attach.attr("src");
                if (attach_img_url.isEmpty()) {
                    continue;
                }
                if (attach_img_url.endsWith("image_s.gif")) {
                    thread.setHavePic(true);
                }
                if (attach_img_url.endsWith("common.gif")) {
//                    thread.setHaveAttach(true);
                    thread.setHavePic(true);
                }
            }

            //get max page number
            Elements pages = tbodyE.select("span.threadpages a");
            int maxPage = 1;
            if (pages.size() > 0) {
                Element pageLink = pages.get(pages.size() - 1);
                if (!TextUtils.isEmpty(pageLink.attr("href"))) {
                    String lastPage = HttpUtils.getMiddleString(pageLink.attr("href"), "page=", "&");
                    if (!TextUtils.isEmpty(lastPage) && TextUtils.isDigitsOnly(lastPage))
                        maxPage = Integer.parseInt(lastPage);
                }
            }
            thread.setMaxPage(maxPage);

            threads.add(thread);
        }

        return threads;
    }

    public static class parseNotifyRunnable implements Runnable {

        private Document mDoc;
        private Context mCtx;

        public parseNotifyRunnable(Context context, Document doc) {
            mDoc = doc;
            mCtx = context;
        }

        @Override
        public void run() {
            if (System.currentTimeMillis() > HOLD_FETCH_NOTIFY + 600 * 1000) {
//                NotificationMgr.fetchNotification(mDoc);
//                NotificationMgr.showNotification(mCtx);
            }
        }
    }

    public static void holdFetchNotify() {
        HOLD_FETCH_NOTIFY = System.currentTimeMillis();
    }

}
