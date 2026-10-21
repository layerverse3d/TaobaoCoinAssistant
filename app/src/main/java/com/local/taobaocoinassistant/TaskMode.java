package com.local.taobaocoinassistant;

/**
 * 自动化运行模式。
 *
 * <p>{@link #COIN} 保留 v1.1 原有淘金币行为；{@link #DOUBLE11_ENERGY} 为新增的
 * “双十一赚能量”独立入口。两个入口共用同一套 Shizuku + OCR 执行引擎，只在
 * 页面导航、面板认证、任务解析与收尾领奖等“页面判断”处按模式分支。</p>
 */
public enum TaskMode {
    COIN("金币"),
    DOUBLE11_ENERGY("能量");

    /** 日志与状态展示用中文标签。 */
    public final String label;

    TaskMode(String label) {
        this.label = label;
    }

    /** 从 Intent Extra 字符串安全解析；未知值一律回退金币模式。 */
    public static TaskMode fromName(String name) {
        if (name == null) return COIN;
        for (TaskMode m : values()) {
            if (m.name().equals(name)) return m;
        }
        return COIN;
    }
}
