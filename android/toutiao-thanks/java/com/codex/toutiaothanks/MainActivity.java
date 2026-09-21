package com.codex.toutiaothanks;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private static final String TOUTIAO = "com.ss.android.article.news";
    private static final String TOUTIAO_LITE = "com.ss.android.article.lite";

    private final Handler handler = new Handler();
    private TextView statusText;
    private TextView detailText;
    private TextView permissionText;
    private Button startButton;
    private Button stopButton;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
        UpdateManager.installPendingIfAllowed(this);
        UpdateManager.checkForUpdate(this, false);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(245, 246, 247));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("头条点赞感谢", 28, Color.rgb(24, 28, 33));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = text("只做一件事：谢谢 → 私信「谢谢点赞」 → 下一行 → 滑页", 15,
                Color.rgb(90, 96, 105));
        addTop(subtitle, dp(8));
        root.addView(subtitle);

        LinearLayout statusCard = card(Color.WHITE);
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        addTop(statusCard, dp(22));
        root.addView(statusCard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        permissionText = text("无障碍权限：检查中", 15, Color.rgb(90, 96, 105));
        statusCard.addView(permissionText);

        statusText = text("等待开始", 22, Color.rgb(19, 138, 71));
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        addTop(statusText, dp(10));
        statusCard.addView(statusText);

        detailText = text("先开启无障碍权限，再进入今日头条点赞页。", 14,
                Color.rgb(90, 96, 105));
        addTop(detailText, dp(7));
        statusCard.addView(detailText);

        Button permissionButton = button("1. 开启无障碍权限", Color.rgb(43, 99, 188));
        addTop(permissionButton, dp(18));
        root.addView(permissionButton, buttonParams());
        permissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(MainActivity.this,
                        "请在列表中找到「头条点赞感谢」并开启", Toast.LENGTH_LONG).show();
            }
        });

        startButton = button("2. 开始自动感谢", Color.rgb(19, 138, 71));
        addTop(startButton, dp(12));
        root.addView(startButton, buttonParams());
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startAutomation();
            }
        });

        stopButton = button("停止", Color.rgb(210, 70, 65));
        addTop(stopButton, dp(12));
        root.addView(stopButton, buttonParams());
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AutomationController.stop("已手动停止");
                Toast.makeText(MainActivity.this, "已停止", Toast.LENGTH_SHORT).show();
                refreshUi();
            }
        });

        TextView openText = text("已经开启权限了？也可以直接打开今日头条：", 14,
                Color.rgb(90, 96, 105));
        addTop(openText, dp(23));
        root.addView(openText);

        Button openButton = button("打开今日头条", Color.rgb(73, 82, 94));
        addTop(openButton, dp(9));
        root.addView(openButton, buttonParams());
        openButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openToutiao();
            }
        });

        Button updateButton = button("检查更新（GitHub）", Color.rgb(91, 72, 160));
        addTop(updateButton, dp(9));
        root.addView(updateButton, buttonParams());
        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                UpdateManager.checkForUpdate(MainActivity.this, true);
            }
        });

        TextView steps = text(
                "使用步骤\n\n" +
                "1. 点上面的按钮开启无障碍权限。\n\n" +
                "2. 打开今日头条，进入「我的 → 消息/互动 → 点赞」。\n\n" +
                "3. 回到本软件，点「开始自动感谢」，然后切回今日头条。\n\n" +
                "4. 它会按行操作；没有符合条件的就向下滑。连续两次没有新内容后自动结束。\n\n" +
                "说明：本软件不联网、不读取密码，只在你说“开始”后工作。平台若出现验证码或风险提示，请立即停止并手动处理。",
                14, Color.rgb(70, 76, 85));
        steps.setLineSpacing(0, 1.15f);
        addTop(steps, dp(24));
        root.addView(steps);

        return scrollView;
    }

    private void startAutomation() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        AutomationController.start();
        Toast.makeText(this, "已开始，请切回今日头条点赞页", Toast.LENGTH_LONG).show();
        refreshUi();
        moveTaskToBack(true);
    }

    private void openToutiao() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TOUTIAO);
        if (launch == null) {
            launch = getPackageManager().getLaunchIntentForPackage(TOUTIAO_LITE);
        }
        if (launch == null) {
            Toast.makeText(this, "没有找到今日头条或极速版", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        ComponentName expected = new ComponentName(this, ThanksAccessibilityService.class);
        List<AccessibilityServiceInfo> services =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (services == null) {
            return false;
        }
        for (AccessibilityServiceInfo info : services) {
            if (info == null || info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            String packageName = info.getResolveInfo().serviceInfo.packageName;
            String className = info.getResolveInfo().serviceInfo.name;
            if (TextUtils.equals(packageName, expected.getPackageName())
                    && TextUtils.equals(className, expected.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void refreshUi() {
        boolean enabled = isAccessibilityEnabled();
        boolean running = AutomationController.isRunning();

        permissionText.setText(enabled ? "无障碍权限：已开启" : "无障碍权限：未开启");
        permissionText.setTextColor(enabled ? Color.rgb(19, 138, 71) : Color.rgb(201, 76, 70));
        statusText.setText(AutomationController.getStatus());

        String detail = AutomationController.getDetail();
        if (TextUtils.isEmpty(detail)) {
            detail = enabled
                    ? "进入今日头条点赞页后，点“开始自动感谢”。"
                    : "先点“开启无障碍权限”，在系统列表中找到本软件并打开。";
        } else if (running) {
            detail += "\n" + AutomationController.summary();
        }
        detailText.setText(detail);

        startButton.setEnabled(enabled && !running);
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.45f);
        stopButton.setEnabled(running);
        stopButton.setAlpha(stopButton.isEnabled() ? 1f : 0.45f);
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        return view;
    }

    private LinearLayout card(int color) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(rounded(color, 18));
        return layout;
    }

    private Button button(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(17);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(rounded(color, 14));
        button.setMinHeight(dp(54));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void addTop(View view, int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = marginTop;
        view.setLayoutParams(params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}