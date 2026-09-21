package com.codex.toutiaothanks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

final class UpdateManager {
    private static final String API_URL =
            "https://api.github.com/repos/c6823821-sketch/shouji-automation/releases/latest";
    private static final String USER_AGENT = "ToutiaoThanksUpdater";
    private static final long CHECK_COOLDOWN_MS = 5 * 60 * 1000L;

    private static long lastCheckAt = 0L;
    private static boolean checking = false;
    private static boolean downloading = false;
    private static File pendingApk = null;

    private UpdateManager() {
    }

    static void checkForUpdate(Activity activity, boolean manual) {
        long now = System.currentTimeMillis();
        if (checking || (!manual && now - lastCheckAt < CHECK_COOLDOWN_MS)) {
            return;
        }
        checking = true;
        lastCheckAt = now;
        if (manual) {
            Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Release release = null;
                String error = null;
                try {
                    release = fetchLatestRelease(activity);
                } catch (Throwable throwable) {
                    error = throwable.getMessage();
                } finally {
                    checking = false;
                }

                final Release found = release;
                final String failure = error;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing()) {
                            return;
                        }
                        if (found == null) {
                            if (manual) {
                                String message = failure == null
                                        ? "已经是最新版本"
                                        : "检查更新失败，请稍后再试";
                                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                            }
                            return;
                        }
                        showUpdateDialog(activity, found);
                    }
                });
            }
        }, "github-update-check").start();
    }

    static void installPendingIfAllowed(Activity activity) {
        if (pendingApk != null && canInstallPackages(activity)) {
            installApk(activity, pendingApk);
        }
    }

    private static Release fetchLatestRelease(Activity activity) throws Exception {
        String currentVersion = currentVersion(activity);
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        int code = connection.getResponseCode();
        if (code == 404) {
            return null;
        }
        if (code != 200) {
            throw new IllegalStateException("GitHub HTTP " + code);
        }

        StringBuilder body = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }
        reader.close();

        JSONObject json = new JSONObject(body.toString());
        String tag = json.optString("tag_name", "");
        JSONArray assets = json.optJSONArray("assets");
        String downloadUrl = "";
        if (assets != null) {
            for (int index = 0; index < assets.length(); index++) {
                JSONObject asset = assets.optJSONObject(index);
                if (asset == null) {
                    continue;
                }
                String name = asset.optString("name", "");
                if ("toutiao-thanks.apk".equalsIgnoreCase(name) || name.toLowerCase().endsWith(".apk")) {
                    downloadUrl = asset.optString("browser_download_url", "");
                    if (!downloadUrl.isEmpty()) {
                        break;
                    }
                }
            }
        }

        if (tag.isEmpty() || downloadUrl.isEmpty() || !isNewer(tag, currentVersion)) {
            return null;
        }
        return new Release(tag, downloadUrl);
    }

    private static void showUpdateDialog(Activity activity, Release release) {
        new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + release.tag)
                .setMessage("当前版本：" + currentVersion(activity) + "\n是否现在下载并更新？")
                .setPositiveButton("立即更新", new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface dialog, int which) { downloadApk(activity, release); } })
                .setNegativeButton("稍后", null)
                .show();
    }

    private static void downloadApk(Activity activity, Release release) {
        if (downloading) {
            Toast.makeText(activity, "正在下载，请稍等", Toast.LENGTH_SHORT).show();
            return;
        }
        downloading = true;
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("正在下载更新");
        progress.setMessage(release.tag);
        progress.setCancelable(false);
        progress.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                File apk = null;
                String error = null;
                try {
                    apk = downloadFile(activity, release.downloadUrl);
                } catch (Throwable throwable) {
                    error = throwable.getMessage();
                } finally {
                    downloading = false;
                }

                final File downloaded = apk;
                final String failure = error;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!activity.isFinishing()) {
                            progress.dismiss();
                        }
                        if (downloaded == null) {
                            Toast.makeText(activity,
                                    "下载失败：" + (failure == null ? "未知错误" : failure),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        pendingApk = downloaded;
                        installApk(activity, downloaded);
                    }
                });
            }
        }, "github-update-download").start();
    }

    private static File downloadFile(Activity activity, String urlText) throws Exception {
        File directory = activity.getExternalFilesDir(null);
        if (directory == null) {
            throw new IllegalStateException("无法创建下载目录");
        }
        File target = new File(directory, UpdateFileProvider.FILE_NAME);
        File temporary = new File(directory, UpdateFileProvider.FILE_NAME + ".part");

        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        int code = connection.getResponseCode();
        if (code != 200) {
            throw new IllegalStateException("下载 HTTP " + code);
        }

        InputStream input = connection.getInputStream();
        FileOutputStream output = new FileOutputStream(temporary, false);
        byte[] buffer = new byte[16384];
        int length;
        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
        output.flush();
        output.close();
        input.close();

        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("无法替换旧安装包");
        }
        if (!temporary.renameTo(target)) {
            throw new IllegalStateException("无法保存安装包");
        }
        return target;
    }

    private static void installApk(Activity activity, File apk) {
        if (!apk.isFile()) {
            Toast.makeText(activity, "安装包不存在", Toast.LENGTH_LONG).show();
            return;
        }
        if (!canInstallPackages(activity)) {
            pendingApk = apk;
            openInstallPermissionSettings(activity);
            return;
        }

        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(UpdateFileProvider.AUTHORITY)
                .path("apk")
                .build();
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
        pendingApk = null;
    }

    private static boolean canInstallPackages(Activity activity) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls();
    }

    private static void openInstallPermissionSettings(Activity activity) {
        Toast.makeText(activity,
                "请允许本软件安装更新，返回后会自动继续",
                Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    private static String currentVersion(Activity activity) {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0)
                    .versionName;
        } catch (Throwable ignored) {
            return "0";
        }
    }

    private static boolean isNewer(String remoteTag, String currentVersion) {
        String remote = digitsOnly(remoteTag);
        String current = digitsOnly(currentVersion);
        if (remote.isEmpty() || current.isEmpty()) {
            return false;
        }
        String[] remoteParts = remote.split("\\.");
        String[] currentParts = current.split("\\.");
        int count = Math.max(remoteParts.length, currentParts.length);
        for (int index = 0; index < count; index++) {
            int remoteNumber = index < remoteParts.length ? safeInt(remoteParts[index]) : 0;
            int currentNumber = index < currentParts.length ? safeInt(currentParts[index]) : 0;
            if (remoteNumber > currentNumber) {
                return true;
            }
            if (remoteNumber < currentNumber) {
                return false;
            }
        }
        return false;
    }

    private static String digitsOnly(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character >= '0' && character <= '9') || character == '.') {
                builder.append(character);
            }
        }
        return builder.toString().replaceAll("^\\.+|\\.+$", "");
    }

    private static int safeInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static final class Release {
        final String tag;
        final String downloadUrl;

        Release(String tag, String downloadUrl) {
            this.tag = tag;
            this.downloadUrl = downloadUrl;
        }
    }
}