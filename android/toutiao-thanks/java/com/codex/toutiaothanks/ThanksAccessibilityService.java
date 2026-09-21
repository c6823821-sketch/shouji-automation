package com.codex.toutiaothanks;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class ThanksAccessibilityService extends AccessibilityService {
    private static final String TOUTIAO = "com.ss.android.article.news";
    private static final String TOUTIAO_LITE = "com.ss.android.article.lite";
    private static final long TICK_MS = 400L;
    private static final long WAIT_AFTER_THANK_MS = 850L;
    private static final long WAIT_AFTER_PM_MS = 1050L;
    private static final long WAIT_AFTER_SEND_MS = 750L;
    private static final long WAIT_AFTER_BACK_MS = 700L;
    private static final long WAIT_AFTER_SCROLL_MS = 1250L;
    private static final int MAX_BACK_ATTEMPTS = 2;
    private static final int MAX_END_ROUNDS = 3;

    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern TIME = Pattern.compile(
            "^(刚刚|刚才|\\d+(秒|分钟|小时|天|周|个月|月|年)前|昨天|前天|今天|" +
            "\\d{1,2}[:：]\\d{2}|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})$"
    );

    private HandlerThread handlerThread;
    private Handler handler;
    private Runnable ticker;

    private int sessionId = -1;
    private final Set<String> processedKeys = new HashSet<String>();
    private String phase = "FIND";
    private String pendingKey = "";
    private int pendingRowY = 0;
    private int pendingThanksX = 0;
    private int pendingRetries = 0;
    private boolean pendingCounts = true;
    private long waitUntil = 0L;
    private int backAttempts = 0;
    private int noMatchRounds = 0;
    private String lastPageSignature = "";
    private String failedClickKey = "";
    private int failedClickCount = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (handlerThread != null && handlerThread.isAlive()) {
            return;
        }
        handlerThread = new HandlerThread("toutiao-thanks-ticker");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        ticker = new Runnable() {
            @Override
            public void run() {
                try {
                    if (AutomationController.isRunning()) {
                        tickOnce();
                    }
                } catch (Throwable throwable) {
                    AutomationController.stop("已停止：页面读取异常，请重新开始");
                } finally {
                    if (handler != null) {
                        handler.postDelayed(this, TICK_MS);
                    }
                }
            }
        };
        handler.post(ticker);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The ticker is intentionally used instead of event-driven clicking.
    }

    @Override
    public void onInterrupt() {
        AutomationController.stop("无障碍服务被系统中断，已停止");
    }

    @Override
    public void onDestroy() {
        if (handler != null && ticker != null) {
            handler.removeCallbacks(ticker);
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }

    private void tickOnce() {
        int currentSession = AutomationController.getSessionId();
        if (currentSession != sessionId) {
            beginSession(currentSession);
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            AutomationController.setStatus("正在等待页面", "请切回今日头条点赞页。");
            return;
        }

        String packageName = stringValue(root.getPackageName());
        if (!TOUTIAO.equals(packageName) && !TOUTIAO_LITE.equals(packageName)) {
            AutomationController.setStatus("请切换到今日头条",
                    "打开「我的 → 消息/互动 → 点赞」后会自动开始。");
            return;
        }

        List<NodeSnapshot> nodes = collectNodes(root);
        if (nodes.isEmpty()) {
            return;
        }

        if ("AFTER_THANKS".equals(phase)) {
            continueAfterThanks(nodes);
            return;
        }
        if ("AFTER_PM".equals(phase)) {
            continueAfterPm(nodes);
            return;
        }
        if ("AFTER_SEND".equals(phase)) {
            continueAfterSend();
            return;
        }
        if ("RETURN".equals(phase)) {
            continueReturnToLikes(nodes);
            return;
        }
        if ("SCROLL".equals(phase)) {
            continueScrolling(nodes);
            return;
        }

        if (!isLikesPage(nodes)) {
            nodes = prepareSendText(nodes);
            NodeSnapshot send = findSendButton(nodes);
            if (send != null && clickNode(send.node)) {
                phase = "AFTER_SEND";
                waitUntil = now() + WAIT_AFTER_SEND_MS;
                AutomationController.setStatus("正在发送私信", "已点单独出现的「发送」按钮。");
            } else {
                AutomationController.setStatus("请手动回到点赞页",
                        "当前不是点赞列表。为避免把头条返回主页，本软件不会连续按返回键。");
            }
            return;
        }

        noMatchRounds = 0;
        List<RowAction> actions = findThanksActions(nodes);
        RowAction next = firstUnprocessed(actions);
        if (next != null) {
            processThanks(next);
        } else {
            startVerticalScroll(nodes);
        }
    }

    private void beginSession(int currentSession) {
        sessionId = currentSession;
        processedKeys.clear();
        phase = "FIND";
        pendingKey = "";
        pendingRowY = 0;
        pendingThanksX = 0;
        pendingRetries = 0;
        pendingCounts = true;
        backAttempts = 0;
        noMatchRounds = 0;
        lastPageSignature = "";
        failedClickKey = "";
        failedClickCount = 0;
        AutomationController.setStatus("正在等待今日头条点赞页",
                "请切回今日头条，保持在「我的 → 消息/互动 → 点赞」页面。");
    }

    private RowAction firstUnprocessed(List<RowAction> actions) {
        for (RowAction action : actions) {
            if (!processedKeys.contains(action.key)) {
                return action;
            }
        }
        return null;
    }

    private void processThanks(RowAction action) {
        if (!clickNode(action.thanks.node)) {
            if (TextUtils.equals(failedClickKey, action.key)) {
                failedClickCount++;
            } else {
                failedClickKey = action.key;
                failedClickCount = 1;
            }
            if (failedClickCount <= 1) {
                AutomationController.setStatus("这一条没点上，正在重试",
                        shortKey(action.key) + "：不会跳过去。");
                waitUntil = now() + 650L;
                return;
            }
            processedKeys.add(action.key);
            AutomationController.markSkipped();
            failedClickKey = "";
            failedClickCount = 0;
            AutomationController.setStatus("跳过一条", "连续两次没点到「谢谢」，继续下一条。");
            return;
        }

        failedClickKey = "";
        failedClickCount = 0;
        pendingKey = action.key;
        pendingRowY = action.thanks.centerY();
        pendingThanksX = action.thanks.centerX();
        pendingRetries = 0;
        pendingCounts = true;
        phase = "AFTER_THANKS";
        waitUntil = now() + WAIT_AFTER_THANK_MS;
        AutomationController.setStatus("正在处理：" + shortKey(action.key),
                "已点「谢谢」，正在找同一行的「私信谢谢点赞」。");
    }

    private void continueAfterThanks(List<NodeSnapshot> nodes) {
        if (now() < waitUntil) {
            return;
        }

        NodeSnapshot pm = findPmNear(nodes, pendingRowY, pendingThanksX);
        if (pm != null && clickNode(pm.node)) {
            phase = "AFTER_PM";
            waitUntil = now() + WAIT_AFTER_PM_MS;
            AutomationController.setStatus("正在处理：" + shortKey(pendingKey),
                    "已点「私信谢谢点赞」。");
            return;
        }

        // Sometimes the row repaints a little later. Retry the same row twice
        // before giving up, so an entire row is not skipped on a transient tree.
        if (isLikesPage(nodes) && pendingRetries < 2) {
            pendingRetries++;
            waitUntil = now() + 650L;
            AutomationController.setStatus("这一行稍等重试",
                    shortKey(pendingKey) + "：正在重新找同一行的私信按钮。");
            return;
        }

        // A few Toutiao versions open the chat directly from the thank action.
        nodes = prepareSendText(nodes);
        NodeSnapshot send = findSendButton(nodes);
        if (send != null && clickNode(send.node)) {
            phase = "AFTER_SEND";
            waitUntil = now() + WAIT_AFTER_SEND_MS;
            AutomationController.setStatus("正在发送私信", "已点「发送」。");
            return;
        }

        pendingCounts = false;
        AutomationController.markSkipped();
        AutomationController.setStatus("跳过一条", "这行确实找不到私信按钮，继续下一条。");
        phase = "RETURN";
        backAttempts = 0;
    }

    private void continueAfterPm(List<NodeSnapshot> nodes) {
        if (now() < waitUntil) {
            return;
        }
        nodes = prepareSendText(nodes);
        NodeSnapshot send = findSendButton(nodes);
        if (send != null && clickNode(send.node)) {
            phase = "AFTER_SEND";
            waitUntil = now() + WAIT_AFTER_SEND_MS;
            AutomationController.setStatus("正在发送私信", "已点「发送」。");
            return;
        }

        // If the private button already sent the prefilled message directly,
        // there is no separate send button to click.
        phase = "RETURN";
        backAttempts = 0;
    }

    private void continueAfterSend() {
        if (now() < waitUntil) {
            return;
        }
        phase = "RETURN";
        backAttempts = 0;
    }

    private void continueReturnToLikes(List<NodeSnapshot> nodes) {
        if (isLikesPage(nodes)) {
            if (!processedKeys.contains(pendingKey)) {
                processedKeys.add(pendingKey);
                if (pendingCounts) {
                    AutomationController.markProcessed();
                }
            }
            phase = "FIND";
            pendingKey = "";
            backAttempts = 0;
            noMatchRounds = 0;
            AutomationController.setStatus("已完成 " + AutomationController.getProcessed() + " 条",
                    "继续处理下一行。");
            return;
        }

        if (backAttempts >= MAX_BACK_ATTEMPTS) {
            AutomationController.stop("已停止：连续返回两次仍未回到点赞页，不再继续按返回键");
            return;
        }

        performGlobalAction(GLOBAL_ACTION_BACK);
        backAttempts++;
        waitUntil = now() + WAIT_AFTER_BACK_MS;
        AutomationController.setStatus("正在返回点赞页",
                "只返回 " + backAttempts + "/" + MAX_BACK_ATTEMPTS + " 次，避免退回主页。");
    }

    private void startVerticalScroll(List<NodeSnapshot> nodes) {
        lastPageSignature = pageSignature(nodes);
        noMatchRounds = 0;
        swipeUp(nodes);
        AutomationController.markScroll();
        phase = "SCROLL";
        waitUntil = now() + WAIT_AFTER_SCROLL_MS;
        AutomationController.setStatus("当前页处理完了", "只向上滑动，继续找下一行。");
    }

    private void continueScrolling(List<NodeSnapshot> nodes) {
        if (now() < waitUntil) {
            return;
        }
        if (!isLikesPage(nodes)) {
            phase = "RETURN";
            backAttempts = 0;
            return;
        }

        List<RowAction> actions = findThanksActions(nodes);
        if (firstUnprocessed(actions) != null) {
            phase = "FIND";
            noMatchRounds = 0;
            AutomationController.setStatus("找到下一行", "继续处理。");
            return;
        }

        String currentSignature = pageSignature(nodes);
        if (currentSignature.equals(lastPageSignature)) {
            noMatchRounds++;
        } else {
            noMatchRounds = 0;
        }
        lastPageSignature = currentSignature;

        if (noMatchRounds >= MAX_END_ROUNDS) {
            AutomationController.complete("已完成：没有更多符合条件的记录");
            AutomationController.setStatus("已完成", AutomationController.summary());
            return;
        }

        swipeUp(nodes);
        AutomationController.markScroll();
        waitUntil = now() + WAIT_AFTER_SCROLL_MS;
        AutomationController.setStatus("继续向下查找",
                "连续 " + noMatchRounds + "/" + MAX_END_ROUNDS + " 次没有新内容。");
    }

    private List<RowAction> findThanksActions(List<NodeSnapshot> nodes) {
        List<NodeSnapshot> thanksNodes = new ArrayList<NodeSnapshot>();
        Set<String> seen = new HashSet<String>();
        for (NodeSnapshot node : nodes) {
            String text = node.normalized();
            if ("谢谢".equals(text) || "感谢".equals(text) || "谢谢点赞".equals(text)) {
                if (seen.add(node.identity())) {
                    thanksNodes.add(node);
                }
            }
        }

        Collections.sort(thanksNodes, new Comparator<NodeSnapshot>() {
            @Override
            public int compare(NodeSnapshot left, NodeSnapshot right) {
                return Integer.compare(left.centerY(), right.centerY());
            }
        });

        List<RowAction> result = new ArrayList<RowAction>();
        Set<String> usedKeys = new HashSet<String>();
        for (NodeSnapshot thanks : thanksNodes) {
            String key = buildRowKey(nodes, thanks.centerY());
            if (!usedKeys.add(key)) {
                key = key + "#" + thanks.centerY();
            }
            result.add(new RowAction(key, thanks));
        }
        return result;
    }

    private NodeSnapshot findPmNear(List<NodeSnapshot> nodes, int rowY, int thanksX) {
        NodeSnapshot best = null;
        int bestScore = Integer.MAX_VALUE;
        int screenHeight = screenHeight(nodes);
        int verticalTolerance = Math.max(260, Math.round(screenHeight * 0.17f));
        for (NodeSnapshot node : nodes) {
            String text = node.normalized();
            boolean privateButton = (text.contains("私信") || text.contains("私讯"))
                    && (text.contains("谢谢点赞") || text.contains("感谢点赞") || "私信".equals(text));
            if (!privateButton) {
                continue;
            }
            if (node.centerX() < thanksX - 60) {
                continue;
            }
            int verticalDistance = Math.abs(node.centerY() - rowY);
            if (verticalDistance > verticalTolerance) {
                continue;
            }
            int score = verticalDistance * 4 + Math.abs(node.centerX() - thanksX);
            if (score < bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best;
    }

    private NodeSnapshot findSendButton(List<NodeSnapshot> nodes) {
        for (NodeSnapshot node : nodes) {
            String text = node.normalized();
            if ("发送".equals(text) || "Send".equalsIgnoreCase(text) || "发送消息".equals(text)) {
                return node;
            }
        }
        return null;
    }

    private List<NodeSnapshot> prepareSendText(List<NodeSnapshot> nodes) {
        boolean changed = false;
        for (NodeSnapshot snapshot : nodes) {
            CharSequence className = snapshot.node.getClassName();
            if (className == null || !className.toString().contains("EditText") || !snapshot.node.isEnabled()) {
                continue;
            }
            if (!snapshot.normalized().contains("谢谢")) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        "谢谢点赞");
                if (snapshot.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    changed = true;
                }
            }
        }
        if (!changed) {
            return nodes;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root == null ? nodes : collectNodes(root);
    }

    private String buildRowKey(List<NodeSnapshot> nodes, int rowY) {
        NodeSnapshot liked = null;
        int likedDistance = Integer.MAX_VALUE;
        for (NodeSnapshot node : nodes) {
            if (!node.normalized().contains("点赞了你的作品")) {
                continue;
            }
            int distance = Math.abs(node.centerY() - rowY);
            if (distance < likedDistance) {
                likedDistance = distance;
                liked = node;
            }
        }

        String nickname = "";
        if (liked != null) {
            int bestBottom = Integer.MIN_VALUE;
            for (NodeSnapshot node : nodes) {
                String text = node.normalized();
                if (TextUtils.isEmpty(text) || isGenericText(text) || isTimeText(text)) {
                    continue;
                }
                if (text.contains("点赞了你的作品") || text.contains("私信")
                        || "谢谢".equals(text) || "感谢".equals(text) || "发送".equals(text)) {
                    continue;
                }
                int gap = liked.top - node.bottom;
                if (gap >= -8 && gap <= 260) {
                    if (node.bottom > bestBottom) {
                        bestBottom = node.bottom;
                        nickname = text;
                    }
                }
            }
        }

        if (TextUtils.isEmpty(nickname)) {
            nickname = "记录" + Math.max(1, rowY / 200);
        }
        return nickname;
    }

    private boolean isLikesPage(List<NodeSnapshot> nodes) {
        for (NodeSnapshot node : nodes) {
            if (node.normalized().contains("点赞了你的作品")) {
                return true;
            }
        }
        return false;
    }

    private int screenHeight(List<NodeSnapshot> nodes) {
        int height = 2400;
        for (NodeSnapshot node : nodes) {
            height = Math.max(height, node.bottom);
        }
        return height;
    }

    private String pageSignature(List<NodeSnapshot> nodes) {
        StringBuilder builder = new StringBuilder();
        for (NodeSnapshot node : nodes) {
            String text = node.normalized();
            if (TextUtils.isEmpty(text) || isTimeText(text)) {
                continue;
            }
            builder.append(text)
                    .append('@')
                    .append(node.left / 24)
                    .append(',')
                    .append(node.top / 24)
                    .append(';');
        }
        return builder.toString();
    }

    private List<NodeSnapshot> collectNodes(AccessibilityNodeInfo root) {
        List<NodeSnapshot> result = new ArrayList<NodeSnapshot>();
        collectNodes(root, result, 0);
        return result;
    }

    private void collectNodes(AccessibilityNodeInfo node, List<NodeSnapshot> result, int depth) {
        if (node == null || depth > 45) {
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() > 1 && bounds.height() > 1) {
            result.add(new NodeSnapshot(node, bounds,
                    stringValue(node.getText()), stringValue(node.getContentDescription())));
        }
        int childCount = node.getChildCount();
        for (int index = 0; index < childCount; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                collectNodes(child, result, depth + 1);
            }
        }
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() > 1 && bounds.height() > 1 && tap(bounds.centerX(), bounds.centerY())) {
            return true;
        }

        AccessibilityNodeInfo current = node;
        for (int level = 0; level < 5 && current != null; level++) {
            if (current.isClickable() && current.isEnabled()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 70L))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private void swipeUp(List<NodeSnapshot> nodes) {
        int width = 1080;
        int height = 2400;
        for (NodeSnapshot node : nodes) {
            width = Math.max(width, node.right);
            height = Math.max(height, node.bottom);
        }

        // Explicit vertical gesture: both points have exactly the same X,
        // so this cannot become a left/right swipe.
        int x = width / 2;
        Path path = new Path();
        path.moveTo(x, Math.round(height * 0.72f));
        path.lineTo(x, Math.round(height * 0.33f));
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 650L))
                .build();
        dispatchGesture(gesture, null, null);
    }

    private boolean isGenericText(String text) {
        return "视频".equals(text)
                || "图片".equals(text)
                || "头像".equals(text)
                || "点赞".equals(text)
                || "评论".equals(text)
                || "收藏".equals(text)
                || "全部".equals(text)
                || "已感谢".equals(text)
                || "私信".equals(text);
    }

    private boolean isTimeText(String text) {
        return TIME.matcher(text).matches();
    }

    private String shortKey(String value) {
        if (value == null) {
            return "记录";
        }
        return value.length() <= 14 ? value : value.substring(0, 14) + "…";
    }

    private static String normalize(String value) {
        return SPACE.matcher(stringValue(value)).replaceAll("").trim();
    }

    private static String stringValue(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static long now() {
        return android.os.SystemClock.uptimeMillis();
    }

    private static final class NodeSnapshot {
        final AccessibilityNodeInfo node;
        final String text;
        final String description;
        final int left;
        final int top;
        final int right;
        final int bottom;

        NodeSnapshot(AccessibilityNodeInfo node, Rect bounds, String text, String description) {
            this.node = node;
            this.text = text == null ? "" : text;
            this.description = description == null ? "" : description;
            this.left = bounds.left;
            this.top = bounds.top;
            this.right = bounds.right;
            this.bottom = bounds.bottom;
        }

        int centerY() {
            return (top + bottom) / 2;
        }

        int centerX() {
            return (left + right) / 2;
        }

        String normalized() {
            String textValue = normalize(text);
            if (textValue.length() > 0) {
                return textValue;
            }
            return normalize(description);
        }

        String identity() {
            return left + ":" + top + ":" + right + ":" + bottom + ":" + normalized();
        }
    }

    private static final class RowAction {
        final String key;
        final NodeSnapshot thanks;

        RowAction(String key, NodeSnapshot thanks) {
            this.key = key;
            this.thanks = thanks;
        }
    }
}