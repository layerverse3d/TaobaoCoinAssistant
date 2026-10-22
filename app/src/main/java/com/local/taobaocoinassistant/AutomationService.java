package com.local.taobaocoinassistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutomationService extends Service {
    private static final String TB = "com.taobao.taobao";
    private static final String TMALL = "com.tmall.wireless";
    private static final int COIN_HOME_EVIDENCE_THRESHOLD = 4;
    private static volatile AutomationService INSTANCE;
    private static final String ACTION_START = "com.local.taobaocoinassistant.START";
    private static final String ACTION_STOP = "com.local.taobaocoinassistant.STOP";
    private static final String ACTION_SYNC_OVERLAY = "com.local.taobaocoinassistant.SYNC_OVERLAY";
    private static final String EXTRA_MODE = "com.local.taobaocoinassistant.EXTRA_MODE";
    private static final String ACTION_RESTART_FROM_CURRENT = "com.local.taobaocoinassistant.RESTART_FROM_CURRENT";
    private static final String LAST_MODE_KEY = "last_task_mode";
    private static final int FOREGROUND_ID = 2001;
    private static final String FOREGROUND_CHANNEL = "coin_automation";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private StatusOverlay statusOverlay;
    private final Runnable overlayTicker = new Runnable() {
        @Override public void run() {
            syncOverlay();
            mainHandler.postDelayed(this, 500);
        }
    };
    private final Map<String, Integer> attempted = new HashMap<>();
    private volatile boolean stopRequested = false;
    private volatile boolean automationJobActive = false;
    private volatile TaskMode currentMode = TaskMode.COIN;
    private OcrEngine ocr;
    private int executedCount = 0;
    private int noChangeScrollCount = 0;
    private int scrollCount = 0;
    private int finalTopRecheckRounds = 0;
    private TaskRuleStore.Rules taskRules;
    private int lastDailySignX = -1;
    private int lastDailySignY = -1;
    // “赚更多金币”几何兜底每次打开任务面板最多使用一次，避免弹窗已出现但 OCR 未刷新时重复点击背景。
    private boolean coinHomeGeometryFallbackUsed = false;
    // v1.1: generic modal/ad obstruction recovery. Back is safer than tapping an unknown X.
    private int popupDismissCount = 0;
    // 能量模式：已成功认证“做任务赚能量”任务面板。用于面板滚动后顶部标题/累计奖励
    // 离开 OCR 视口时，仍能结合「进度 + 动作按钮」同行证据确认仍处于任务列表上下文。
    private volatile boolean energyPanelConfirmed = false;
    // 本轮已对“看…得能量”促销覆盖层执行的动作次数（BACK/安全X），上限 3 次，防止连续乱按。
    private int promoOverlayActions = 0;

    // 当新版 UC WebView 不暴露 EditText 时，Android shell 的 `input text` 对中文并不可靠。
    // 因此 OCR+Shizuku 兜底使用纯 ASCII 搜索词；只用于完成“搜一搜”任务，不改变任务筛选规则。
    private static final List<String> SEARCH_KEYS_ASCII = Arrays.asList(
            "iphone", "macbook", "xiaomi", "camera", "milk"
    );

    // “看…得能量”促销覆盖层标题锚（看抵扣快闪得能量 / 看宠物好货得能量 等变体）。
    private static final Pattern PROMO_OVERLAY_TITLE = Pattern.compile("看.{1,10}得能量");

    public static boolean isServiceActive() {
        AutomationService s = INSTANCE;
        return s != null;
    }

    /** 统一日志前缀：所有关键阶段自动带 [金币]/[能量]，便于区分当前运行模式。 */
    private void logMode(String text) {
        AppState.log("[" + currentMode.label + "] " + text);
    }

    public static void start(Context context, TaskMode mode) {
        Intent i = new Intent(context, AutomationService.class).setAction(ACTION_START);
        i.putExtra(EXTRA_MODE, mode == null ? TaskMode.COIN.name() : mode.name());
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void requestStop(Context context) {
        AutomationService s = INSTANCE;
        if (s != null) {
            s.stopAutomation();
            return;
        }
        // No active controller means there is nothing to stop.
        AppState.running = false;
        AppState.status = "已停止";
    }

    public static void syncOverlay(Context context) {
        AutomationService s = INSTANCE;
        if (s != null) {
            s.syncOverlay();
            return;
        }
        if (!OverlaySettings.isEnabled(context)) return;
        Intent i = new Intent(context, AutomationService.class).setAction(ACTION_SYNC_OVERLAY);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    /** 悬浮窗“重新开始”入口：由服务串行调度，上一轮完全结束后才原地恢复。 */
    public static void requestRestartFromCurrent(Context context) {
        Intent i = new Intent(context, AutomationService.class).setAction(ACTION_RESTART_FROM_CURRENT);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        if (ocr == null) ocr = new OcrEngine();
        if (statusOverlay == null) {
            statusOverlay = new StatusOverlay(this, this::stopAutomation, this::beginRestartFromCurrent);
        }
        Notification foreground = buildForegroundNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FOREGROUND_ID, foreground, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FOREGROUND_ID, foreground);
        }
        mainHandler.removeCallbacks(overlayTicker);
        mainHandler.post(overlayTicker);
        logMode("纯 Shizuku + OCR 自动化服务已启动（不使用无障碍）");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAutomation();
        } else if (ACTION_START.equals(action)) {
            String modeName = intent == null ? null : intent.getStringExtra(EXTRA_MODE);
            startAutomation(TaskMode.fromName(modeName));
        } else if (ACTION_RESTART_FROM_CURRENT.equals(action)) {
            beginRestartFromCurrent();
        } else {
            syncOverlay();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        AppState.running = false;
        INSTANCE = null;
        mainHandler.removeCallbacks(overlayTicker);
        if (statusOverlay != null) statusOverlay.hide();
        if (ocr != null) ocr.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    private Notification buildForegroundNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    FOREGROUND_CHANNEL, "淘金币自动化", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持纯 Shizuku + OCR 自动化运行");
            nm.createNotificationChannel(ch);
        }
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, AutomationService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "停止脚本", stopPi).build();
        return new Notification.Builder(this, FOREGROUND_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("淘金币助手")
                .setContentText(AppState.running ? "自动化运行中" : "自动化服务就绪")
                .setContentIntent(pi)
                .addAction(stopAction)
                .setOngoing(true)
                .build();
    }

    private void refreshForegroundNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(FOREGROUND_ID, buildForegroundNotification());
        } catch (Throwable ignored) {}
    }

    private void syncOverlay() {
        if (statusOverlay == null) return;
        mainHandler.post(() -> {
            if (statusOverlay == null) return;
            if (OverlaySettings.isEnabled(this)) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    statusOverlay.hide();
                    if (!AppState.running) stopSelf();
                    return;
                }
                if (!statusOverlay.isAttached()) statusOverlay.show();
                statusOverlay.update();
            } else {
                statusOverlay.hide();
                if (!AppState.running) stopSelf();
            }
        });
    }

    private synchronized void startAutomation(TaskMode mode) {
        beginRun(mode, false);
    }

    /** 悬浮窗“重新开始”：沿用上一次显式选择的模式，从当前页面原地恢复（不重启淘宝）。 */
    private synchronized void beginRestartFromCurrent() {
        beginRun(loadLastMode(), true);
    }

    private void saveLastMode(TaskMode mode) {
        getSharedPreferences("ui_settings_v1", Context.MODE_PRIVATE)
                .edit().putString(LAST_MODE_KEY, mode.name()).apply();
    }

    private TaskMode loadLastMode() {
        String name = getSharedPreferences("ui_settings_v1", Context.MODE_PRIVATE)
                .getString(LAST_MODE_KEY, null);
        return TaskMode.fromName(name);
    }

    /**
     * 唯一的运行入口：同一时刻只允许一个自动化作业（单线程 worker + automationJobActive）。
     * resumeFromCurrent=true 时走原地恢复，不 force-stop 淘宝。
     */
    private synchronized void beginRun(TaskMode mode, boolean resumeFromCurrent) {
        if (automationJobActive || AppState.running) {
            logMode("上一轮任务仍在运行/收尾，暂不能开始");
            return;
        }
        currentMode = (mode == null ? TaskMode.COIN : mode);
        saveLastMode(currentMode);
        energyPanelConfirmed = false;
        promoOverlayActions = 0;
        automationJobActive = true;
        stopRequested = false;
        attempted.clear();
        executedCount = 0;
        noChangeScrollCount = 0;
        scrollCount = 0;
        finalTopRecheckRounds = 0;
        popupDismissCount = 0;
        taskRules = TaskRuleStore.load(this);
        logMode("━━━━━━━━━━ " + (resumeFromCurrent ? "原地重新开始" : "新一轮执行") + " ━━━━━━━━━━");
        logMode("任务规则已加载：" + taskRules.summary());
        AppState.running = true;
        AppState.status = "正在启动";
        refreshForegroundNotification();
        worker.execute(() -> runAutomationInternal(resumeFromCurrent));
    }

    private void stopAutomation() {
        stopRequested = true;
        // 保持 running=true 直到 worker 真正退出（finally），期间悬浮窗仍显示“停止”，
        // 状态显示“正在收尾”，避免用户误以为已停止而重复操作。
        AppState.status = "正在收尾";
        logMode("收到停止请求，正在收尾…");
        refreshForegroundNotification();
    }

    private void runAutomationInternal(boolean resumeFromCurrent) {
        NotificationHelper.notify(this, "淘金币助手",
                currentMode.label + (resumeFromCurrent ? "原地重新开始" : "任务开始执行"), 1001);
        logMode(resumeFromCurrent
                ? "原地重新开始：从当前页面恢复，不重启淘宝"
                : "开始执行：单线程状态机，不启用后台自动点击");
        try {
            if (!ShizukuShell.hasPermission()) {
                throw new IllegalStateException("Shizuku 未连接或未授权");
            }
            boolean entered = resumeFromCurrent
                    ? resumeFromCurrentPage(currentMode)
                    : enterTaskList(true, currentMode);
            if (!entered) {
                if (stopRequested) {
                    AppState.status = "已停止";
                    logMode("用户已停止执行，正常退出");
                    return;
                }
                throw new IllegalStateException(resumeFromCurrent
                        ? "原地恢复失败：无法确认当前页面属于" + currentMode.label + "任务流程"
                        : "无法进入" + currentMode.label + "任务列表");
            }
            AppState.status = "识别任务中";
            mainLoop();
            if (stopRequested) {
                AppState.status = "已停止";
                logMode("用户已停止执行，正常退出");
            } else {
                AppState.status = "已完成";
                logMode("本次结束，共发起执行 " + executedCount + " 次任务");
                NotificationHelper.notify(this, "淘金币助手", currentMode.label + "任务执行结束，共发起 " + executedCount + " 次", 1002);
            }
        } catch (Throwable e) {
            if (stopRequested) {
                AppState.status = "已停止";
                logMode("用户已停止执行，正常退出");
            } else {
                AppState.status = "异常结束";
                logMode("异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                NotificationHelper.notify(this, "淘金币助手", currentMode.label + "任务异常结束，请查看日志", 1003);
            }
        } finally {
            AppState.running = false;
            automationJobActive = false;
            refreshForegroundNotification();
            if (!OverlaySettings.isEnabled(this)) mainHandler.post(this::stopSelf);
        }
    }

    /** 仅用于原地恢复：面板滚动后标题滚出视口时，凭多条任务行 + 多个动作按钮认定能量列表。 */
    private boolean isResumableEnergyList(OcrEngine.Snapshot snap) {
        if (currentMode != TaskMode.DOUBLE11_ENERGY || snap == null) return false;
        List<TaskParser.TaskItem> tasks = TaskParser.parseEnergyActionable(snap.rows, snap.height);
        return !tasks.isEmpty() && TaskParser.countActionRows(snap.rows) >= 2;
    }

    /**
     * 原地恢复（悬浮窗“重新开始”）：绝不 force-stop 淘宝、不走完整首页重建。
     * 顺序：任务面板 → 阻塞弹窗处理 → 赛车首页开面板 → 子页面有限 Back；
     * 证据不足时明确失败并提示用户手动返回，不瞎点。
     */
    private boolean resumeFromCurrentPage(TaskMode mode) throws Exception {
        ForegroundState fg = currentForeground();
        if (!fg.valid() || !fg.isPackage(TB)) {
            logMode("原地恢复：当前不在淘宝前台（" + fg + "），请手动返回活动页后再试");
            return false;
        }

        for (int round = 0; round < 5 && !stopRequested; round++) {
            OcrEngine.Snapshot snap = ocr.captureTaskList();

            if (isTaskContextForMode(snap) || isResumableEnergyList(snap)) {
                if (mode == TaskMode.DOUBLE11_ENERGY) energyPanelConfirmed = true;
                logMode("原地恢复：已确认" + mode.label + "任务面板，直接进入主循环");
                return true;
            }

            if (dismissBlockingPopupIfPresent(snap, "原地恢复")) {
                sleep(900);
                continue;
            }

            if (looksLikeCoinHomeSnapshot(snap)) {
                logMode("原地恢复：当前在淘金币赛车首页，打开" + mode.label + "任务面板");
                boolean ok = openPanelForMode(mode) && isModeTaskListVisible(mode);
                if (ok && mode == TaskMode.DOUBLE11_ENERGY) energyPanelConfirmed = true;
                return ok;
            }

            logMode("原地恢复：当前页面暂未识别（第 " + (round + 1) + "/5 轮），尝试有限返回");
            shellBack();
            sleep(1000);
            ForegroundState now = currentForeground();
            if (!now.isPackage(TB)) {
                logMode("原地恢复：返回后已离开淘宝，请手动返回活动页后再试");
                return false;
            }
        }
        logMode("原地恢复未能回到" + mode.label + "任务面板；请手动返回活动页，或从 App 主界面完整启动");
        return false;
    }

    private void mainLoop() throws Exception {
        int emptyCount = 0;
        while (!stopRequested) {
            sleep(1800);
            if (stopRequested) return;

            ForegroundState fg = currentForeground();
            if (!fg.isPackage(TB)) {
                logMode("主循环检测到已离开淘宝: " + fg + "，执行保险恢复");
                if (!enterTaskList(true, currentMode)) throw new IllegalStateException("恢复任务列表失败");
                noChangeScrollCount = 0;
                scrollCount = 0;
                emptyCount = 0;
                continue;
            }

            OcrEngine.Snapshot snap = ocr.captureTaskList();
            if (dismissBlockingPopupIfPresent(snap, "任务列表")) {
                sleep(900);
                continue;
            }
            String all = snap.allText();
            if (containsVerification(all)) {
                logMode("检测到淘宝安全验证，本次停止；请手动处理，不自动绕过验证");
                stopRequested = true;
                return;
            }

            // 先强认证“这真的是当前模式的任务面板”，再允许解析/点击。
            // 单独看到一个 (1/3) 之类进度绝不能作为任务页证据，否则商品活动日期会被误当任务。
            if (!isTaskContextForMode(snap)) {
                logMode("淘宝前台但当前画面不具备可信任务面板特征；禁止解析/点击，先等待一帧复核");
                sleep(900);
                OcrEngine.Snapshot contextRetry = ocr.captureTaskList();
                if (!isTaskContextForMode(contextRetry)) {
                    logMode("连续两帧未通过任务页认证，执行返回/完整恢复，避免把商品页当任务页");
                    returnToTaskList(currentMode);
                    if (stopRequested) return;
                    OcrEngine.Snapshot recovered = ocr.captureTaskList();
                    if (!isTaskContextForMode(recovered)) {
                        logMode("常规返回后仍不是可信任务面板，强制重建任务页");
                        if (!enterTaskList(true, currentMode)) throw new IllegalStateException("任务页上下文恢复失败");
                    }
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                    emptyCount = 0;
                    continue;
                }
                snap = contextRetry;
            }

            List<TaskParser.TaskItem> tasks = parseTasksForMode(snap);
            boolean expandedPanelContext = isExpandedPanelForMode(snap);
            if (tasks.isEmpty() && expandedPanelContext) {
                emptyCount = 0;
                logMode("已确认任务面板，但当前视口没有带进度任务；直接进入下滑流程");
            } else if (tasks.isEmpty()) {
                emptyCount++;
                logMode("当前 OCR 为 0 个任务 (" + emptyCount + "/3)，只等待重识别");
                sleep(1500);

                OcrEngine.Snapshot retrySnap = ocr.captureTaskList();
                List<TaskParser.TaskItem> retryTasks = parseTasksForMode(retrySnap);
                if (!retryTasks.isEmpty()) {
                    logMode("重新识别恢复，当前可见任务 " + retryTasks.size() + " 个");
                    emptyCount = 0;
                    continue;
                }

                ForegroundState retryFg = currentForeground();
                if (!retryFg.isPackage(TB)) {
                    logMode("OCR 持续为空且已离开淘宝: " + retryFg);
                    if (!enterTaskList(true, currentMode)) throw new IllegalStateException("OCR 空白后恢复失败");
                    emptyCount = 0;
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                    continue;
                }

                if (emptyCount >= 3) {
                    logMode("连续 3 次 OCR 为空但仍在淘宝容器，执行完整页面恢复作为保险");
                    if (!enterTaskList(true, currentMode)) throw new IllegalStateException("OCR 空白后恢复失败");
                    emptyCount = 0;
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                }
                continue;
            }
            emptyCount = 0;

            logMode("当前屏幕识别到 " + tasks.size() + " 个任务");
            List<TaskParser.TaskItem> ordered = new ArrayList<>(tasks);
            ordered.sort(Comparator.comparingInt(TaskParser::priority).thenComparingInt(t -> t.y));

            TaskParser.TaskItem chosen = null;
            for (TaskParser.TaskItem t : ordered) {
                String rawName = t.name.length() > 40 ? t.name.substring(0, 40) + "…" : t.name;
                String reason = TaskParser.skipReason(t, taskRules);
                if (reason != null) {
                    // 可诊断决策日志：OCR 原名（截断）+ 归一化名 + 命中的具体规则。
                    logMode("跳过[" + reason + "] raw=" + rawName
                            + " norm=" + TaskRuleStore.normalizeKeyword(t.name));
                    continue;
                }
                if (attempted.getOrDefault(stableTaskId(t), 0) >= 2) {
                    logMode("跳过[多次尝试未成功(" + attempted.get(stableTaskId(t)) + "次)]: " + rawName
                            + " norm=" + TaskRuleStore.normalizeKeyword(t.name));
                    continue;
                }
                logMode("候选[通过规则，历史尝试" + attempted.getOrDefault(stableTaskId(t), 0)
                        + "次] raw=" + rawName);
                chosen = t;
                break;
            }

            if (chosen != null) {
                noChangeScrollCount = 0;
                scrollCount = 0;
                executeTask(chosen, snap.width, snap.height);
                continue;
            }

            if (bottomVisibleForMode(snap)) {
                logMode("已到任务列表底部；当前剩余任务均为已完成/黑名单，进入收尾复查/领奖");
                if (finalRewardSweep(currentMode)) {
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                    emptyCount = 0;
                    continue;
                }
                return;
            }

            scrollCount++;
            String before = signature(tasks);
            logMode("当前视口没有可执行任务，使用纯 Shizuku input swipe 向下滚动 (" + scrollCount + "/10)");
            shellSwipeList(snap.width, snap.height);
            sleep(1200);

            List<TaskParser.TaskItem> afterTasks = new ArrayList<>();
            OcrEngine.Snapshot afterSnap = null;
            try {
                afterSnap = ocr.captureTaskList();
                afterTasks = parseTasksForMode(afterSnap);
                if (afterTasks.isEmpty()) {
                    sleep(550);
                    afterSnap = ocr.captureTaskList();
                    afterTasks = parseTasksForMode(afterSnap);
                }
            } catch (Throwable e) {
                logMode("滚动后 OCR 暂时失败: " + e.getMessage());
            }

            ForegroundState afterFg = currentForeground();
            if (!afterFg.isPackage(TB)) {
                logMode("滑动后检测到已离开淘宝: " + afterFg + "，执行完整任务页恢复");
                if (!enterTaskList(true, currentMode)) throw new IllegalStateException("滑动后恢复任务列表失败");
                scrollCount = 0;
                noChangeScrollCount = 0;
                emptyCount = 0;
                continue;
            }

            // Reuse the same post-swipe OCR frame for context certification; v2.0 used to take
            // an extra full screenshot here, which made every list swipe unnecessarily slow.
            try {
                if (afterSnap != null && !isTaskContextForMode(afterSnap)) {
                    logMode("滚动后任务页上下文丢失，停止解析并执行恢复");
                    if (!enterTaskList(true, currentMode)) throw new IllegalStateException("滚动后任务页上下文恢复失败");
                    scrollCount = 0;
                    noChangeScrollCount = 0;
                    emptyCount = 0;
                    continue;
                }
            } catch (Throwable e) {
                logMode("滚动后任务页认证暂时失败: " + e.getMessage());
            }

            if (afterTasks.isEmpty()) {
                // 与最终 Python 版一致：仍在 TMS 容器时，不把一次空 OCR 当成“没有滚动”。
                logMode("滚动后 OCR 暂时为空；不补点击、不后退，下一轮重新确认");
                noChangeScrollCount = 0;
            } else {
                String after = signature(afterTasks);
                if (after.equals(before)) {
                    noChangeScrollCount++;
                    logMode("滚动后任务未变化 (" + noChangeScrollCount + "/2)");
                } else {
                    noChangeScrollCount = 0;
                    logMode("滚动后任务已变化");
                }
            }

            if (noChangeScrollCount >= 2) {
                logMode("连续两次滚动后任务内容未变化，判断已到底，进入收尾复查/领奖");
                if (finalRewardSweep(currentMode)) {
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                    emptyCount = 0;
                    continue;
                }
                return;
            }
            if (scrollCount >= 10) {
                logMode("连续多次滚动后仍未找到可执行任务，进入收尾复查/领奖");
                if (finalRewardSweep(currentMode)) {
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                    emptyCount = 0;
                    continue;
                }
                return;
            }
        }
    }

    /** 按当前模式选择任务解析策略：能量模式必须使用带动作按钮证据的严格解析。 */
    private List<TaskParser.TaskItem> parseTasksForMode(OcrEngine.Snapshot snap) {
        if (currentMode == TaskMode.DOUBLE11_ENERGY) {
            return TaskParser.parseEnergyActionable(snap.rows, snap.height);
        }
        return TaskParser.parse(snap.rows, snap.height);
    }

    /**
     * 按当前模式认证“当前画面确实是本模式的任务面板”。
     * 能量模式额外支持“有状态的已进入任务列表”上下文（标题滚出视口后仍可认证）。
     */
    private boolean isTaskContextForMode(OcrEngine.Snapshot snap) {
        if (currentMode == TaskMode.DOUBLE11_ENERGY) {
            return TaskParser.isTrustedEnergyTaskPanel(snap.rows, snap.height)
                    || isEnergyTaskListContext(snap);
        }
        return TaskParser.isTrustedTaskContext(snap.rows, snap.height);
    }

    /** 按当前模式判断“已进入可执行任务面板（即使当前视口暂无带进度任务）”。 */
    private boolean isExpandedPanelForMode(OcrEngine.Snapshot snap) {
        if (currentMode == TaskMode.DOUBLE11_ENERGY) {
            return TaskParser.isTrustedEnergyTaskPanel(snap.rows, snap.height)
                    || isEnergyTaskListContext(snap);
        }
        return TaskParser.isExpandedTaskPanelView(snap.rows, snap.height);
    }

    /** 按当前模式判断是否已滚动到任务列表底部。 */
    private boolean bottomVisibleForMode(OcrEngine.Snapshot snap) {
        if (currentMode == TaskMode.DOUBLE11_ENERGY) {
            return TaskParser.energyBottomVisible(snap.rows);
        }
        return TaskParser.bottomVisible(snap.rows);
    }

    /**
     * 能量模式“已进入可信任务列表”的有状态上下文判断。
     *
     * <p>面板已确认打开并滚动后，顶部“做任务赚能量”标题/“累计任务奖励”可能离开 OCR
     * 视口；不能单靠标题不见了就判定失败。此方法仅在本次运行已成功认证过能量面板
     * （energyPanelConfirmed）的前提下，结合当前可见的多条「进度 + 任务动作按钮」同行
     * 证据与前台包名判断仍处于列表。绝不因任意 0/1 或单个“能量”字样通过。</p>
     */
    private boolean isEnergyTaskListContext(OcrEngine.Snapshot snap) {
        if (snap == null || !energyPanelConfirmed) return false;
        if (!TB.equals(currentPackage())) return false;
        List<TaskParser.TaskItem> tasks = TaskParser.parseEnergyActionable(snap.rows, snap.height);
        int actionRows = TaskParser.countActionRows(snap.rows);
        return !tasks.isEmpty() && actionRows >= 2;
    }

    /**
     * 稳定任务身份，用于 attempted 重试计数。默认返回任务名（含进度，进度增长即新身份，
     * 保证 (0/5)→(1/5) 可继续执行）。对“淘金币省钱等级考试”这类 OCR 易抖动的任务
     * （“等级”→“等圾”）返回确定性身份，避免同一题因文字抖动被当作新任务反复执行。
     */
    private String stableTaskId(TaskParser.TaskItem t) {
        if (isCoinExamTask(t.name)) return "exam:coin_saving_exam";
        return t.name;
    }

    /** 受限匹配“淘金币省钱等级考试”及其 OCR 变体（等级/等圾/等級、省钱/省线、钱/錢/线/線）。 */
    private boolean isCoinExamTask(String name) {
        String norm = TaskRuleStore.normalizeKeyword(name);
        if (!norm.contains("考试")) return false;
        // 局部繁体/形近归一，覆盖 等級/省线/錢 等 OCR 抖动。
        String n = norm.replace('級', '级').replace('錢', '钱').replace('線', '线');
        if (n.contains("省钱") || n.contains("省线")) return true;
        return n.contains("等级") || n.contains("等圾");
    }

    /** 来源任务是否为考试类（标题含“考试”二字），用于限定宽松答题认证路径。 */
    private boolean isExamLikeTask(String name) {
        return name != null && TaskRuleStore.normalizeKeyword(name).contains("考试");
    }

    private void executeTask(TaskParser.TaskItem task, int width, int height) throws Exception {
        attempted.put(stableTaskId(task), attempted.getOrDefault(stableTaskId(task), 0) + 1);
        executedCount++;
        AppState.status = "执行: " + task.name;
        boolean externalTask = TaskParser.external(task, taskRules);
        boolean searchTask = TaskParser.isSearch(task, taskRules);
        // 能量模式受限搜索候选：OCR 截断的“你喜欢的宝贝(0/5)”仍应按搜索流程执行。
        boolean energySearchCandidate = currentMode == TaskMode.DOUBLE11_ENERGY
                && TaskParser.isEnergySearchCandidate(task);
        if (energySearchCandidate && !searchTask) {
            logMode("SEARCH_CLASSIFY raw=" + task.name
                    + " reason=energy_title_variant confidence=candidate");
        }
        searchTask = searchTask || energySearchCandidate;
        logMode("准备执行任务[" + TaskParser.displayMode(task, taskRules) + "]: " + task.name);

        int x = (int) (width * 0.875f);
        shellTap(x, task.y);
        sleep(3500);
        if (stopRequested) return;

        // 答题页双层认证分流：黑名单已在候选循环 skip（如用户加了“考试”等词会被拦）。
        // 考试来源任务即使未识别出答题页，也绝不回退为 22 秒普通浏览。
        boolean sourceExam = isExamLikeTask(task.name);
        QuizPageInfo quiz = detectQuizPageWithRetry(task, sourceExam ? 3 : 2);
        if (quiz != null) {
            logMode("分类：" + task.name + " -> QUIZ（黑名单未命中，method=" + quiz.method + "）");
            handleQuizTask(quiz);
            if (stopRequested) return;
            returnToTaskList(currentMode);
            if (!stopRequested) AppState.status = "识别任务中";
            return;
        }
        if (sourceExam) {
            logMode("QUIZ_UNRECOGNISED source=" + task.name
                    + "（考试任务未识别出答题页，不执行 22 秒浏览，安全返回任务列表）");
            returnToTaskList(currentMode);
            if (!stopRequested) AppState.status = "识别任务中";
            return;
        }

        if (searchTask) {
            SearchResult result = handleSearchTask();
            if (stopRequested) return;
            if (result == SearchResult.FAILED) {
                logMode("搜索任务未能打开结果页；直接返回任务列表，不在搜索入口页空滑");
                returnToTaskList(currentMode);
                if (!stopRequested) AppState.status = "识别任务中";
                return;
            }
            if (result == SearchResult.HANDLED_AND_BROWSED) {
                // 发现页已点击推荐并浏览约 8 秒，不再额外 22 秒浏览，逐层返回任务列表。
                logMode("搜索发现页已浏览完成，不额外等待，逐层返回任务列表");
                returnToTaskList(currentMode);
                if (!stopRequested) AppState.status = "识别任务中";
                return;
            }
            // SUBMITTED_NEEDS_BROWSE：输入关键词已提交，继续外层 22 秒浏览。
        }

        if (stopRequested) return;
        browseCurrentTask(width, height, externalTask);
        if (stopRequested) return;
        returnToTaskList(currentMode);
        if (!stopRequested) AppState.status = "识别任务中";
    }

    private void browseCurrentTask(int width, int height, boolean external) throws Exception {
        // 金币/能量普通浏览保持原有约 22 秒（覆盖 5s/15s 任务），不做回归性改动。
        browseFor(width, height, 22_000L, external);
    }

    /** 通用浏览滑动；时长可调（普通浏览 22 秒，搜索发现中间页流程约 8 秒）。 */
    private void browseFor(int width, int height, long durationMs, boolean external) throws Exception {
        final long startedAt = SystemClock.elapsedRealtime();
        long lastDialogCheckAt = 0L;

        logMode(external ? "跨应用任务：等待约 " + (durationMs / 1000) + " 秒，不在外部 App 内主动滑动"
                : "浏览任务：按脚本逻辑浏览约 " + (durationMs / 1000) + " 秒");

        while (!stopRequested && SystemClock.elapsedRealtime() - startedAt < durationMs) {
            // 纯 OCR 模式：每隔约 3 秒检查一次“浏览器打开”系统弹窗。
            long now = SystemClock.elapsedRealtime();
            if (now - lastDialogCheckAt >= 3000L) {
                lastDialogCheckAt = now;
                if (cancelBrowserOpenDialogIfPresent()) break;
            }

            ForegroundState fg = currentForeground();
            if (external || (!fg.isPackage(TB) && !fg.isPackage(TMALL))) {
                // 与 Python task_loop 一致：跨应用任务只等待，不对外部 App 做机械滑动。
                sleep(1000);
                continue;
            }

            int startX = randomBetween(Math.max(1, width / 6), Math.max(2, width / 2));
            int startY = randomBetween(Math.max(1, height / 2), Math.max(2, height - height / 4));
            int endX = randomBetween(Math.max(1, startX - Math.max(20, width / 14)), Math.max(2, startX));
            int endYUpper = Math.max(220, startY - 300);
            int endY = randomBetween(200, endYUpper);
            int verticalDistance = Math.abs(startY - endY);
            int duration = verticalDistance > 500
                    ? randomBetween(400, 1000)
                    : randomBetween(200, 500);

            ShizukuShell.Result r = ShizukuShell.exec(
                    "input swipe " + startX + " " + startY + " " + endX + " " + endY + " " + duration
            );
            if (!r.ok()) logMode("浏览滑动失败: " + r.output);
            sleep(800 + random.nextInt(1201));
        }
    }

    private int randomBetween(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        if (hi <= lo) return lo;
        return lo + random.nextInt(hi - lo + 1);
    }

    /** 是否为“历史搜索/搜索发现”搜索中间页（双11搜索任务的必经中间页）。 */
    private boolean isSearchDiscoveryPage(OcrEngine.Snapshot snap) {
        if (snap == null) return false;
        String all = normalizeOcr(snap.allText());
        return all.contains("搜索发现") || all.contains("历史搜索");
    }

    private boolean isUnsafeSearchDiscoveryText(String t) {
        String[] unsafe = {"删除", "清空", "反馈", "客服", "购物车", "结算", "支付",
                "收藏", "加购", "下单", "搜索发现", "历史搜索"};
        for (String w : unsafe) {
            if (t.contains(w)) return true;
        }
        return false;
    }

    /**
     * 在搜索发现区定位一个安全的推荐项。优先元素级目标——两列卡片会被行级 OCR
     * 合并成一行，点 union 中心会落在两卡之间的空隙。排除 清空/删除/反馈/支付 等
     * 危险词与“搜索发现/历史搜索”标题锚本身；绝不点垃圾桶/清空历史。
     */
    private int[] findSearchDiscoveryTarget(OcrEngine.Snapshot snap) {
        if (snap == null) return null;
        int anchorBottom = -1;
        for (OcrEngine.OcrRow row : snap.rows) {
            if (normalizeOcr(row.text).contains("搜索发现")) {
                anchorBottom = row.bounds.bottom;
                break;
            }
        }
        if (anchorBottom < 0) {
            for (OcrEngine.OcrRow row : snap.rows) {
                if (normalizeOcr(row.text).contains("历史搜索")) {
                    anchorBottom = row.bounds.bottom;
                    break;
                }
            }
        }
        if (anchorBottom < 0) return null;
        int minY = anchorBottom + 8;
        int maxY = (int) (snap.height * 0.92f);

        if (snap.elements != null) {
            for (OcrEngine.OcrRow e : snap.elements) {
                int cy = e.bounds.centerY();
                if (cy < minY || cy > maxY) continue;
                String t = normalizeOcr(e.text);
                if (t.length() < 2 || t.length() > 20) continue;
                if (isUnsafeSearchDiscoveryText(t)) continue;
                return new int[]{e.bounds.centerX(), cy};
            }
        }
        for (OcrEngine.OcrRow row : snap.rows) {
            int cy = row.bounds.centerY();
            if (cy < minY || cy > maxY) continue;
            String t = normalizeOcr(row.text);
            if (t.length() < 2 || t.length() > 20) continue;
            if (isUnsafeSearchDiscoveryText(t)) continue;
            return new int[]{Math.max(24, Math.min(snap.width - 24, row.bounds.centerX())), cy};
        }
        return null;
    }

    /**
     * 能量搜索任务专用：识别“历史搜索/搜索发现”中间页，点击安全推荐项并验证页面
     * 切换，随后浏览约 8 秒（5 秒任务 + 缓冲）。返回 Boolean.TRUE/FALSE 表示已按
     * 该页面处理（成功/失败）；null 表示页面类型不匹配或无法安全定位，调用方回退
     * 到原有输入关键词流程。返回任务面板/进度复查由 executeTask 的
     * returnToTaskList 与主循环完成。
     */
    private Boolean tryHandleSearchDiscoveryPage() throws Exception {
        for (int i = 1; i <= 3 && !stopRequested; i++) {
            OcrEngine.Snapshot snap;
            try {
                snap = ocr.captureSearchPage();
            } catch (Throwable e) {
                logMode("搜索中间页 OCR 失败: " + e.getMessage());
                sleep(700);
                continue;
            }
            if (!isSearchDiscoveryPage(snap)) {
                if (i >= 2) return null;   // 连续两帧都不是中间页，交给原流程
                sleep(800);
                continue;
            }

            String act = currentForeground().activityName;
            if (act != null && act.contains(".")) act = act.substring(act.lastIndexOf('.') + 1);
            logMode("SEARCH_CONTEXT activity=" + act
                    + " discovery=true（历史搜索/搜索发现，不触碰清空/删除历史）");
            int[] target = findSearchDiscoveryTarget(snap);
            if (target == null) {
                logMode("搜索发现页未能安全定位推荐项；回退到输入关键词流程");
                return null;
            }
            String before = compactPanelSignature(snap);
            shellTap(target[0], target[1]);
            logMode("已点击搜索发现推荐项 (" + target[0] + ", " + target[1] + ")");
            sleep(2600);
            if (stopRequested) return false;

            OcrEngine.Snapshot after = null;
            try {
                after = ocr.captureSearchPage();
            } catch (Throwable e) {
                logMode("点击推荐项后 OCR 暂时失败，按已进入浏览页处理: " + e.getMessage());
            }
            boolean left = after == null
                    || (!isSearchDiscoveryPage(after) && !compactPanelSignature(after).equals(before));
            if (!left) {
                logMode("点击推荐项后页面未切换，本次按失败记录，返回任务列表复查进度");
                return false;
            }
            logMode("已进入推荐/结果页，开始约 8 秒安全浏览（少量滑动）");
            browseFor(after == null ? snap.width : after.width,
                    after == null ? snap.height : after.height, 8_000L, false);
            return !stopRequested;
        }
        return null;
    }

    /**
     * 搜一搜任务：纯 OCR 定位搜索栏，Shizuku 负责聚焦、输入和提交。
     * 不读取 EditText 或 WebView UI tree；搜索页只以截图 OCR 与 Shizuku 输入为准。
     */
    /** 搜索任务处理结果语义：已浏览 / 需外层继续浏览 / 失败。 */
    private enum SearchResult {
        HANDLED_AND_BROWSED,    // 发现页已点击推荐并完成约 8 秒浏览，外层无需再浏览
        SUBMITTED_NEEDS_BROWSE, // 输入关键词已提交，外层继续 22 秒浏览
        FAILED                  // 无法认证/打开，安全返回任务列表
    }

    private SearchResult handleSearchTask() throws Exception {
        AppState.status = "搜索任务：OCR定位搜索框";

        // 能量模式：优先处理“历史搜索/搜索发现”中间页（点推荐项 → 浏览 → 逐步返回）。
        if (currentMode == TaskMode.DOUBLE11_ENERGY) {
            Boolean discovery = tryHandleSearchDiscoveryPage();
            if (discovery != null) {
                return discovery ? SearchResult.HANDLED_AND_BROWSED : SearchResult.FAILED;
            }
        }

        for (int attempt = 1; attempt <= 8 && !stopRequested; attempt++) {
            try {
                OcrEngine.Snapshot snap = ocr.captureSearchPage();
                SearchVisualTarget target = findSearchVisualTarget(snap);
                if (target != null) {
                    logMode("OCR 已定位新版搜索页，搜索栏 y=" + target.inputY + "，使用 Shizuku 输入");
                    if (submitSearchByVisualTarget(target)) {
                        return SearchResult.SUBMITTED_NEEDS_BROWSE;
                    }
                    if (stopRequested) return SearchResult.FAILED;
                } else if (attempt == 1 || attempt == 4 || attempt == 8) {
                    logMode("搜索页 OCR 暂未定位输入栏 (" + attempt + "/8): " + summarizeSearchOcr(snap));
                }
            } catch (Throwable e) {
                if (attempt == 1 || attempt == 8) {
                    logMode("搜索页 OCR 失败 (" + attempt + "/8): " + e.getMessage());
                }
            }
            sleep(650);
        }

        if (!stopRequested) logMode("未能通过 OCR 定位/提交新版搜索框");
        return SearchResult.FAILED;
    }

    private boolean submitSearchByVisualTarget(SearchVisualTarget target) throws Exception {
        if (target == null || stopRequested) return false;

        shellTap(target.inputX, target.inputY);
        sleep(650);
        if (stopRequested) return false;

        String keyword = SEARCH_KEYS_ASCII.get(random.nextInt(SEARCH_KEYS_ASCII.size()));
        // 先尝试清掉可能残留的旧关键词，再输入纯 ASCII，避免依赖任何 EditText/IME API。
        ShizukuShell.exec("input keyevent KEYCODE_MOVE_END");
        ShizukuShell.exec("i=0; while [ $i -lt 24 ]; do input keyevent KEYCODE_DEL; i=$((i+1)); done");
        ShizukuShell.Result typed = ShizukuShell.exec("input text " + keyword);
        if (!typed.ok()) {
            logMode("Shizuku 向搜索框输入关键词失败: " + typed.output);
            return false;
        }
        logMode("已用 Shizuku 向 OCR 定位的搜索框输入关键词: " + keyword);

        sleep(650);
        if (stopRequested) return false;
        shellTap(target.searchX, target.searchY);
        logMode("通过 OCR 几何位置点击右侧“搜索”按钮: (" + target.searchX + ", " + target.searchY + ")");

        AppState.status = "搜索任务：已提交 " + keyword;
        sleep(1800);
        if (stopRequested) return false;

        try {
            OcrEngine.Snapshot verify = ocr.captureSearchPage();
            String verifyText = normalizeOcr(verify.allText());
            if (verifyText.contains("搜索宝贝") && !verifyText.toLowerCase().contains(keyword.toLowerCase())) {
                logMode("点击搜索后仍检测到空白‘搜索宝贝’占位符，判定本次搜索未提交");
                return false;
            }
        } catch (Throwable e) {
            logMode("搜索提交后的 OCR 确认暂时失败，继续执行: " + e.getMessage());
        }

        sleep(1200);
        return !stopRequested;
    }

    private SearchVisualTarget findSearchVisualTarget(OcrEngine.Snapshot snap) {
        if (snap == null) return null;
        String all = normalizeOcr(snap.allText());
        boolean pageEvidence = all.contains("搜索有福利")
                || all.contains("搜索后浏览")
                || all.contains("搜索宝贝")
                || fuzzyContains(all, "搜索宝贝", 1);
        if (!pageEvidence) return null;

        int minY = (int) (snap.height * 0.09f);
        int maxY = (int) (snap.height * 0.20f);
        int y = -1;
        for (OcrEngine.OcrRow row : snap.rows) {
            int cy = row.bounds.centerY();
            if (cy < minY || cy > maxY) continue;
            String t = normalizeOcr(row.text);
            if (t.contains("搜索宝贝") || fuzzyContains(t, "搜索宝贝", 1)
                    || (t.contains("搜索") && row.bounds.width() > snap.width * 0.35f)) {
                y = cy;
                break;
            }
        }

        // 某些 OCR 帧只识别到“搜索有福利/搜索后浏览”，但漏掉 placeholder。
        // 已有页面证据时，搜索栏在新版页面顶部固定带中，使用比例 y 作为有限兜底。
        if (y < 0) y = (int) (snap.height * 0.132f);

        int inputX = (int) (snap.width * 0.36f);
        int searchX = (int) (snap.width * 0.895f);
        return new SearchVisualTarget(inputX, y, searchX, y);
    }

    private String summarizeSearchOcr(OcrEngine.Snapshot snap) {
        if (snap == null) return "<空截图>";
        StringBuilder sb = new StringBuilder();
        for (OcrEngine.OcrRow row : snap.rows) {
            int y = row.bounds.centerY();
            if (y > snap.height * 0.34f) break;
            String t = normalizeOcr(row.text);
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t);
            if (sb.length() > 180) break;
        }
        return sb.length() == 0 ? "<顶部无文字>" : sb.toString();
    }

    private static final class SearchVisualTarget {
        final int inputX;
        final int inputY;
        final int searchX;
        final int searchY;

        SearchVisualTarget(int inputX, int inputY, int searchX, int searchY) {
            this.inputX = inputX;
            this.inputY = inputY;
            this.searchX = searchX;
            this.searchY = searchY;
        }
    }

    /** 淘金币答题页识别结果：选项坐标、是否已出反馈、是否出现领奖/返回提示。 */
    private static final class QuizPageInfo {
        final Map<String, int[]> options;
        final boolean feedbackVisible;
        final boolean rewardVisible;
        /** 题型标签：单选/多选，空串表示 OCR 未识别出题型词。 */
        final String choiceType;
        /** 认证路径：title=严格标题认证，exam_context_structural=考试来源+结构认证。 */
        final String method;

        QuizPageInfo(Map<String, int[]> options, boolean feedbackVisible, boolean rewardVisible,
                     String choiceType, String method) {
            this.options = options;
            this.feedbackVisible = feedbackVisible;
            this.rewardVisible = rewardVisible;
            this.choiceType = choiceType == null ? "" : choiceType;
            this.method = method == null ? "title" : method;
        }
    }

    /** 答题页扫描中间结果，供认证与诊断复用。 */
    private static final class QuizScan {
        boolean titleExact;
        String choiceType = "";
        Map<String, int[]> options = new HashMap<>();
        boolean submitVisible;
        boolean feedbackVisible;
        boolean rewardVisible;
        boolean sourceExam;
    }

    /** 扫描当前截图，收集答题页证据（标题/题型/选项/提交按钮/反馈），不依赖单一标题。 */
    private QuizScan scanQuizPage(OcrEngine.Snapshot snap, TaskParser.TaskItem sourceTask) {
        QuizScan scan = new QuizScan();
        if (snap == null) return scan;
        String all = normalizeOcr(snap.allText());
        scan.titleExact = all.contains("淘金币答题") || all.contains("每日答题");
        scan.choiceType = all.contains("多选题") || all.contains("多选")
                ? "多选"
                : (all.contains("单选题") || all.contains("单选") ? "单选" : "");
        scan.sourceExam = sourceTask != null && isExamLikeTask(sourceTask.name);

        int titleBottom = -1;
        int submitY = -1;
        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (titleBottom < 0 && (t.contains("淘金币答题") || t.contains("每日答题"))) {
                titleBottom = row.bounds.bottom;
            }
            if (submitY < 0 && t.contains("我选好了")) {
                submitY = row.bounds.centerY();
            }
        }
        scan.submitVisible = submitY > 0;

        // 1) 元素级单字母 A/B/C/D（最可靠）
        if (snap.elements != null) {
            for (OcrEngine.OcrRow e : snap.elements) {
                String t = normalizeOcr(e.text);
                if (t.length() != 1 || "ABCD".indexOf(t.charAt(0)) < 0) continue;
                int cy = e.bounds.centerY();
                if (titleBottom > 0 && cy < titleBottom) continue;
                if (submitY > 0 && cy > submitY) continue;
                scan.options.putIfAbsent(t, new int[]{e.bounds.centerX(), cy});
            }
        }
        // 2) 行级：以 A/B/C/D 开头（如“A1999年”合并文本）。
        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (t.isEmpty()) continue;
            char c = t.charAt(0);
            if (!(c == 'A' || c == 'B' || c == 'C' || c == 'D')) continue;
            String letter = String.valueOf(c);
            if (scan.options.containsKey(letter)) continue;
            int cy = row.bounds.centerY();
            if (titleBottom > 0 && cy < titleBottom) continue;
            if (submitY > 0 && cy > submitY) continue;
            scan.options.put(letter, new int[]{row.bounds.centerX(), cy});
        }

        scan.feedbackVisible = all.contains("正确答案") && all.contains("你的答案");
        scan.rewardVisible = all.contains("已成功领取奖励") || all.contains("回到主页");
        return scan;
    }

    /**
     * 答题页双层认证：
     * 路径 A（严格）：标题“淘金币答题/每日答题”命中即可进入，沿用原行为。
     * 路径 B（考试来源限定结构认证）：来源任务含“考试”，且当前页同时出现 A、B 两个
     *   有效选项 + “我选好了”提交控件（或已出现领奖反馈），即使顶端艺术字标题漏识别
     *   也进入。仅凭任意 A/B 字母绝不在商品页/搜索页触发；黑名单已在候选循环拦截。
     */
    private QuizPageInfo buildQuizPageInfo(OcrEngine.Snapshot snap, TaskParser.TaskItem sourceTask) {
        QuizScan scan = scanQuizPage(snap, sourceTask);
        if (scan.titleExact) {
            return new QuizPageInfo(scan.options, scan.feedbackVisible, scan.rewardVisible,
                    scan.choiceType, "title");
        }
        if (scan.sourceExam) {
            boolean hasA = scan.options.containsKey("A");
            boolean hasB = scan.options.containsKey("B");
            boolean structural = hasA && hasB && scan.submitVisible;
            boolean feedback = scan.feedbackVisible || scan.rewardVisible;
            if (structural || (hasA && feedback)) {
                return new QuizPageInfo(scan.options, scan.feedbackVisible, scan.rewardVisible,
                        scan.choiceType, "exam_context_structural");
            }
        }
        return null;
    }

    private QuizPageInfo detectQuizPageWithRetry(TaskParser.TaskItem sourceTask, int maxAttempts) throws Exception {
        for (int i = 1; i <= maxAttempts && !stopRequested; i++) {
            OcrEngine.Snapshot snap = null;
            try {
                snap = ocr.captureQuizPage();
            } catch (Throwable e) {
                logMode("答题页 OCR 失败 (" + i + "/" + maxAttempts + "): " + e.getMessage());
            }
            if (snap != null) {
                QuizPageInfo quiz = buildQuizPageInfo(snap, sourceTask);
                if (quiz != null) {
                    logMode("QUIZ_SCAN pass=" + i
                            + " titleExact=" + quiz.method.equals("title")
                            + " choiceType=" + (quiz.choiceType.isEmpty() ? "?" : quiz.choiceType)
                            + " options=[" + joinOptions(quiz.options) + "]"
                            + " sourceExam=" + (sourceTask != null && isExamLikeTask(sourceTask.name))
                            + " method=" + quiz.method);
                    return quiz;
                }
                if (i == maxAttempts) {
                    QuizScan scan = scanQuizPage(snap, sourceTask);
                    logMode("QUIZ_DETECT_FAILED titleExact=" + scan.titleExact
                            + " choiceType=" + (scan.choiceType.isEmpty() ? "?" : scan.choiceType)
                            + " options=[" + joinOptions(scan.options) + "]"
                            + " submit=" + scan.submitVisible
                            + " sourceExam=" + scan.sourceExam);
                }
            }
            if (i < maxAttempts) sleep(1000);
        }
        return null;
    }

    /** 选项字母列表转成可读日志（A,B,C）。 */
    private String joinOptions(Map<String, int[]> options) {
        StringBuilder sb = new StringBuilder();
        for (String letter : new String[]{"A", "B", "C", "D"}) {
            if (options.containsKey(letter)) {
                if (sb.length() > 0) sb.append(',');
                sb.append(letter);
            }
        }
        return sb.length() == 0 ? "(无)" : sb.toString();
    }

    /**
     * 答题专用流程（首版固定选 A，兼容单选/多选、2–4 个选项；多选也只选 A 后提交。
     * 不联网找答案，只按页面真实反馈决定后续动作，绝不重复作答）。
     */
    private void handleQuizTask(QuizPageInfo quiz) throws Exception {
        logMode("答题页认证 method=" + quiz.method + " 题型="
                + (quiz.choiceType.isEmpty() ? "未识别" : quiz.choiceType)
                + " 选项=" + joinOptions(quiz.options));

        if (quiz.rewardVisible) {
            clickQuizReturnHome();
            return;
        }

        int[] optionA = quiz.options.get("A");
        if (optionA == null) {
            logMode("答题页缺 A 选项，安全退出该项（不转为浏览），返回任务列表");
            return;
        }
        shellTap(optionA[0], optionA[1]);
        logMode("QUIZ_TAP option=A (" + optionA[0] + "," + optionA[1] + ")");
        sleep(1200);
        if (stopRequested) return;

        OcrEngine.Snapshot afterSelect = ocr.captureQuizPage();
        int[] submit = findTextPoint(afterSelect, "我选好了", false);
        if (submit != null) {
            shellTap(submit[0], submit[1]);
            logMode("QUIZ_SUBMIT button=我选好了");
            sleep(2200);
        } else {
            logMode("未看到“我选好了”提交按钮，按页面当前状态复查反馈（不无限点击）");
        }
        if (stopRequested) return;

        QuizScan feedback = scanQuizPage(ocr.captureQuizPage(), null);
        if (feedback.rewardVisible) {
            logMode("QUIZ_RESULT reward=confirmed（已成功领取奖励 / 出现返回提示）");
            clickQuizReturnHome();
        } else {
            logMode("QUIZ_RESULT reward=unconfirmed，安全返回任务列表（不重复作答）");
        }
    }

    /** 点击“已成功领取奖励 回到主页”按钮；定位不到时仅记录，不盲点坐标。 */
    private void clickQuizReturnHome() throws Exception {
        OcrEngine.Snapshot snap = ocr.captureQuizPage();
        int[] home = findTextPoint(snap, "回到主页", false);
        if (home == null) home = findTextPoint(snap, "已成功领取奖励", false);
        if (home != null) {
            shellTap(home[0], home[1]);
            logMode("已点击“回到主页”返回");
            sleep(2200);
        } else {
            logMode("未定位到“回到主页”按钮，交由返回流程恢复任务列表");
        }
    }

    private boolean enterTaskList(boolean cleanStart, TaskMode mode) throws Exception {
        currentMode = mode;
        AppState.status = "进入" + mode.label;

        // 完整恢复流程只在需要时清理并启动一次淘宝。
        // 进入“领淘金币”后不要求 UC WebView 暴露任何 UI 节点，只按真实截图继续
        // 下一阶段，避免明明已到淘金币首页却被误判为失败。
        if (cleanStart) {
            energyPanelConfirmed = false;
            ShizukuShell.exec("am force-stop " + TB);
        }
        launchTaobao();
        sleep(2600);

        for (int attempt = 1; attempt <= 2 && !stopRequested; attempt++) {
            logMode("进入任务页尝试 " + attempt + "/2");

            if (!TB.equals(currentPackage())) {
                logMode("当前不在淘宝，重新拉起淘宝，但不重复 force-stop");
                launchTaobao();
                sleep(1800);
            } else if (attempt > 1) {
                logMode("继续在当前淘宝页面重试，不重启 App");
                sleep(900);
            }

            // One fast navigation frame handles all three states: already in task panel,
            // already on coin home, or still on Taobao home with the coin entry visible.
            // 两种模式都先进入“淘金币首页（赛车首页）”，再按模式进入对应任务面板。
            boolean atCoinHome = openCoinHomeFromTaobaoHome();
            if (!atCoinHome) {
                logMode("未能打开淘金币入口");
                continue;
            }

            if (mode == TaskMode.DOUBLE11_ENERGY) {
                logMode("已到淘金币首页，定位右侧“赚能量”入口");
                if (openEnergyPanel()) {
                    logMode("已进入能量任务面板");
                    return true;
                }
            } else {
                logMode("已到淘金币首页，打开金币任务面板（季节首页优先左侧去赚金币，兼容旧版）");
                if (openTaskPanel()) {
                    logMode("已进入淘金币任务列表");
                    return true;
                }
            }

            logMode("当前页面暂未打开/确认任务面板；保持当前页面重试，不重启淘宝");
        }
        return false;
    }

    private boolean openCoinHomeFromTaobaoHome() throws Exception {
        logMode("检查当前页面：快速 OCR 确认是否已在淘金币首页，否则寻找‘领淘金币’入口");

        for (int i = 1; i <= 3 && !stopRequested; i++) {
            OcrEngine.Snapshot snap;
            try {
                snap = ocr.captureNavigation();
            } catch (Throwable e) {
                logMode("导航 OCR 失败: " + e.getMessage());
                sleep(500);
                continue;
            }

            if (dismissBlockingPopupIfPresent(snap, "淘宝/淘金币首页")) {
                sleep(850);
                continue;
            }

            if (taskPanelVisibleForMode(snap)) {
                logMode("OCR 已确认任务弹窗已经打开，不重复走首页导航");
                return true;
            }
            if (looksLikeCoinHomeSnapshot(snap)) {
                logMode("OCR 已确认当前就在淘金币首页，不重复走首页导航");
                return true;
            }

            int[] p = findTextPoint(snap, "领淘金币", false);
            if (p == null) p = findTextPoint(snap, "淘金币", false);
            if (p != null) {
                shellTap(p[0], p[1]);
                logMode("通过宽范围 OCR 点击淘宝首页淘金币入口: (" + p[0] + ", " + p[1] + ")");

                // Do not assume a tap succeeded. Shortcut labels from multiple icons are sometimes
                // merged into one OCR row; the next frame is the source of truth. If we are still
                // on Taobao home, retry with a fresh frame instead of treating the product feed as
                // the coin page.
                sleep(1900);
                boolean confirmed = false;
                for (int confirm = 1; confirm <= 2 && !stopRequested; confirm++) {
                    try {
                        OcrEngine.Snapshot afterTap = ocr.captureNavigation();
                        if (taskPanelVisibleForMode(afterTap) || looksLikeCoinHomeSnapshot(afterTap)) {
                            confirmed = true;
                            break;
                        }
                    } catch (Throwable ignored) {}
                    if (confirm < 2) sleep(650);
                }
                if (confirmed) {
                    logMode("已确认进入淘金币页面");
                    return true;
                }
                logMode("点击淘金币入口后仍未确认进入；重新定位入口，不把淘宝商品首页误当淘金币页");
                continue;
            }
            logMode("第 " + i + "/3 次宽范围 OCR 未找到淘金币入口");
            sleep(500);
        }

        logMode("OCR 未找到淘宝首页淘金币入口");
        return false;
    }

    private void launchTaobao() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TB);
        ComponentName component = launch == null ? null : launch.getComponent();
        if (component == null) {
            logMode("无法解析淘宝启动 Activity");
            return;
        }
        String flat = component.flattenToShortString();
        ShizukuShell.Result r = ShizukuShell.exec("am start -n " + flat + " >/dev/null");
        if (!r.ok()) logMode("Shizuku 拉起淘宝失败: " + r.output);
    }

    private boolean openTaskPanel() throws Exception {
        // v2.1 fast path: capture the upper screen once and reuse it for panel/sign-in/more-coins
        // decisions instead of doing 2~3 full OCR passes before the first click.
        coinHomeGeometryFallbackUsed = false;

        OcrEngine.Snapshot first = null;
        try {
            first = ocr.captureNavigation();
        } catch (Throwable e) {
            logMode("淘金币首页快速 OCR 失败: " + e.getMessage());
        }

        if (first != null && dismissBlockingPopupIfPresent(first, "淘金币首页")) {
            sleep(850);
            try { first = ocr.captureNavigation(); } catch (Throwable ignored) {}
        }

        if (first != null && taskPanelVisibleInSnapshot(first)) {
            return ensureTaskListExpanded();
        }

        if (first != null) {
            // 新季节双十一赛车首页：左下“去赚金币”优先；旧版中央“赚更多金币”仅作 fallback。
            // 只有季节首页特征成立才走左侧入口，避免在新首页中央盲点装饰。
            if (isSeasonalCoinHome(first)) {
                if (openSeasonalCoinPanel(first)) return true;
                return false;
            }

            int[] sign = findOcrTextCenter(first, "签到领金币");
            if (sign == null) sign = findOcrTextCenter(first, "签到领淘金币");
            if (sign != null) {
                lastDailySignX = sign[0];
                lastDailySignY = sign[1];
                shellTap(sign[0], sign[1]);
                logMode("通过单帧 OCR 点击每日首次‘签到领金币’");
                logMode("已处理每日首次签到；等待 5 秒让按钮原地切换为‘赚更多金币’");
                return waitAndOpenMoreAfterDailySign();
            }

            int[] more = findMoreCoinsVisualPoint(first);
            int score = coinHomeEvidenceScore(first);
            if (more == null) more = findCoinHomeCenterActionFallback(first, score);
            if (more != null) {
                coinHomeGeometryFallbackUsed = true;
                shellTap(more[0], more[1]);
                logMode("通过单帧快速 OCR/首页特征点击‘赚更多金币’: ("
                        + more[0] + ", " + more[1] + ")");
                sleep(1500);
                if (isTaskPanelVisibleForNavigation()) return ensureTaskListExpanded();
            }
        }

        // Only use the slower retry path when the first fast frame was genuinely inconclusive.
        return clickMoreCoinsAndConfirm(3);
    }

    /** 新季节双十一赛车首页：以左下“去赚金币”强锚（旧版为“赚更多金币”）判定。 */
    private boolean isSeasonalCoinHome(OcrEngine.Snapshot snap) {
        if (snap == null) return false;
        return normalizeOcr(snap.allText()).contains("去赚金币");
    }

    /**
     * 新版赛车首页左侧“去赚金币”入口定位：优先元素级，其次行级合并
     * （如“去赚金币 去赚能量”一行）按子串相对位置定位，且要求落在左侧按钮区；
     * y 比例仅用于限制候选区域，不做盲点兜底。
     */
    private int[] findCoinEntryPoint(OcrEngine.Snapshot snap) {
        if (snap == null) return null;
        int minY = (int) (snap.height * 0.35f);
        int maxY = (int) (snap.height * 0.62f);

        if (snap.elements != null) {
            for (OcrEngine.OcrRow e : snap.elements) {
                String t = normalizeOcr(e.text);
                if (!(t.equals("去赚金币") || t.equals("赚金币"))) continue;
                int cy = e.bounds.centerY();
                if (cy < minY || cy > maxY) continue;
                int cx = e.bounds.centerX();
                if (cx > snap.width * 0.55f) continue;  // 仅左侧按钮区
                return new int[]{cx, cy};
            }
        }
        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (!t.contains("去赚金币")) continue;
            int cy = row.bounds.centerY();
            if (cy < minY || cy > maxY) continue;
            int idx = t.indexOf("去赚金币");
            float charCenter = idx + 2.0f;
            float ratio = charCenter / Math.max(1f, t.length());
            int x = row.bounds.left + Math.round(row.bounds.width() * ratio);
            if (x > snap.width * 0.55f) continue;
            return new int[]{Math.max(24, Math.min(snap.width - 24, x)), cy};
        }
        return null;
    }

    /** 季节赛车首页：点击左侧“去赚金币”并确认金币任务面板打开，有限次重试。 */
    private boolean openSeasonalCoinPanel(OcrEngine.Snapshot first) throws Exception {
        logMode("COIN_HOME_VARIANT=DOUBLE11_RACE evidence=去赚金币");
        for (int attempt = 1; attempt <= 3 && !stopRequested; attempt++) {
            OcrEngine.Snapshot snap = (attempt == 1) ? first : ocr.captureNavigation();
            if (snap == null) {
                sleep(600);
                continue;
            }
            if (taskPanelVisibleInSnapshot(snap)) {
                logMode("COIN_PANEL_VERIFY trusted=true（金币任务面板已打开）");
                return ensureTaskListExpanded();
            }
            int[] entry = findCoinEntryPoint(snap);
            if (entry == null) {
                logMode("COIN_ENTRY_NOT_FOUND（第 " + attempt + "/3 次未定位到左侧去赚金币）");
                sleep(600);
                continue;
            }
            shellTap(entry[0], entry[1]);
            logMode("COIN_ENTRY_TAP left=true (" + entry[0] + "," + entry[1] + ")（第 " + attempt + "/3 次）");
            sleep(2200);
            if (isTaskPanelVisibleForNavigation()) {
                logMode("COIN_PANEL_VERIFY trusted=true（金币任务面板已打开）");
                return ensureTaskListExpanded();
            }
            logMode("COIN_PANEL_UNCONFIRMED（第 " + attempt + "/3 次点击后未确认任务面板）");
        }
        logMode("COIN_PANEL_UNCONFIRMED 季节入口多次未确认；OCR 摘要: " + summarizeOcrSnippet(first));
        return false;
    }

    /**
     * 签到之后专用等待阶段。中央大按钮通常在原位置从“签到领金币”变成“赚更多金币”。
     * 不重启淘宝，也不重新走首页导航；最长等待约 15 秒。
     */
    /**
     * 签到之后专用阶段：按用户实测固定等待 5 秒，随后立即点击原地切换出的“赚更多金币”。
     * 不做 15 秒轮询，也不重启淘宝。
     */
    private boolean waitAndOpenMoreAfterDailySign() throws Exception {
        logMode("签到完成，固定等待 5 秒让按钮切换为‘赚更多金币’");
        sleep(5000);
        if (stopRequested) return false;

        if (isTaskPanelVisibleForNavigation()) {
            return ensureTaskListExpanded();
        }

        // 第一优先：通过 OCR 找“赚更多金币”。
        if (clickMoreCoinsOnce()) {
            logMode("签到后已点击‘赚更多金币’，等待任务弹窗");
            sleep(2500);
            if (isTaskPanelVisibleForNavigation()) {
                return ensureTaskListExpanded();
            }
        }

        // UC WebView 可能已经换字但 OCR 仍是旧帧。中央按钮位置不变，
        // 因此只在“刚刚明确完成签到”的前提下，使用签到按钮原坐标兜底点击一次。
        if (lastDailySignX > 0 && lastDailySignY > 0) {
            logMode("5 秒后仍未识别到‘赚更多金币’，按签到按钮原位置兜底点击一次");
            shellTap(lastDailySignX, lastDailySignY);
            sleep(2500);
            if (isTaskPanelVisibleForNavigation()) {
                return ensureTaskListExpanded();
            }
        }

        // 最后只再做一次正常识别点击，不继续长时间等待。
        if (clickMoreCoinsOnce()) {
            logMode("签到后第二次识别到‘赚更多金币’，已点击");
            sleep(2500);
            if (isTaskPanelVisibleForNavigation()) {
                return ensureTaskListExpanded();
            }
        }

        logMode("签到后 5 秒流程结束，仍未确认任务弹窗；保持当前页面交给外层重试");
        return isTaskPanelVisibleForNavigation() && ensureTaskListExpanded();
    }

    private boolean clickMoreCoinsAndConfirm(int maxAttempts) throws Exception {
        for (int i = 1; i <= maxAttempts && !stopRequested; i++) {
            if (isTaskPanelVisibleForNavigation()) {
                return ensureTaskListExpanded();
            }

            if (clickMoreCoinsOnce()) {
                sleep(1500);
                if (isTaskPanelVisibleForNavigation()) {
                    return ensureTaskListExpanded();
                }
                logMode("已点击‘赚更多金币’，但暂未确认任务弹窗，继续等待 (" + i + "/" + maxAttempts + ")");
                sleep(450);
            } else {
                logMode("等待‘赚更多金币’入口加载 (" + i + "/" + maxAttempts + ")");
                sleep(500);
            }
        }
        return isTaskPanelVisibleForNavigation() && ensureTaskListExpanded();
    }

    /**
     * 只负责点击一次“赚更多金币”，不做重启、不做页面跳转。
     *
     * 新版淘金币把中央按钮绘制在 UC WebView/Canvas 中，澎湃 OS 3 上经常出现：
     * 1) 屏幕肉眼已经显示“赚更多金币”；
     * 2) 页面没有可供本工具使用的结构化控件信息；
     * 3) ML Kit 把“金币”中的某一个字识别错，导致精确 contains 失败。
     *
     * 因此这里采用两层策略：OCR 模糊文字 -> 淘金币首页多特征确认后的
     * 中央按钮安全区域兜底。第二层每次 openTaskPanel() 最多执行一次。
     */
    private boolean clickMoreCoinsOnce() throws Exception {
        OcrEngine.Snapshot snap = null;
        try {
            snap = ocr.captureNavigation();
            if (dismissBlockingPopupIfPresent(snap, "淘金币首页")) {
                sleep(800);
                return false;
            }
            int[] p = findMoreCoinsVisualPoint(snap);
            if (p != null) {
                shellTap(p[0], p[1]);
                logMode("通过 OCR 模糊匹配点击淘金币首页“赚更多金币”: (" + p[0] + ", " + p[1] + ")");
                return true;
            }

            if (!coinHomeGeometryFallbackUsed) {
                int score = coinHomeEvidenceScore(snap);
                int[] fallback = findCoinHomeCenterActionFallback(snap, score);
                if (fallback != null) {
                    coinHomeGeometryFallbackUsed = true;
                    shellTap(fallback[0], fallback[1]);
                    logMode("OCR 未精确读出“赚更多金币”，但淘金币首页特征分=" + score
                            + "；按中央大按钮安全位置兜底点击一次: ("
                            + fallback[0] + ", " + fallback[1] + ")");
                    return true;
                }
            }
        } catch (Throwable e) {
            logMode("赚更多金币 OCR 定位失败: " + e.getMessage());
        }

        // 识别不到时输出一条精简诊断，下一份日志可以直接看到 ML Kit 实际读到了什么。
        if (snap != null) {
            logMode("未定位到“赚更多金币”；首页 OCR 摘要: " + summarizeCoinHomeOcr(snap));
        }
        return false;
    }

    /**
     * 从 OCR 行中寻找中央“赚更多金币”。允许 1 个汉字被 ML Kit 识别错，
     * 并要求命中位置处于淘金币首页中央操作带，避免商品区其它“金币”文字造成误点。
     */
    private int[] findMoreCoinsVisualPoint(OcrEngine.Snapshot snap) {
        if (snap == null) return null;
        int minY = (int) (snap.height * 0.18f);
        int maxY = (int) (snap.height * 0.36f);
        for (OcrEngine.OcrRow row : snap.rows) {
            int y = row.bounds.centerY();
            if (y < minY || y > maxY) continue;
            String text = normalizeOcr(row.text);
            int earnPos = text.indexOf("赚");
            int morePos = text.indexOf("更多");
            // 要求“赚”出现在“更多”之前且距离很近，避免把任务弹窗标题
            // “更多金币等你赚”误当成首页按钮。
            boolean orderedEarnMore = earnPos >= 0 && morePos > earnPos && (morePos - earnPos) <= 3;
            boolean likely = text.contains("赚更多金币")
                    || (orderedEarnMore && fuzzyContains(text, "赚更多金币", 1))
                    || (orderedEarnMore && text.contains("金"));
            if (!likely) continue;

            // 同一水平行可能被 mergeRows 合并成“玩游戏 赚更多金币 找好友”，
            // 这种情况下整行中心正好仍落在中央大按钮。
            int x = row.bounds.centerX();
            if (row.bounds.width() > snap.width * 0.55f) x = snap.width / 2;
            x = Math.max(24, Math.min(snap.width - 24, x));
            return new int[]{x, y};
        }
        return null;
    }

    /**
     * 只根据“淘金币首页”独有的多个视觉文字共同打分。
     * 单独出现“淘金币”或“搜索”绝不会触发几何兜底。
     */
    private int coinHomeEvidenceScore(OcrEngine.Snapshot snap) {
        if (snap == null) return 0;
        String all = normalizeOcr(snap.allText());
        int score = 0;
        if (all.contains("淘金币") || fuzzyContains(all, "淘金币", 1)) score += 3;
        if (all.contains("搜索抵钱") || (all.contains("搜索") && all.contains("金币额外抵扣"))) score += 2;
        if (all.contains("玩游戏")) score += 1;
        if (all.contains("找好友")) score += 1;
        if (all.contains("快速赚") || all.contains("明天可领") || all.contains("后天")) score += 1;
        if (all.contains("铂金会员") || all.contains("足迹加抵")) score += 1;
        return score;
    }

    /**
     * 当中央文字本身 OCR 失败时，优先使用“玩游戏/找好友”同一行推断按钮 y；
     * 若侧边文字也没读到，则只有首页特征分足够高时才使用屏幕比例坐标。
     * 用户当前 921x2048 截图中按钮中心约为 (0.50W, 0.265H)。
     */
    private int[] findCoinHomeCenterActionFallback(OcrEngine.Snapshot snap, int score) {
        if (snap == null || score < COIN_HOME_EVIDENCE_THRESHOLD) return null;
        String all = normalizeOcr(snap.allText());
        // 如果 OCR 已经读到任务弹窗特征，即使 isTaskPanelVisibleForNavigation() 刚才因为
        // 截图时序没确认成功，也绝不再按首页中央坐标。
        if (all.contains("今日速赚") || all.contains("更多金币等你赚")
                || all.contains("完成下方任务得额外金币") || all.contains("快速赚奖励已拿完")) {
            return null;
        }
        int minY = (int) (snap.height * 0.18f);
        int maxY = (int) (snap.height * 0.36f);
        int gameY = -1;
        int friendY = -1;

        for (OcrEngine.OcrRow row : snap.rows) {
            int y = row.bounds.centerY();
            if (y < minY || y > maxY) continue;
            String t = normalizeOcr(row.text);
            if (t.contains("玩游戏")) gameY = y;
            if (t.contains("找好友")) friendY = y;
            if (t.contains("玩游戏") && t.contains("找好友")) {
                return new int[]{snap.width / 2, y};
            }
        }

        if (gameY > 0 && friendY > 0 && Math.abs(gameY - friendY) <= 90) {
            return new int[]{snap.width / 2, (gameY + friendY) / 2};
        }
        if (gameY > 0) return new int[]{snap.width / 2, gameY};
        if (friendY > 0) return new int[]{snap.width / 2, friendY};

        // 最后一级只在达到多特征阈值的强首页证据下启用。
        return new int[]{snap.width / 2, (int) (snap.height * 0.265f)};
    }

    private boolean fuzzyContains(String text, String target, int maxDistance) {
        if (text == null || target == null) return false;
        String a = normalizeOcr(text);
        String b = normalizeOcr(target);
        if (a.contains(b)) return true;
        if (b.isEmpty() || a.isEmpty()) return false;

        int minLen = Math.max(1, b.length() - maxDistance);
        int maxLen = b.length() + maxDistance;
        for (int len = minLen; len <= maxLen; len++) {
            if (len > a.length()) continue;
            for (int i = 0; i + len <= a.length(); i++) {
                if (editDistance(a.substring(i, i + len), b, maxDistance) <= maxDistance) return true;
            }
        }
        return false;
    }

    /** 带上限的 Levenshtein；超过 limit 时提前退出。 */
    private int editDistance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) return limit + 1;
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private boolean looksLikeCoinHomeSnapshot(OcrEngine.Snapshot snap) {
        if (snap == null) return false;
        if (findMoreCoinsVisualPoint(snap) != null) return true;
        String all = normalizeOcr(snap.allText());
        if (all.contains("签到领金币") || all.contains("签到领淘金币")) return true;
        return coinHomeEvidenceScore(snap) >= COIN_HOME_EVIDENCE_THRESHOLD;
    }

    private String summarizeCoinHomeOcr(OcrEngine.Snapshot snap) {
        if (snap == null) return "<无截图>";
        StringBuilder sb = new StringBuilder();
        int minY = (int) (snap.height * 0.08f);
        int maxY = (int) (snap.height * 0.42f);
        for (OcrEngine.OcrRow row : snap.rows) {
            int y = row.bounds.centerY();
            if (y < minY || y > maxY) continue;
            String t = normalizeOcr(row.text);
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t);
            if (sb.length() > 220) break;
        }
        return sb.length() == 0 ? "<顶部区域无文字>" : sb.toString();
    }

    /**
     * 处理淘金币首页每天第一次出现的“签到领金币/签到领淘金币”大按钮。
     * 返回 true 表示本轮确实执行了签到点击；false 表示当前无需签到。
     */
    private boolean clickDailySignInIfPresent() throws Exception {
        lastDailySignX = -1;
        lastDailySignY = -1;
        if (!TB.equals(currentPackage())) return false;

        try {
            // One upper-screen frame is enough to decide panel/sign-in state and locate the button.
            OcrEngine.Snapshot snap = ocr.captureNavigation();
            if (taskPanelVisibleInSnapshot(snap)) return false;

            int[] p = findOcrTextCenter(snap, "签到领金币");
            if (p == null) p = findOcrTextCenter(snap, "签到领淘金币");
            if (p != null) {
                lastDailySignX = p[0];
                lastDailySignY = p[1];
                shellTap(p[0], p[1]);
                logMode("通过单帧 OCR 点击每日首次‘签到领金币’");
                return true;
            }
        } catch (Throwable e) {
            logMode("签到 OCR 定位失败: " + e.getMessage());
        }
        return false;
    }

    private int[] findOcrTextCenter(OcrEngine.Snapshot snap, String needle) {
        if (snap == null) return null;
        String normalizedNeedle = normalizeOcr(needle);
        for (OcrEngine.OcrRow row : snap.rows) {
            if (!normalizeOcr(row.text).contains(normalizedNeedle)) continue;
            Rect b = row.bounds;
            int x = b.centerX();
            int y = b.centerY();
            if (x > 0 && y > 0 && x < snap.width && y < snap.height) return new int[]{x, y};
        }
        return null;
    }

    /**
     * 打开“今日速赚”后，尽量点击一次“展开”；但是否可以进入主循环，
     * 不再依赖某个单一的“展开/收起”文字或固定 Activity 名。
     *
     * 淘宝 UC WebView 的实际表现有两种都属于可执行状态：
     * 1) “快速赚”区域完全展开；
     * 2) 快速赚区域收起，但下方“更多专享福利”的带进度任务和右侧操作按钮已经可见。
     *
     * v1.3 把“真正展开”判得过严，用户截图中页面已经可执行仍被判失败。
     * v1.4 改成“可见任务行本身就是成功证据”。
     */
    private boolean ensureTaskListExpanded() throws Exception {
        if (!TB.equals(currentPackage())) return false;

        // v2.2: “更多金币等你赚  展开”这张折叠卡的优先级必须高于“可信任务面板”。
        // v2.1 先做可信面板判断，导致上方今日速赚已经可见时提前 return，实际从未点击底部展开。
        for (int attempt = 1; attempt <= 3 && !stopRequested; attempt++) {
            OcrEngine.Snapshot snap;
            try {
                snap = ocr.captureTaskPanel();
            } catch (Throwable e) {
                logMode("任务面板 OCR 失败: " + e.getMessage());
                sleep(420);
                continue;
            }

            if (dismissBlockingPopupIfPresent(snap, "任务面板")) {
                sleep(800);
                continue;
            }

            if (attempt == 1) logTaskPanelVariant(snap);

            // 第一优先：只要折叠卡还在，就必须先展开。即使 ML Kit 漏掉“展开”两个字，
            // 也用“更多金币等你赚”这一行作为锚点，点击同一行右侧的安全区域。
            int[] expand = findExpandTaskPanelPoint(snap);
            if (expand != null) {
                shellTap(expand[0], expand[1]);
                logMode("检测到‘更多金币等你赚/展开’折叠卡，优先点击展开: ("
                        + expand[0] + ", " + expand[1] + ")");
                sleep(720);

                OcrEngine.Snapshot after = ocr.captureTaskPanel();
                int[] stillCollapsed = findExpandTaskPanelPoint(after);
                if (stillCollapsed == null && TaskParser.isTrustedTaskContext(after.rows, after.height)) {
                    logMode("展开控件已消失，且任务面板认证通过；进入主循环");
                    return true;
                }
                if (stillCollapsed != null) {
                    logMode("展开控件仍可见，等待 UC WebView 刷新后重试 (" + attempt + "/3)");
                    sleep(420);
                    continue;
                }
                if (TaskParser.isTrustedTaskContext(after.rows, after.height)) {
                    logMode("展开后已确认可信任务面板；允许继续识别/下滑");
                    return true;
                }
                if (taskPanelVisibleInSnapshot(after)) {
                    logMode("展开后任务弹窗仍在，但任务列表特征尚未稳定；等待下一帧 ("
                            + attempt + "/3)");
                    sleep(420);
                    continue;
                }
            }

            // 只有确认不存在折叠卡，才允许“可信任务面板”直接进入主循环。
            if (TaskParser.isTrustedTaskContext(snap.rows, snap.height)) {
                logMode("未看到‘更多金币等你赚’折叠卡，任务面板认证通过；可直接识别/下滑");
                return true;
            }

            logMode("第 " + attempt + "/3 次未确认展开控件或可执行任务列表，短暂等待页面刷新");
            sleep(420);
        }

        boolean ready = isExpandedTaskListVisible();
        if (ready) logMode("最终确认：任务面板已可执行");
        return ready;
    }

    /**
     * “任务列表可执行”的视觉判定。
     *
     * 这里刻意只要求淘宝包名，不再强制 resumed Activity 必须恰好叫 TMSActivity。
     * 淘宝不同版本/ROM 上活动名会变化，而真实截图 OCR 才是当前页面的最终依据。
     */
    private boolean isExpandedTaskListVisible() {
        ForegroundState fg = currentForeground();
        if (!fg.isPackage(TB)) return false;

        // v2.1：不把单独的“收起”或任意 x/y 当成功证据，同时允许金币圆标型任务面板。
        // UC WebView 页面只以真实截图 OCR 的组合特征作为任务列表认证依据。
        try {
            OcrEngine.Snapshot snap = ocr.captureTaskPanel();
            if (findExpandTaskPanelPoint(snap) != null) {
                logMode("仍看到‘更多金币等你赚/展开’折叠卡，不能把当前画面当作已展开任务列表");
                return false;
            }
            if (TaskParser.isTrustedTaskContext(snap.rows, snap.height)) {
                List<TaskParser.TaskItem> tasks = TaskParser.parse(snap.rows, snap.height);
                logMode("OCR 任务上下文认证通过：可见带进度任务 " + tasks.size()
                        + " 个 / 动作行 " + TaskParser.countActionRows(snap.rows));
                return true;
            }
        } catch (Throwable e) {
            logMode("任务区域 OCR 强认证失败: " + e.getMessage());
        }
        return false;
    }

    private void returnToTaskList(TaskMode mode) throws Exception {
        logMode("开始返回任务列表");
        long deadline = SystemClock.elapsedRealtime() + 35_000L;
        int backs = 0;
        int foreignBounceCount = 0;

        while (!stopRequested && SystemClock.elapsedRealtime() < deadline && backs < 6) {
            ForegroundState fg = currentForeground();
            if (!fg.valid()) {
                sleep(500);
                continue;
            }
            if (fg.activityContains("Ext2ContainerActivity")) {
                sleep(500);
                continue;
            }

            logMode("返回状态: " + fg);

            if (fg.isPackage(TB) && isModeTaskListVisible(mode)) {
                logMode("当前已经是任务列表画面，停止继续返回");
                return;
            }

            if (!fg.isPackage(TB)) {
                foreignBounceCount++;
                logMode("当前在其他应用，拉回淘宝: " + fg.packageName
                        + " (跨应用回跳 " + foreignBounceCount + "/2)");

                // 闲鱼/淘宝广告深链可能形成“闲鱼 -> 淘宝商品 -> back -> 闲鱼”的环。
                // 第二次回到外部 App 时不再继续猜 Back 栈，直接 force-stop 淘宝并重建任务页。
                if (foreignBounceCount >= 2) {
                    logMode("检测到跨应用深链来回跳转，放弃当前返回栈，强制重建任务页");
                    if (!enterTaskList(true, mode)) throw new IllegalStateException("跨应用深链后恢复任务列表失败");
                    return;
                }

                launchTaobao();
                sleep(2000);
                if (tapOcrTextContains("跳过", true)) {
                    logMode("通过 OCR 点击淘宝启动页‘跳过’");
                    sleep(1800);
                }
                continue;
            }

            if (fg.activityContains("com.taobao.themis.container.app.TMSActivity") && isCoinHomeVisibleForNavigation()) {
                if (openPanelForMode(mode) && isModeTaskListVisible(mode)) {
                    logMode("已重新打开并确认任务列表");
                    return;
                }
            }

            if (fg.activityContains("com.taobao.tao.welcome.Welcome")) {
                logMode("返回到了淘宝首页而不是任务列表，执行完整恢复");
                if (!enterTaskList(true, mode)) throw new IllegalStateException("返回淘宝首页后恢复失败");
                return;
            }

            logMode("点击系统后退");
            shellBack();
            backs++;
            sleep(500);
        }

        if (stopRequested) return;
        logMode("常规返回未能恢复任务列表，执行完整恢复");
        if (!enterTaskList(true, mode)) throw new IllegalStateException("无法恢复任务列表");
    }

    /** 按模式确认“任务列表可执行”的画面状态。 */
    private boolean isModeTaskListVisible(TaskMode mode) {
        if (mode == TaskMode.DOUBLE11_ENERGY) return isEnergyTaskListVisible();
        return isExpandedTaskListVisible();
    }

    /** 按模式打开对应任务面板（金币或能量）。 */
    private boolean openPanelForMode(TaskMode mode) throws Exception {
        if (mode == TaskMode.DOUBLE11_ENERGY) return openEnergyPanel();
        return openTaskPanel();
    }

    /** 按模式判断当前截图是否为本模式的任务面板（用于首页导航短路）。 */
    private boolean taskPanelVisibleForMode(OcrEngine.Snapshot snap) {
        if (currentMode == TaskMode.DOUBLE11_ENERGY) {
            return TaskParser.isTrustedEnergyTaskPanel(snap.rows, snap.height)
                    || isEnergyTaskListContext(snap);
        }
        return taskPanelVisibleInSnapshot(snap);
    }

    /**
     * 能量入口：从赛车淘金币首页右下“赚金币 / 赚能量”两侧按钮区域定位“赚能量”，
     * 点击后以“做任务赚能量”+“累计任务奖励/当前进度/具体任务行”组合证据确认面板。
     */
    private boolean openEnergyPanel() throws Exception {
        logMode("定位“做任务赚能量”面板");

        // 能量面板若已打开，直接复用当前页面，不再找“赚能量”入口。
        if (isEnergyTaskPanelVisibleForNavigation()) {
            return ensureEnergyPanelReady();
        }

        for (int i = 1; i <= 3 && !stopRequested; i++) {
            OcrEngine.Snapshot snap;
            try {
                snap = ocr.captureNavigation();
            } catch (Throwable e) {
                logMode("能量导航 OCR 失败: " + e.getMessage());
                sleep(500);
                continue;
            }

            // P0：先认证合法能量面板，再考虑遮挡关闭，避免把已打开的面板误关。
            if (TaskParser.isTrustedEnergyTaskPanel(snap.rows, snap.height)) {
                logMode("OCR 已确认能量面板已打开");
                return ensureEnergyPanelReady();
            }

            if (dismissBlockingPopupIfPresent(snap, "淘金币首页")) {
                sleep(850);
                continue;
            }

            int[] p = findEnergyEntryPoint(snap);
            if (p != null) {
                shellTap(p[0], p[1]);
                logMode("点击淘金币首页右侧“赚能量”入口: (" + p[0] + ", " + p[1] + ")");
                sleep(2200);
                if (isEnergyTaskPanelVisibleForNavigation()) {
                    return ensureEnergyPanelReady();
                }
                // 已点击但未确认，继续用新帧重定位，不重复点固定坐标。
            } else {
                logMode("第 " + i + "/3 次未在淘金币首页定位到“赚能量”入口");
                sleep(500);
            }
        }

        logMode("未能确认进入能量任务面板");
        return false;
    }

    private boolean ensureEnergyPanelReady() throws Exception {
        if (!TB.equals(currentPackage())) return false;
        for (int attempt = 1; attempt <= 3 && !stopRequested; attempt++) {
            OcrEngine.Snapshot snap;
            try {
                snap = ocr.captureTaskPanel();
            } catch (Throwable e) {
                logMode("能量任务面板 OCR 失败: " + e.getMessage());
                sleep(420);
                continue;
            }

            // P0：先认证合法能量面板，再考虑遮挡关闭，避免面板刚打开就被误关。
            if (TaskParser.isTrustedEnergyTaskPanel(snap.rows, snap.height)) {
                energyPanelConfirmed = true;
                logMode("已确认“做任务赚能量”任务面板，可进入主循环");
                return true;
            }

            if (dismissBlockingPopupIfPresent(snap, "能量任务面板")) {
                sleep(800);
                continue;
            }

            logMode("第 " + attempt + "/3 次未确认能量面板特征，等待刷新");
            if (attempt >= 2) logMode("能量面板 OCR 摘要: " + summarizeOcrSnippet(snap));
            sleep(420);
        }
        return false;
    }

    private boolean isEnergyTaskPanelVisibleForNavigation() {
        if (!TB.equals(currentPackage())) return false;
        try {
            OcrEngine.Snapshot snap = ocr.captureNavigation();
            boolean hit = TaskParser.isTrustedEnergyTaskPanel(snap.rows, snap.height);
            if (hit) logMode("OCR 已确认“做任务赚能量”任务面板已打开");
            return hit;
        } catch (Throwable e) {
            logMode("能量任务面板 OCR 确认失败: " + e.getMessage());
            return false;
        }
    }

    /** 能量任务列表可执行的视觉判定。 */
    private boolean isEnergyTaskListVisible() {
        ForegroundState fg = currentForeground();
        if (!fg.isPackage(TB)) return false;
        try {
            OcrEngine.Snapshot snap = ocr.captureTaskPanel();
            if (TaskParser.isTrustedEnergyTaskPanel(snap.rows, snap.height)
                    || isEnergyTaskListContext(snap)) {
                List<TaskParser.TaskItem> tasks = TaskParser.parseEnergyActionable(snap.rows, snap.height);
                logMode("能量任务面板认证通过：可见带进度任务 " + tasks.size() + " 个");
                return true;
            }
        } catch (Throwable e) {
            logMode("能量任务区域 OCR 强认证失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 在淘金币首页较靠下区域定位右侧“赚能量”按钮。
     * 优先精确元素，其次处理“赚金币 赚能量”合并成一行时按子串位置点击。
     * 禁止无条件点击固定坐标。
     */
    private int[] findEnergyEntryPoint(OcrEngine.Snapshot snap) {
        if (snap == null) return null;
        int minY = (int) (snap.height * 0.40f);
        int maxY = (int) (snap.height * 0.88f);

        if (snap.elements != null) {
            for (OcrEngine.OcrRow e : snap.elements) {
                String t = normalizeOcr(e.text);
                if (!t.equals("赚能量")) continue;
                int y = e.bounds.centerY();
                if (y < minY || y > maxY) continue;
                return new int[]{e.bounds.centerX(), y};
            }
        }

        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (!t.contains("赚能量")) continue;
            int y = row.bounds.centerY();
            if (y < minY || y > maxY) continue;
            int idx = t.indexOf("赚能量");
            int x;
            if (t.length() <= 3) {
                x = row.bounds.centerX();
            } else {
                float charCenter = idx + 1.5f;
                float ratio = charCenter / Math.max(1f, t.length());
                x = row.bounds.left + Math.round(row.bounds.width() * ratio);
            }
            return new int[]{Math.max(24, Math.min(snap.width - 24, x)), y};
        }
        return null;
    }

    /** 能量模式收尾：回顶复查 + 5/20/50 累计任务奖励独立领取。 */
    private boolean energyRewardSweep() throws Exception {
        if (stopRequested) return false;
        AppState.status = "收尾复查";
        logMode("开始收尾：先强制返回能量任务面板顶部，再复查未完成任务");

        if (!TB.equals(currentPackage())) {
            logMode("收尾时已离开淘宝，先恢复能量任务页");
            if (!enterTaskList(true, TaskMode.DOUBLE11_ENERGY)) {
                logMode("收尾恢复失败，跳过领奖并结束");
                return false;
            }
        }

        OcrEngine.Snapshot topSnap = rewindEnergyPanelToTop();
        if (stopRequested || topSnap == null) return false;

        OcrEngine.Snapshot taskRecheckSnap = ocr.captureTaskList();
        List<TaskParser.TaskItem> topTasks = TaskParser.parseEnergyActionable(taskRecheckSnap.rows, taskRecheckSnap.height);
        logMode("收尾复查：顶部识别到 " + topTasks.size() + " 个能量任务");
        TaskParser.TaskItem unfinished = null;
        for (TaskParser.TaskItem task : topTasks) {
            String skipReason = TaskParser.skipReason(task, taskRules);
            if (skipReason != null) {
                logMode("收尾复查跳过[" + skipReason + "]: " + task.name);
                continue;
            }
            if (attempted.getOrDefault(stableTaskId(task), 0) >= 2) {
                logMode("收尾复查发现未完成任务但已达到尝试上限，跳过: " + task.name);
                continue;
            }
            unfinished = task;
            break;
        }

        if (unfinished != null && finalTopRecheckRounds < 2) {
            finalTopRecheckRounds++;
            logMode("收尾复查发现仍有可执行能量任务: " + unfinished.name
                    + "；返回主循环继续执行 (" + finalTopRecheckRounds + "/2)");
            AppState.status = "识别任务中";
            return true;
        }

        if (unfinished != null) {
            logMode("收尾复查仍看到未完成能量任务，但已达到复查轮次上限；为避免循环，继续领奖并结束");
        } else {
            logMode("收尾复查：顶部未发现黑名单外的未完成能量任务，检查累计任务奖励");
        }

        AppState.status = "收尾领奖";
        claimEnergyCumulativeReward(topSnap);
        AppState.status = "识别任务中";
        return false;
    }

    private OcrEngine.Snapshot rewindEnergyPanelToTop() throws Exception {
        String lastSignature = "";
        int unchanged = 0;
        OcrEngine.Snapshot snap = null;
        for (int i = 1; i <= 8 && !stopRequested; i++) {
            snap = ocr.captureTaskPanel();
            String all = normalizeOcr(snap.allText());

            if (i > 1 && isEnergyRewardPanelTop(all)) {
                logMode("已确认回到能量任务面板顶部");
                break;
            }

            String sig = compactPanelSignature(snap);
            if (!sig.isEmpty() && sig.equals(lastSignature)) unchanged++; else unchanged = 0;
            lastSignature = sig;
            if (i > 2 && unchanged >= 2) {
                logMode("连续两次向上滑动后画面未变化，按当前画面视为顶部");
                break;
            }

            logMode("收尾：向上返回能量面板顶部 (" + i + "/8)");
            shellSwipeToTop(snap.width, snap.height);
            sleep(650);
        }

        if (stopRequested) return snap;
        snap = ocr.captureTaskPanel();
        logMode("已回到顶部，开始复查能量任务与奖励");
        return snap;
    }

    private boolean isEnergyRewardPanelTop(String normalizedAll) {
        if (normalizedAll == null) return false;
        return normalizedAll.contains("累计任务奖励") || normalizedAll.contains("当前进度");
    }

    /**
     * 能量累计任务奖励：只在“当前进度”达到 5/20/50 任一门槛、且明确看到可领按钮时点击。
     * 绝不照搬金币经典版的三个固定 X 点位。
     */
    private void claimEnergyCumulativeReward(OcrEngine.Snapshot topSnap) throws Exception {
        if (topSnap == null) return;
        int[] progress = TaskParser.energyCumulativeProgress(topSnap.rows);
        if (progress == null) {
            logMode("未识别到“累计任务奖励/当前进度”，跳过能量领奖避免误点");
            return;
        }
        int current = progress[0];
        logMode("能量累计任务奖励进度: " + current + "/" + progress[1]);

        int threshold;
        if (current >= 50) threshold = 50;
        else if (current >= 20) threshold = 20;
        else if (current >= 5) threshold = 5;
        else threshold = 0;

        if (threshold <= 0) {
            logMode("当前进度 " + current + " 未达 5/20/50 任一门槛，跳过能量领奖");
            return;
        }

        int[] claim = findEnergyClaimPoint(topSnap);
        if (claim == null) {
            logMode("达到 " + threshold + " 次门槛，但未找到明确可领按钮，跳过避免误点");
            return;
        }
        shellTap(claim[0], claim[1]);
        logMode("已点击能量累计奖励领取按钮 (" + claim[0] + ", " + claim[1] + ")");
        sleep(800);
    }

    private int[] findEnergyClaimPoint(OcrEngine.Snapshot snap) {
        if (snap == null) return null;
        int anchorY = -1;
        for (OcrEngine.OcrRow row : snap.rows) {
            if (normalizeOcr(row.text).contains("累计任务奖励")) {
                anchorY = row.bounds.centerY();
                break;
            }
        }
        if (anchorY < 0) return null;
        for (OcrEngine.OcrRow row : snap.rows) {
            int y = row.bounds.centerY();
            if (Math.abs(y - anchorY) > 260) continue;
            String t = normalizeOcr(row.text);
            // 只认“立即领取”或单独“领取”，避免把任务行的“领取奖励”当累计奖励点击。
            if (t.contains("立即领取") || t.equals("领取")) {
                return new int[]{Math.max(24, Math.min(snap.width - 24, row.bounds.centerX())), y};
            }
        }
        return null;
    }


    /**
     * 主任务结束后的统一收尾阶段。
     *
     * 1) 无论新旧版，先强制把任务列表滑回最上方；
     * 2) 在顶部重新检查是否还有未完成且不在黑名单中的任务；
     * 3) 若有，则返回主循环继续执行；
     * 4) 若没有，才开始领取旧版“今日累计奖励”或当前可见的领取按钮。
     *
     * @return true 表示顶部复查发现了新的可执行任务，调用方应继续 mainLoop。
     */
    private boolean finalRewardSweep(TaskMode mode) throws Exception {
        if (mode == TaskMode.DOUBLE11_ENERGY) return energyRewardSweep();
        if (stopRequested) return false;
        AppState.status = "收尾复查";
        logMode("开始收尾：先强制返回任务面板顶部，再复查未完成任务");

        if (!TB.equals(currentPackage())) {
            logMode("收尾时已离开淘宝，先恢复淘金币任务页");
            if (!enterTaskList(true, mode)) {
                logMode("收尾恢复失败，跳过领奖并结束");
                return false;
            }
        }

        OcrEngine.Snapshot topSnap = rewindTaskPanelToTop();
        if (stopRequested || topSnap == null) return false;

        // 顶部复查改用任务列表专用高分辨率 OCR，尽量减少形近字/进度漏识别。
        // 最多允许触发两轮，避免某个失败任务导致“到底 -> 回顶部 -> 再到底”的死循环。
        OcrEngine.Snapshot taskRecheckSnap = ocr.captureTaskList();
        List<TaskParser.TaskItem> topTasks = TaskParser.parse(taskRecheckSnap.rows, taskRecheckSnap.height);
        logMode("收尾复查：顶部识别到 " + topTasks.size() + " 个带进度任务");
        TaskParser.TaskItem unfinished = null;
        for (TaskParser.TaskItem task : topTasks) {
            String skipReason = TaskParser.skipReason(task, taskRules);
            if (skipReason != null) {
                logMode("收尾复查跳过[" + skipReason + "]: " + task.name);
                continue;
            }
            if (attempted.getOrDefault(stableTaskId(task), 0) >= 2) {
                logMode("收尾复查发现未完成任务但已达到尝试上限，跳过: " + task.name);
                continue;
            }
            unfinished = task;
            break;
        }

        if (unfinished != null && finalTopRecheckRounds < 2) {
            finalTopRecheckRounds++;
            logMode("收尾复查发现仍有可执行任务: " + unfinished.name
                    + "；返回主循环继续执行 (" + finalTopRecheckRounds + "/2)");
            AppState.status = "识别任务中";
            return true;
        }

        if (unfinished != null) {
            logMode("收尾复查仍看到未完成任务，但已达到复查轮次上限；为避免循环，继续领奖并结束");
        } else {
            logMode("收尾复查：顶部未发现黑名单外的未完成任务，开始检查奖励");
        }

        AppState.status = "收尾领奖";
        logTaskPanelVariant(topSnap);

        // 经典版只有明确看到“今日累计奖励”时才点击三个奖励槽。
        // 不再因为固定标题“赚金币抵钱”存在就用兜底坐标点击，避免在列表底部误点第一条任务。
        if (hasClassicDailyRewardHeader(topSnap)) {
            tapClassicRewardSlots(topSnap);
        }

        int claimed = 0;
        String lastClaimFrame = "";
        int unchanged = 0;
        for (int i = 0; i < 8 && !stopRequested; i++) {
            OcrEngine.Snapshot snap = ocr.captureTaskPanel();
            int[] claim = findVisibleClaimPoint(snap);
            if (claim == null) break;

            String frame = compactPanelSignature(snap);
            if (frame.equals(lastClaimFrame)) unchanged++; else unchanged = 0;
            lastClaimFrame = frame;
            if (unchanged >= 2) {
                logMode("领奖按钮画面连续未变化，停止重复点击");
                break;
            }

            shellTap(claim[0], claim[1]);
            claimed++;
            logMode("收尾领奖：点击可领取奖励 (" + claim[0] + ", " + claim[1] + ")");
            sleep(850);
        }

        logMode(claimed > 0
                ? "收尾领奖完成：额外点击可领取按钮 " + claimed + " 次"
                : "收尾领奖完成：未发现额外可领取按钮");
        AppState.status = "识别任务中";
        return false;
    }

    /**
     * 收尾阶段始终至少执行一次向上滑动，不再依赖固定标题判断“已经到顶”。
     * 经典版的“赚金币抵钱”标题在列表滚到底时仍然可见，旧逻辑因此会误判已经在顶部。
     */
    private OcrEngine.Snapshot rewindTaskPanelToTop() throws Exception {
        String lastSignature = "";
        int unchanged = 0;
        OcrEngine.Snapshot snap = null;

        for (int i = 1; i <= 8 && !stopRequested; i++) {
            snap = ocr.captureTaskPanel();
            String all = normalizeOcr(snap.allText());

            // 第一轮无条件滑一下，确保经典版不会因为固定标题而直接进入领奖。
            if (i > 1 && isStrongRewardPanelTop(all)) {
                logMode("已确认回到任务面板顶部");
                break;
            }

            String sig = compactPanelSignature(snap);
            if (!sig.isEmpty() && sig.equals(lastSignature)) unchanged++; else unchanged = 0;
            lastSignature = sig;
            if (i > 2 && unchanged >= 2) {
                logMode("连续两次向上滑动后画面未变化，按当前画面视为顶部");
                break;
            }

            logMode("收尾：向上返回任务面板顶部 (" + i + "/8)");
            shellSwipeToTop(snap.width, snap.height);
            sleep(650);
        }

        if (stopRequested) return snap;
        snap = ocr.captureTaskPanel();
        logMode("已回到顶部，开始复查任务与奖励");
        return snap;
    }

    private boolean isStrongRewardPanelTop(String normalizedAll) {
        if (normalizedAll == null) return false;
        // 旧版顶部的强标志：今日累计奖励。
        if (normalizedAll.contains("今日累计奖励")) return true;
        // 新版顶部的强标志：完成下方任务得额外金币。
        return normalizedAll.contains("完成下方任务得额外金币")
                || (normalizedAll.contains("完成下方任务") && normalizedAll.contains("金币"));
    }

    private boolean hasClassicDailyRewardHeader(OcrEngine.Snapshot snap) {
        return snap != null && normalizeOcr(snap.allText()).contains("今日累计奖励");
    }

    private boolean isRewardPanelTop(String normalizedAll) {
        if (normalizedAll == null) return false;
        return normalizedAll.contains("今日累计奖励")
                || normalizedAll.contains("赚金币抵钱")
                || normalizedAll.contains("今日速赚")
                || (normalizedAll.contains("今日速") && normalizedAll.contains("淘金币"))
                || normalizedAll.contains("完成下方任务得额外金币")
                || (normalizedAll.contains("完成下方任务") && normalizedAll.contains("金币"));
    }

    private boolean isClassicRewardPanel(OcrEngine.Snapshot snap) {
        if (snap == null) return false;
        String all = normalizeOcr(snap.allText());
        return all.contains("今日累计奖励") || all.contains("赚金币抵钱");
    }

    private void logTaskPanelVariant(OcrEngine.Snapshot snap) {
        if (snap == null) return;
        String all = normalizeOcr(snap.allText());
        if (all.contains("今日累计奖励") || all.contains("赚金币抵钱")) {
            logMode("界面识别：经典‘赚金币抵钱’任务面板");
        } else if (all.contains("今日速赚") || (all.contains("今日速") && all.contains("淘金币"))
                || (all.contains("完成下方任务") && all.contains("金币"))) {
            logMode("界面识别：新版‘今日速赚’任务面板");
        } else {
            logMode("界面识别：未明确区分新旧版，继续使用通用 OCR 流程");
        }
    }

    private void tapClassicRewardSlots(OcrEngine.Snapshot snap) throws Exception {
        int anchorY = -1;
        for (OcrEngine.OcrRow row : snap.rows) {
            if (normalizeOcr(row.text).contains("今日累计奖励")) {
                anchorY = row.bounds.centerY();
                break;
            }
        }
        if (anchorY < 0) {
            logMode("经典版收尾：未明确识别到‘今日累计奖励’，为避免误点任务，不使用坐标兜底");
            return;
        }

        int[] xs = new int[]{
                (int) (snap.width * 0.43f),
                (int) (snap.width * 0.66f),
                (int) (snap.width * 0.87f)
        };
        logMode("经典版收尾：检查‘今日累计奖励’三个奖励槽");
        for (int x : xs) {
            if (stopRequested) return;
            shellTap(x, anchorY);
            sleep(500);
        }
    }

    /**
     * 查找明确的领取动作。旧版“立即领取”位于顶部奖励条中部，
     * 普通“领取奖励”位于任务行右侧，两者采用不同的安全点击 x。
     */
    private int[] findVisibleClaimPoint(OcrEngine.Snapshot snap) {
        if (snap == null) return null;
        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (t.contains("立即领取")) {
                int x;
                if (t.length() <= 8) x = row.bounds.centerX();
                else x = (int) (snap.width * 0.43f);
                return new int[]{Math.max(24, Math.min(snap.width - 24, x)), row.bounds.centerY()};
            }
        }
        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (t.contains("领取奖励")) {
                int x = Math.max((int) (snap.width * 0.86f), row.bounds.right - 8);
                return new int[]{Math.min(snap.width - 24, x), row.bounds.centerY()};
            }
        }
        return null;
    }

    private void shellSwipeToTop(int width, int height) throws Exception {
        int x = Math.max(120, (int) (width * 0.50f));
        int y1 = (int) (height * 0.30f);
        int y2 = (int) (height * 0.82f);
        ShizukuShell.Result r = ShizukuShell.exec(
                "input swipe " + x + " " + y1 + " " + x + " " + y2 + " 650"
        );
        if (!r.ok()) throw new IllegalStateException("返回任务顶部失败: " + r.output);
    }

    private String compactPanelSignature(OcrEngine.Snapshot snap) {
        if (snap == null) return "";
        String all = normalizeOcr(snap.allText());
        if (all.length() > 220) all = all.substring(0, 220);
        return all;
    }

    private void shellTap(int x, int y) throws Exception {
        ShizukuShell.Result r = ShizukuShell.exec("input tap " + x + " " + y);
        if (!r.ok()) throw new IllegalStateException("input tap 失败: " + r.output);
    }

    private void shellSwipeList(int width, int height) throws Exception {
        int x = Math.max(120, (int) (width * 0.278f));
        int y1 = (int) (height * 0.844f);
        int y2 = (int) (height * 0.281f);
        ShizukuShell.Result r = ShizukuShell.exec("input swipe " + x + " " + y1 + " " + x + " " + y2 + " 1000");
        if (!r.ok()) throw new IllegalStateException("列表滚动失败: " + r.output);
    }

    private String signature(List<TaskParser.TaskItem> tasks) {
        StringBuilder sb = new StringBuilder();
        for (TaskParser.TaskItem t : tasks) sb.append(t.name).append('|');
        return sb.toString();
    }

    private boolean containsVerification(String text) {
        if (text == null) return false;
        return text.contains("请拖动下方滑块完成验证") || text.contains("验证失败") || text.contains("通过验证以确保正常访问");
    }

    private boolean isCoinHomeVisibleForNavigation() {
        if (!TB.equals(currentPackage())) return false;
        try {
            OcrEngine.Snapshot snap = ocr.captureNavigation();
            boolean hit = looksLikeCoinHomeSnapshot(snap);
            if (hit) {
                logMode("OCR 已确认当前为淘金币首页（特征分=" + coinHomeEvidenceScore(snap) + ")");
            }
            return hit;
        } catch (Throwable e) {
            logMode("淘金币首页 OCR 确认失败: " + e.getMessage());
            return false;
        }
    }

    private boolean tapOcrTextContains(String needle, boolean preferRight) {
        try {
            OcrEngine.Snapshot snap = ocr.captureNavigation();
            int[] p = findTextPoint(snap, needle, preferRight);
            if (p == null) return false;
            shellTap(p[0], p[1]);
            return true;
        } catch (Throwable e) {
            logMode("OCR 导航兜底失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * v1.1 通用遮挡弹窗恢复。
     *
     * 不尝试维护“广告名称黑名单”，因为淘宝弹窗样式/文案变化非常频繁。
     * 这里使用两类证据：
     * 1) 高特异弹窗文案（组件、额外抵扣、出行、闪购、抵金等）；
     * 2) 屏幕中部出现明显的单一 CTA，而背景仍能看到淘金币/任务页上下文。
     *
     * 命中后只按一次系统 Back，再由下一张真实截图验证页面是否恢复。
     * Back 比点击未知位置的 X 更安全，可避免 X 位置变化时误触广告内容。
     */
    private boolean dismissBlockingPopupIfPresent(OcrEngine.Snapshot snap, String stage) {
        if (snap == null || stopRequested) return false;
        if (!TB.equals(currentPackage())) return false;

        // §3 P0：高置信促销覆盖层（“看…得能量”）必须先于 KEEP_PANEL 面板保护判定。
        // 该弹窗会遮住能量面板/赛车首页，但背景的任务行、标题仍能被 OCR 看到，
        // 若先走面板保护会把真正的阻塞弹窗放行。判定要求两个相互独立且空间位置
        // 合理的证据（标题“看…得能量” + “去浏览加抵好物”或“浏览商品…得”），
        // 单独的 能量/立即领取/奖励 字样绝不触发。默认不点击“去浏览加抵好物”。
        if (detectPromoOverlay(snap)) {
            return handlePromoOverlay(snap, stage);
        }

        // P0：合法能量任务面板优先于普通弹窗关闭。
        // 能量模式下，若当前截图有可信能量任务面板证据（明确标题锚点 + 奖励/任务动作
        // 组合，或已认证后的列表上下文），绝不可执行 Back 关闭；否则会把已成功打开的
        // “做任务赚能量”面板误当成广告弹窗关掉，陷入“打开→关闭→重试”死循环。
        if (currentMode == TaskMode.DOUBLE11_ENERGY) {
            boolean trusted = TaskParser.isTrustedEnergyTaskPanel(snap.rows, snap.height)
                    || isEnergyTaskListContext(snap);
            if (trusted) {
                logMode("弹窗判定 page=" + stage + " mode=能量 trusted=true action=KEEP_PANEL"
                        + "（合法能量任务面板，不执行 Back）");
                return false;
            }
        }

        String all = normalizeOcr(snap.allText());
        List<String> hits = new ArrayList<>();
        int score = 0;

        // 高特异文案：这些内容通常只会出现在覆盖式促销/引导弹窗中。
        String[] strong = new String[]{
                "已尝试添加组件", "再次添加", "前往桌面", "添加失败",
                "一键享受金币额外抵扣", "金币额外抵扣", "你加购的",
                "出行玩乐", "订票订酒店", "闪购福利"
        };
        for (String k : strong) {
            if (all.contains(normalizeOcr(k))) { score += 2; hits.add("强:" + k); }
        }
        // 这些词在普通淘宝页面也可能出现，只作为弱证据，不能单独触发 Back。
        String[] weak = new String[]{"淘宝闪购", "加抵金", "抵金", "额外抵", "去看看"};
        for (String k : weak) {
            if (all.contains(normalizeOcr(k))) { score += 1; hits.add("弱:" + k); }
        }

        // 背景仍像淘金币/任务页时，中间出现大 CTA 更像是“盖在原页面上的弹窗”。
        boolean backgroundContext = looksLikeCoinHomeSnapshot(snap)
                || taskPanelVisibleForMode(snap)
                || isTaskContextForMode(snap);
        boolean centeredCta = false;
        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (t.isEmpty()) continue;
            int cx = row.bounds.centerX();
            int cy = row.bounds.centerY();
            boolean inCenter = cx > snap.width * 0.22f && cx < snap.width * 0.78f
                    && cy > snap.height * 0.32f && cy < snap.height * 0.82f;
            if (!inCenter) continue;
            boolean actionText = t.contains("去看看") || t.contains("前往") || t.contains("再次")
                    || t.contains("立即") || t.contains("一键") || t.contains("添加")
                    || t.contains("使用") || t.contains("查看") || t.contains("开启");
            if (actionText && row.bounds.width() > snap.width * 0.18f) {
                centeredCta = true;
                break;
            }
        }
        if (backgroundContext && centeredCta) { score += 2; hits.add("背景+居中CTA"); }

        String hitDesc = hits.isEmpty() ? "无" : String.join(",", hits);
        if (score < 3) {
            logMode("弹窗判定 page=" + stage + " mode=" + currentMode.label
                    + " trusted=false hits=[" + hitDesc + "] centeredCta=" + centeredCta
                    + " score=" + score + " action=KEEP");
            return false;
        }
        if (popupDismissCount >= 6) {
            logMode("弹窗判定 page=" + stage + " mode=" + currentMode.label
                    + " hits=[" + hitDesc + "] score=" + score
                    + " 已达自动关闭上限，交给页面恢复流程");
            return false;
        }

        popupDismissCount++;
        logMode("弹窗判定 page=" + stage + " mode=" + currentMode.label
                + " trusted=false hits=[" + hitDesc + "] centeredCta=" + centeredCta
                + " score=" + score + " action=BACK（" + popupDismissCount + "/6）");
        ShizukuShell.exec("input keyevent KEYCODE_BACK");
        return true;
    }

    /**
     * “看…得能量”促销覆盖层检测。要求两个相互独立且空间位置合理的证据：
     * 1) 屏幕上半部出现“看…得能量”标题（如 看抵扣快闪得能量 / 看宠物好货得能量）；
     * 2) 中下部出现“去浏览加抵好物”CTA 或“浏览商品…得”文案。
     * 单独的 能量/立即领取/奖励/去完成 字样绝不触发，避免误伤合法能量任务面板。
     */
    private boolean detectPromoOverlay(OcrEngine.Snapshot snap) {
        if (snap == null) return false;
        boolean titleHit = false;
        boolean ctaHit = false;
        boolean browseNoteHit = false;
        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (t.isEmpty()) continue;
            int cy = row.bounds.centerY();
            if (!titleHit && cy < snap.height * 0.50f
                    && t.length() <= 18
                    && PROMO_OVERLAY_TITLE.matcher(t).find()) {
                titleHit = true;
            }
            if (!ctaHit && cy > snap.height * 0.42f
                    && (t.contains("去浏览加抵好物") || fuzzyContains(t, "去浏览加抵好物", 1))) {
                ctaHit = true;
            }
            if (!browseNoteHit && cy > snap.height * 0.20f && cy < snap.height * 0.65f
                    && t.contains("浏览商品") && t.contains("得")) {
                browseNoteHit = true;
            }
            if (titleHit && (ctaHit || browseNoteHit)) return true;
        }
        return false;
    }

    /**
     * 促销覆盖层处理：BACK 优先（最多 2 次），仍无效且存在安全 X 证据时点击一次 X，
     * 每次动作后截图复查；全程不点击“去浏览加抵好物”购物 CTA。总上限 3 次，
     * 超限后交由页面恢复流程/人工处理。
     */
    private boolean handlePromoOverlay(OcrEngine.Snapshot snap, String stage) {
        if (promoOverlayActions >= 3) {
            logMode("promoOverlay=true page=" + stage
                    + " action=GIVE_UP（已达 3 次处理上限，不点购物 CTA，交由页面恢复/人工处理）");
            return false;
        }
        promoOverlayActions++;
        if (promoOverlayActions <= 2) {
            logMode("promoOverlay=true page=" + stage
                    + " evidence=[看…得能量标题 + 去浏览加抵好物/浏览商品15s得] action=BACK（"
                    + promoOverlayActions + "/3）");
            ShizukuShell.exec("input keyevent KEYCODE_BACK");
        } else {
            int[] x = findPromoCloseX(snap);
            if (x == null) {
                promoOverlayActions--;
                logMode("promoOverlay=true page=" + stage
                        + " action=SKIP（Back 未生效且未定位到安全的关闭 X；不点击购物 CTA）");
                return false;
            }
            logMode("promoOverlay=true page=" + stage
                    + " action=SAFE_X（" + x[0] + "," + x[1] + "）（" + promoOverlayActions + "/3）");
            try {
                shellTap(x[0], x[1]);
            } catch (Exception tapError) {
                logMode("promoOverlay 点击安全 X 失败: " + tapError.getMessage());
            }
        }
        try {
            sleep(900);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        boolean recovered = false;
        try {
            OcrEngine.Snapshot check = ocr.captureNavigation();
            recovered = !detectPromoOverlay(check);
        } catch (Throwable ignored) {}
        logMode("promoOverlay 复查: " + (recovered ? "覆盖层已消失" : "覆盖层仍在或未确认，等待下一轮处理"));
        return true;
    }

    /**
     * 在促销覆盖层上定位“关闭 X”：仅接受 OCR 元素级、单字符 X/×/✕、
     * 尺寸小且位于弹窗边缘区域的目标；找不到返回 null，不做坐标猜测。
     */
    private int[] findPromoCloseX(OcrEngine.Snapshot snap) {
        if (snap == null || snap.elements == null) return null;
        for (OcrEngine.OcrRow e : snap.elements) {
            String t = normalizeOcr(e.text);
            if (!(t.equals("X") || t.equals("×") || t.equals("x") || t.equals("✕"))) continue;
            if (e.bounds.width() > snap.width * 0.08f) continue;
            int cy = e.bounds.centerY();
            if (cy < snap.height * 0.25f || cy > snap.height * 0.95f) continue;
            int cx = e.bounds.centerX();
            if (cx < snap.width * 0.10f || cx > snap.width * 0.90f) continue;
            return new int[]{cx, cy};
        }
        return null;
    }

    /**
     * 安全裁剪的 OCR 摘要：只输出前若干行文字并限制长度，用于两次认证失败后的诊断，
     * 不包含完整截图、账号、地址、订单或 token 等敏感信息。
     */
    private String summarizeOcrSnippet(OcrEngine.Snapshot snap) {
        if (snap == null) return "<无截图>";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t);
            count++;
            if (count >= 14 || sb.length() > 240) break;
        }
        return sb.length() == 0 ? "<无文字>" : sb.toString();
    }

    private String normalizeOcr(String text) {
        if (text == null) return "";
        return text.replace('額', '额')
                .replace('幣', '币')
                .replace('賺', '赚')
                .replace('獎', '奖')
                .replace('專', '专')
                .replace('領', '领')
                .replace('勵', '励')
                .replaceAll("\\s+", "")
                .replace("·", "")
                .trim();
    }

    private boolean isTaskPanelVisibleForNavigation() {
        if (!TB.equals(currentPackage())) return false;
        try {
            OcrEngine.Snapshot snap = ocr.captureNavigation();
            boolean hit = taskPanelVisibleInSnapshot(snap);
            if (hit) logMode("OCR 已确认‘今日速赚/更多金币’等任务弹窗已打开");
            return hit;
        } catch (Throwable e) {
            logMode("任务弹窗 OCR 确认失败: " + e.getMessage());
            return false;
        }
    }

    private boolean taskPanelVisibleInSnapshot(OcrEngine.Snapshot snap) {
        if (snap == null) return false;
        String all = normalizeOcr(snap.allText());
        boolean quickEarn = all.contains("今日速赚")
                || (all.contains("今日速") && all.contains("淘金币"));
        boolean rewardHeader = all.contains("完成下方任务得额外金币")
                || (all.contains("完成下方任务") && all.contains("金币"));
        boolean classic = all.contains("赚金币抵钱") || all.contains("今日累计奖励");
        return quickEarn
                || rewardHeader
                || all.contains("更多金币等你赚")
                || all.contains("今日快速赚奖励已拿完")
                || all.contains("快速赚奖励已拿完")
                || (all.contains("记得明天再来") && all.contains("展开"))
                || classic;
    }

    /**
     * v2.2 展开控件定位。
     *
     * 淘宝当前页面常把“更多金币等你赚”和“展开”分别绘制；缩放 OCR 后“展开”偶尔漏字。
     * 因此先找明确“展开”，再以高度特异的“更多金币等你赚”卡片作为锚点点击同一行右侧。
     * 这个兜底只用于已经确认打开淘金币任务弹窗后的阶段，不会在淘宝首页/商品页使用。
     */
    private int[] findExpandTaskPanelPoint(OcrEngine.Snapshot snap) {
        if (snap == null) return null;

        int[] exact = findTextPoint(snap, "展开", true);
        if (exact != null && exact[1] > snap.height * 0.42f) return exact;

        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            boolean anchor = t.contains("更多金币等你赚")
                    || (t.contains("更多金币") && t.contains("等你赚"));
            if (!anchor) continue;
            int y = row.bounds.centerY();
            if (y < snap.height * 0.50f || y > snap.height * 0.94f) continue;
            int x = Math.min(snap.width - 28, Math.max((int) (snap.width * 0.86f), row.bounds.right + 36));
            return new int[]{x, y};
        }
        return null;
    }

    private int[] findTextPoint(OcrEngine.Snapshot snap, String needle, boolean preferRight) {
        if (snap == null) return null;
        String n = normalizeOcr(needle);

        // Prefer an exact/near-exact ML Kit Element first. On some phones ML Kit merges two or
        // more shortcut labels into one Element (for example “领淘金币阿里拍卖”). Using the merged
        // Element's center can then click the shortcut to the right, so a long Element is handled
        // with substring-relative geometry instead of centerX().
        if (snap.elements != null) {
            for (OcrEngine.OcrRow element : snap.elements) {
                String t = normalizeOcr(element.text);
                if (!t.equals(n) && !(t.contains(n) && t.length() <= n.length() + 1)) continue;
                int[] point = pointInsideMatchedText(element.bounds, t, n, snap.width, snap.height, preferRight);
                if (point != null) return point;
            }
            for (OcrEngine.OcrRow element : snap.elements) {
                String t = normalizeOcr(element.text);
                if (!t.contains(n)) continue;
                int[] point = pointInsideMatchedText(element.bounds, t, n, snap.width, snap.height, preferRight);
                if (point != null) return point;
            }
        }

        for (OcrEngine.OcrRow row : snap.rows) {
            String rowText = normalizeOcr(row.text);
            if (!rowText.contains(n)) continue;
            int[] point = pointInsideMatchedText(row.bounds, rowText, n, snap.width, snap.height, preferRight);
            if (point != null) return point;
        }
        return null;
    }

    private int[] pointInsideMatchedText(Rect b, String fullText, String needle,
                                         int screenWidth, int screenHeight, boolean preferRight) {
        if (b == null || fullText == null || needle == null || needle.isEmpty()) return null;
        int hit = fullText.indexOf(needle);
        if (hit < 0) return null;

        int x;
        if (preferRight) {
            x = Math.min(screenWidth - 24, Math.max(b.right - 8, (int) (screenWidth * 0.84f)));
        } else if (fullText.length() > needle.length() + 1) {
            // Works for both merged Lines and merged Elements. Estimate where the target substring
            // sits inside the OCR bounding box. This is especially important for home shortcuts:
            // “领淘金币阿里拍卖红包签到” must click the first label, not the box center.
            float charCenter = hit + needle.length() * 0.5f;
            float ratio = charCenter / Math.max(1f, fullText.length());
            x = b.left + Math.round(b.width() * ratio);
        } else {
            x = b.centerX();
        }

        x = Math.max(24, Math.min(screenWidth - 24, x));
        int y = b.centerY();
        if (x <= 0 || y <= 0 || x >= screenWidth || y >= screenHeight) return null;
        return new int[]{x, y};
    }

    private String currentPackage() {
        return currentForeground().packageName;
    }

    private ForegroundState currentForeground() {
        String[] commands = new String[]{
                "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' | head -n 1",
                "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1",
                "dumpsys activity top | grep 'ACTIVITY ' | head -n 1"
        };
        Pattern componentPattern = Pattern.compile("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)");
        for (String command : commands) {
            try {
                ShizukuShell.Result r = ShizukuShell.exec(command);
                Matcher m = componentPattern.matcher(r.output);
                if (!m.find()) continue;
                String pkg = m.group(1);
                String act = m.group(2);
                if (act != null && act.startsWith(".")) act = pkg + act;
                return new ForegroundState(pkg, act);
            } catch (Throwable ignored) {}
        }
        return new ForegroundState(null, null);
    }

    private boolean cancelBrowserOpenDialogIfPresent() {
        try {
            OcrEngine.Snapshot snap = ocr.capture();
            String all = normalizeOcr(snap.allText());
            if (!all.contains("浏览器打开")) return false;
            for (OcrEngine.OcrRow row : snap.rows) {
                if (!normalizeOcr(row.text).contains("取消")) continue;
                Rect r = row.bounds;
                shellTap(r.centerX(), r.centerY());
                logMode("OCR 检测到‘浏览器打开’弹窗，已点击取消");
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void shellBack() throws Exception {
        ShizukuShell.Result r = ShizukuShell.exec("input keyevent KEYCODE_BACK");
        if (!r.ok()) throw new IllegalStateException("系统后退失败: " + r.output);
    }

    private void sleep(long ms) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + ms;
        while (!stopRequested && SystemClock.elapsedRealtime() < end) {
            Thread.sleep(Math.min(250, Math.max(1, end - SystemClock.elapsedRealtime())));
        }
    }

    private static final class ForegroundState {
        final String packageName;
        final String activityName;

        ForegroundState(String packageName, String activityName) {
            this.packageName = packageName;
            this.activityName = activityName;
        }

        boolean valid() {
            return packageName != null && !packageName.isEmpty();
        }

        boolean isPackage(String pkg) {
            return pkg != null && pkg.equals(packageName);
        }

        boolean activityContains(String text) {
            return text != null && activityName != null && activityName.contains(text);
        }

        boolean isTaobaoTaskContainer() {
            return isPackage(TB) && activityContains("com.taobao.themis.container.app.TMSActivity");
        }

        @Override public String toString() {
            return String.valueOf(packageName) + "--" + String.valueOf(activityName);
        }
    }

}
