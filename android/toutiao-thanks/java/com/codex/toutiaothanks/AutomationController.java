package com.codex.toutiaothanks;

final class AutomationController {
    private static final Object LOCK = new Object();

    private static boolean running = false;
    private static int sessionId = 0;
    private static String status = "等待开始";
    private static String detail = "";
    private static int processed = 0;
    private static int skipped = 0;
    private static int scrolls = 0;

    private AutomationController() {
    }

    static int start() {
        synchronized (LOCK) {
            running = true;
            sessionId++;
            status = "正在等待今日头条点赞页";
            detail = "请切回今日头条，保持在「我的 → 消息/互动 → 点赞」页面。";
            processed = 0;
            skipped = 0;
            scrolls = 0;
            return sessionId;
        }
    }

    static void stop(String message) {
        synchronized (LOCK) {
            running = false;
            status = message == null || message.length() == 0 ? "已停止" : message;
        }
    }

    static void complete(String message) {
        synchronized (LOCK) {
            running = false;
            status = message == null || message.length() == 0 ? "已完成" : message;
            detail = summary();
        }
    }

    static void setStatus(String newStatus, String newDetail) {
        synchronized (LOCK) {
            status = newStatus;
            if (newDetail != null) {
                detail = newDetail;
            }
        }
    }

    static void markProcessed() {
        synchronized (LOCK) {
            processed++;
        }
    }

    static void markSkipped() {
        synchronized (LOCK) {
            skipped++;
        }
    }

    static void markScroll() {
        synchronized (LOCK) {
            scrolls++;
        }
    }

    static boolean isRunning() {
        synchronized (LOCK) {
            return running;
        }
    }

    static int getSessionId() {
        synchronized (LOCK) {
            return sessionId;
        }
    }

    static String getStatus() {
        synchronized (LOCK) {
            return status;
        }
    }

    static String getDetail() {
        synchronized (LOCK) {
            return detail;
        }
    }

    static int getProcessed() {
        synchronized (LOCK) {
            return processed;
        }
    }

    static int getSkipped() {
        synchronized (LOCK) {
            return skipped;
        }
    }

    static int getScrolls() {
        synchronized (LOCK) {
            return scrolls;
        }
    }

    static String summary() {
        synchronized (LOCK) {
            return "已处理 " + processed + " 条，跳过 " + skipped + " 条，滑动 " + scrolls + " 次";
        }
    }
}