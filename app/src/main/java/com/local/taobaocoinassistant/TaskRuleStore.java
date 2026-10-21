package com.local.taobaocoinassistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 用户可编辑的任务规则。所有匹配均为“包含关键词”，一行一个关键词。
 * 规则优先级：跳过 > 指定普通浏览 > 跨应用 > 搜索 > 默认普通浏览。
 */
public final class TaskRuleStore {
    private static final String PREFS = "task_rules_v11";
    private static final String KEY_SKIP = "skip";
    private static final String KEY_EXTERNAL = "external";
    private static final String KEY_SEARCH = "search";
    private static final String KEY_BROWSE = "browse";
    // 规则迁移版本：一次性、非破坏性地为老版本补充新增的默认黑名单词。
    private static final String KEY_MIGRATION_VERSION = "skip_migration_version";
    private static final int MIGRATION_VERSION = 1;
    private static final List<String> MIGRATION_ADDITIONS_V1 = Arrays.asList("淘特", "快手", "微博");

    public static final String DEFAULT_SKIP = String.join("\n", Arrays.asList(
            "淘金币趣味课堂",
            "百度极速版",
            "下单",
            "充值",
            "助力",
            "评价",
            "答题",
            "游戏",
            "闪购",
            "邀请",
            "开卡",
            "限时",
            "领优惠",
            "点击商品",
            "UC",
            "菜鸟",
            "消消乐",
            "闲鱼",
            "百度",
            "课堂",
            "桌面",
            "回访",
            "爱心蛋",
            "心蛋",
            "极速版",
            "通知",
            "淘特",
            "快手",
            "微博"
    ));

    public static final String DEFAULT_EXTERNAL = String.join("\n", Arrays.asList(
            "蚂蚁森林",
            "蚂蚁庄园",
            "神奇海洋",
            "芝麻信用",
            "商家积分",
            "金豆夺宝",
            "支付宝农场",
            "支付宝",
            "蚂蚁"
    ));

    public static final String DEFAULT_SEARCH = String.join("\n", Arrays.asList(
            "搜一搜",
            "搜索",
            "心仪的宝贝"
    ));

    public static final String DEFAULT_BROWSE = "";

    private TaskRuleStore() {}

    public static Rules load(Context context) {
        ensureMigration(context);
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Rules(
                parseLines(sp.getString(KEY_SKIP, DEFAULT_SKIP)),
                parseLines(sp.getString(KEY_EXTERNAL, DEFAULT_EXTERNAL)),
                parseLines(sp.getString(KEY_SEARCH, DEFAULT_SEARCH)),
                parseLines(sp.getString(KEY_BROWSE, DEFAULT_BROWSE))
        );
    }

    public static void save(Context context, String skip, String external, String search, String browse) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SKIP, cleanForStorage(skip))
                .putString(KEY_EXTERNAL, cleanForStorage(external))
                .putString(KEY_SEARCH, cleanForStorage(search))
                .putString(KEY_BROWSE, cleanForStorage(browse))
                .apply();
    }

    public static void reset(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static String getRawSkip(Context context) { ensureMigration(context); return raw(context, KEY_SKIP, DEFAULT_SKIP); }
    public static String getRawExternal(Context context) { return raw(context, KEY_EXTERNAL, DEFAULT_EXTERNAL); }
    public static String getRawSearch(Context context) { return raw(context, KEY_SEARCH, DEFAULT_SEARCH); }
    public static String getRawBrowse(Context context) { return raw(context, KEY_BROWSE, DEFAULT_BROWSE); }

    private static String raw(Context context, String key, String def) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, def);
    }

    /**
     * 一次性、可追踪、非破坏性规则迁移。
     * 仅当存在用户已保存的 skip 时才把缺失的新增默认词追加一次（保留原词与原顺序）；
     * 全新安装直接使用扩展后的 DEFAULT_SKIP，无需重复追加。用户后续主动删除这些词，
     * 因迁移版本已记录，不会被再次加回。
     */
    private static synchronized void ensureMigration(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int done = sp.getInt(KEY_MIGRATION_VERSION, 0);
        if (done >= MIGRATION_VERSION) return;

        // 全新安装（从未保存过 skip）：直接用扩展后的 DEFAULT_SKIP，无需追加。
        if (!sp.contains(KEY_SKIP)) {
            sp.edit().putInt(KEY_MIGRATION_VERSION, MIGRATION_VERSION).apply();
            return;
        }

        String existing = sp.getString(KEY_SKIP, "");
        List<String> current = parseLines(existing);
        List<String> toAdd = new ArrayList<>();
        for (String w : MIGRATION_ADDITIONS_V1) {
            if (!current.contains(normalizeKeyword(w))) toAdd.add(w);
        }
        if (!toAdd.isEmpty()) {
            StringBuilder sb = new StringBuilder(existing.trim());
            for (String w : toAdd) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(w);
            }
            sp.edit().putString(KEY_SKIP, sb.toString())
                    .putInt(KEY_MIGRATION_VERSION, MIGRATION_VERSION).apply();
            AppState.log("BLACKLIST_MIGRATION v" + MIGRATION_VERSION
                    + " added=[" + String.join(",", toAdd) + "]");
        } else {
            sp.edit().putInt(KEY_MIGRATION_VERSION, MIGRATION_VERSION).apply();
        }
    }

    private static List<String> parseLines(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String line : raw.split("[\\r\\n]+")) {
            String s = normalizeKeyword(line);
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return Collections.unmodifiableList(out);
    }

    private static String cleanForStorage(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("[\\r\\n]+")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    static String normalizeKeyword(String s) {
        if (s == null) return "";
        return s.replace('（', '(')
                .replace('）', ')')
                .replace('／', '/')
                .replace('額', '额')
                .replace('幣', '币')
                .replace('賺', '赚')
                .replace('獎', '奖')
                .replace('專', '专')
                .replace('領', '领')
                .replace('勵', '励')
                .replaceAll("[\\s,，。.!！?？:：;；|丨》>《<·'\"`~、_-]+", "")
                .trim();
    }

    public static final class Rules {
        public final List<String> skip;
        public final List<String> external;
        public final List<String> search;
        public final List<String> browse;

        Rules(List<String> skip, List<String> external, List<String> search, List<String> browse) {
            this.skip = skip;
            this.external = external;
            this.search = search;
            this.browse = browse;
        }

        public String summary() {
            return "跳过" + skip.size() + " / 跨应用" + external.size() + " / 搜索" + search.size() + " / 普通浏览" + browse.size();
        }
    }
}
