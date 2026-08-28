// ============================================================
// 手机自动化助手 · 识别关键元素 + 自动点击 + 自动上下滑动
// 运行环境：Auto.js 系列（推荐 AutoJs6）
// 打包成独立 App：AutoJs6 内导入本项目 -> 点「打包」生成 APK
// ============================================================

auto.waitFor();   // 确保无障碍服务已开启
console.show();   // 打开控制台方便看日志

function log(msg) { toast(msg); console.log(msg); }

// ---------- 1. 检查 / 开启无障碍服务 ----------
function ensureAuto() {
  if (auto.service != null) return true;
  log("请先开启「无障碍服务」，马上为你打开设置...");
  app.startActivity({ action: "android.settings.ACCESSIBILITY_SETTINGS" });
  for (let i = 0; i < 30; i++) {
    if (auto.service != null) return true;
    sleep(1000);
  }
  log("未检测到无障碍已开启，请手动开启后重新运行。");
  exit();
}

// ---------- 2. 查找元素 ----------
function waitText(txt, timeout) { return text(txt).findOne(timeout || 3000); }
function waitDesc(txt, timeout) { return desc(txt).findOne(timeout || 3000); }
function waitId(rid, timeout) { return id(rid).findOne(timeout || 3000); }

// ---------- 3. 点击 ----------
function tapEl(e, label) {
  if (!e) { log("没找到: " + label); return false; }
  if (e.clickable()) e.click();
  else click(e.bounds().centerX(), e.bounds().centerY());
  log("已点击: " + label);
  return true;
}
function clickText(txt) { return tapEl(waitText(txt), txt); }
function clickDesc(txt) { return tapEl(waitDesc(txt), txt); }
function clickId(rid) { return tapEl(waitId(rid), rid); }

// ---------- 4. 滑动 ----------
function swipeUp() {
  let x = device.width / 2;
  swipe(x, device.height * 0.8, x, device.height * 0.2, 500);
  log("向上滑动");
}
function swipeDown() {
  let x = device.width / 2;
  swipe(x, device.height * 0.2, x, device.height * 0.8, 500);
  log("向下滑动");
}

// ---------- 5. 下滑直到出现目标 ----------
function scrollUntilText(txt, maxTimes) {
  maxTimes = maxTimes || 8;
  for (let i = 0; i < maxTimes; i++) {
    if (waitText(txt, 800)) { log("找到了: " + txt); return true; }
    swipeDown();
    sleep(700);
  }
  log("滚动 " + maxTimes + " 次仍未找到: " + txt);
  return false;
}

// ---------- 6. 信息 / 控件树 ----------
function showInfo() {
  console.log("=== 当前前台应用 ===");
  console.log("包名: " + currentPackage());
  console.log("Activity: " + currentActivity());
  console.log("屏幕: " + device.width + " x " + device.height);
}
function dumpUi() {
  try {
    let root = className("android.widget.FrameLayout").findOne(500);
    if (root == null) { log("无控件树"); return; }
    let n = 0;
    root.find().forEach(function (node) {
      if (node.bounds().width() > 0 && n < 40) {
        console.log(
          "[" + (node.text() ? "text=" + node.text() : "") +
          (node.desc() ? " desc=" + node.desc() : "") +
          (node.id() ? " id=" + node.id() : "") + "] " +
          node.bounds()
        );
        n++;
      }
    });
    log("已输出前 " + n + " 个控件");
  } catch (e) { log("控件树读取失败: " + e); }
}

// ---------- 主菜单 ----------
function mainMenu() {
  ensureAuto();
  showInfo();
  while (true) {
    const c = dialogs.select("手机自动化助手 · 请选择", [
      "1. 按【文字】点击",
      "2. 按【描述 desc】点击",
      "3. 按【资源 ID】点击",
      "4. 自动向下滑",
      "5. 自动向上滑",
      "6. 下滑直到找到某文字",
      "7. 查看控件树",
      "8. 退出"
    ]);
    if (c == null) exit();

    if (c === 0) { let t = dialogs.rawInput("要点击的文字", "发布"); if (t) clickText(t); }
    else if (c === 1) { let t = dialogs.rawInput("要点击的 content-desc", ""); if (t) clickDesc(t); }
    else if (c === 2) { let t = dialogs.rawInput("资源ID(如 com.xxx:id/btn)", ""); if (t) clickId(t); }
    else if (c === 3) { let n = parseFloat(dialogs.rawInput("下滑次数", "1")) || 1; for (let i = 0; i < n; i++) swipeDown(); }
    else if (c === 4) { swipeUp(); }
    else if (c === 5) { let t = dialogs.rawInput("要查找的文字", "关注"); let n = parseFloat(dialogs.rawInput("最多滚动次数", "8")) || 8; scrollUntilText(t, n); }
    else if (c === 6) { dumpUi(); }
    else if (c === 7) { console.hide(); exit(); }
    sleep(400);
  }
}

mainMenu();
