package com.klze.nextcard.core.effect;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 一次判定能看到的<b>事实快照</b>（《00》《06》里那些"什么时候才成立"的全部输入）。
 *
 * <p>为什么要中间这一层：{@link Predicate} 必须能在无头环境里被证明（本项目验不了客户端渲染，
 * 也不该靠人开游戏点两下）。只要判据直接读 {@code Entity}/{@code Level}，"未察觉到底怎么算"
 * 就只能靠玩到那一发来验收。事实快照把"读世界"留在边界上，判据本身变成纯函数。</p>
 *
 * <p>字段一律是<em>已经换算好的无量纲量</em>：{@code angleOffBack} 是"离目标背面中线多少度"
 * （0 = 正背后），{@code light} 是 0–15，{@code noise} 是发声量。接管点负责换算，判据负责比较。</p>
 *
 * <p>{@link #unseen()} 之类<em>不是</em>事实——那是三条事实的组合结论，属于
 * {@link Predicates#named}（组合子写一次，全卡表共用）。这里只放不能再拆的原始量。</p>
 */
public record Facts(double attackerHpRatio, double targetHpRatio, double angleOffFront, double distance,
                    int light, double noise, double stillSeconds, double chargeSeconds,
                    String targetKind, Set<String> flags, Map<String, Integer> layers,
                    Map<String, Integer> counts) {

    /** 开关类事实（只有这几种，注册表按名字读，写错名字加载期就红）。 */
    public static final Set<String> FLAG_NAMES = Set.of(
            "moving", "blocking", "charging", "parried", "fatal", "sneaking",
            "appeared_from_outside_view", "target_controlled", "target_attracted_elsewhere", "target_blind");

    /** 目标类别取值（《00》处决段：普通敌人才可能被斩杀；精英与 boss 只吃「重创」）。 */
    public static final Set<String> TARGET_KINDS = Set.of("normal", "elite", "boss", "player");

    public Facts {
        flags = flags == null ? Set.of() : Set.copyOf(new TreeSet<>(flags));
        layers = layers == null ? Map.of() : new TreeMap<>(layers);
        counts = counts == null ? Map.of() : new TreeMap<>(counts);
        if (!TARGET_KINDS.contains(targetKind)) {
            throw new IllegalArgumentException("未知目标类别: " + targetKind + "，可用 " + TARGET_KINDS);
        }
        for (String flag : flags) {
            if (!FLAG_NAMES.contains(flag)) {
                throw new IllegalArgumentException("未知事实开关: " + flag + "，可用 " + FLAG_NAMES);
            }
        }
    }

    /** 什么都没有的一手（缺的数据一律按"没发生"，不是按 0 猜）。 */
    public static final Facts NONE = new Facts(1.0, 1.0, 0.0, 0.0, 15, 0.0, 0.0, 0.0,
            "normal", Set.of(), Map.of(), Map.of());

    public boolean flag(String name) {
        return flags.contains(name);
    }

    public int layers(String stackId) {
        return layers.getOrDefault(stackId, 0);
    }

    public int count(String event) {
        return counts.getOrDefault(event, 0);
    }

    /** 离目标<em>背面</em>中线多少度（0 = 正背后）：背刺与"未察觉"的第①条都按它判。 */
    public double angleOffBack() {
        return Math.abs(180.0 - angleOffFront) % 360.0;
    }

    /**
     * 把"这个持卡人当前的层数账"贴到事实上——`stacks` 条件在世界里唯一的来源。
     *
     * <p>为什么不直接从 {@code CounterStore} 读进判定：判定必须是只读快照的纯函数（见类注释）。
     * 接管点在叫醒触发器之前把账本贴一次，于是"有 3 层毒才怎样"这类条件既能在无头环境里
     * 用 builder 喂死，也能在游戏里跟着真实层数走。</p>
     *
     * <p>同名的已有项<em>以传入的为准</em>（贴的是当下真相），其余项原样保留。</p>
     */
    public Facts withLayers(Map<String, Integer> current) {
        if (current == null || current.isEmpty()) {
            return this;
        }
        Map<String, Integer> merged = new TreeMap<>(layers);
        merged.putAll(current);
        return new Facts(attackerHpRatio, targetHpRatio, angleOffFront, distance, light, noise,
                stillSeconds, chargeSeconds, targetKind, flags, merged, counts);
    }

    /** 这一发是否在目标背后 {@code halfAngle}° 的扇区里（《00》背后 120° = 半角 60°）。 */
    public boolean fromBehind(double halfAngle) {
        return angleOffBack() <= halfAngle;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 只写关心的那几项，其余留默认（测试与接管点都用它，避免 12 个位置参数串错）。 */
    public static final class Builder {
        private double attackerHpRatio = 1.0;
        private double targetHpRatio = 1.0;
        private double angleOffFront = 0.0;
        private double distance = 0.0;
        private int light = 15;
        private double noise = 0.0;
        private double stillSeconds = 0.0;
        private double chargeSeconds = 0.0;
        private String targetKind = "normal";
        private final Set<String> flags = new LinkedHashSet<>();
        private final Map<String, Integer> layers = new TreeMap<>();
        private final Map<String, Integer> counts = new TreeMap<>();

        public Builder attackerHp(double ratio) {
            this.attackerHpRatio = ratio;
            return this;
        }

        public Builder targetHp(double ratio) {
            this.targetHpRatio = ratio;
            return this;
        }

        /** 入射角：0 = 打在正面，180 = 正背后。 */
        public Builder angleOffFront(double degrees) {
            this.angleOffFront = degrees;
            return this;
        }

        public Builder distance(double blocks) {
            this.distance = blocks;
            return this;
        }

        public Builder light(int level) {
            this.light = level;
            return this;
        }

        public Builder noise(double amount) {
            this.noise = amount;
            return this;
        }

        public Builder stillSeconds(double seconds) {
            this.stillSeconds = seconds;
            return this;
        }

        public Builder chargeSeconds(double seconds) {
            this.chargeSeconds = seconds;
            return this;
        }

        public Builder targetKind(String kind) {
            this.targetKind = kind;
            return this;
        }

        public Builder with(String flag) {
            this.flags.add(flag);
            return this;
        }

        public Builder layers(String stackId, int layers) {
            this.layers.put(stackId, layers);
            return this;
        }

        public Builder count(String event, int times) {
            this.counts.put(event, times);
            return this;
        }

        public Facts build() {
            return new Facts(attackerHpRatio, targetHpRatio, angleOffFront, distance, light, noise,
                    stillSeconds, chargeSeconds, targetKind, flags, layers, counts);
        }
    }
}
