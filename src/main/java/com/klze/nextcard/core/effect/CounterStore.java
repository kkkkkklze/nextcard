package com.klze.nextcard.core.effect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 层数 / 资源账本（引擎侧唯一的"值"存身之处）。
 *
 * <p>分工：{@link StackClause} 与 {@link Mechanics} 是<b>声明</b>（上限、时长、产出事件、每层映射），
 * 本类只存<b>值</b>——同一个资源绝不两处记，否则上限与到期会出现两份真相。
 * 上限/时长由调用方从折叠结果传进来（{@link Rule}），本类不回头读卡表。</p>
 *
 * <p>时间单位与声明侧一致，一律<b>秒</b>（{@code duration} / {@code at_seconds}）；落成游戏 tick
 * 是 MC 侧一处换算，不在这里做。</p>
 */
public final class CounterStore {

    /** 同一个持有者可同时持有多份来源，因此按 (持有者, 资源) 记账。 */
    public record Key(String holder, String resource) {
    }

    /**
     * 折叠后的生效规则。
     *
     * @param cap             上限；{@link StackClause#UNBOUNDED} 表示无上限
     * @param durationSeconds 每份层数的存在时长；0 表示不过期
     * @param expiry          时长语义（待内容侧定，见 {@link Expiry}）
     */
    public record Rule(double cap, double durationSeconds, Expiry expiry) {
    }

    /**
     * 时长的两种读法，都真实存在于卡表里，因此不做默认：
     * <ul>
     *   <li>{@link #PER_LAYER}——每次产出各计各的时，先到的先掉（"层数会随时间褪去"）。</li>
     *   <li>{@link #REFRESH_ALL}——再产出就把整份账重置计时，超时一次清空
     *       （"1.5 秒不攻击就清零"这一类）。</li>
     * </ul>
     */
    public enum Expiry {
        PER_LAYER, REFRESH_ALL
    }

    private record Ledger(List<Double> expiries, double fallbackExpiry) {
    }

    private final Map<Key, Ledger> ledgers = new LinkedHashMap<>();

    /** 当前层数。 */
    public double amount(Key key) {
        Ledger ledger = ledgers.get(key);
        return ledger == null ? 0.0 : ledger.expiries.size();
    }

    /**
     * 产出一批层数，受上限夹住。返回<b>实际收下</b>的数量——超出上限的部分被丢弃，
     * 调用方需要据此决定"满层触发"这类效果是否算作一次溢出转化。
     */
    public double gain(Key key, double count, Rule rule, double nowSeconds) {
        if (count <= 0) {
            return 0.0;
        }
        Ledger current = ledgers.get(key);
        int held = current == null ? 0 : current.expiries().size();
        double accepted = Math.min(count, remaining(held, rule.cap()));
        double expiresAt = rule.durationSeconds() <= 0 ? Double.POSITIVE_INFINITY : nowSeconds + rule.durationSeconds();

        List<Double> expiries = new ArrayList<>();
        if (rule.expiry() == Expiry.PER_LAYER && current != null) {
            expiries.addAll(current.expiries());   // 已有的层保留各自的到期时刻
        }
        long retimed = rule.expiry() == Expiry.REFRESH_ALL ? held + (long) accepted : (long) accepted;
        for (long i = 0; i < retimed; i++) {
            expiries.add(expiresAt);
        }
        ledgers.put(key, new Ledger(List.copyOf(expiries), expiresAt));
        return accepted;
    }

    /** 消费层数：从最早到期的那份开始扣（先到先失），返回实际扣掉的数量，永不使账本为负。 */
    public double spend(Key key, double count) {
        Ledger ledger = ledgers.get(key);
        if (ledger == null || count <= 0) {
            return 0.0;
        }
        double spent = Math.min(count, ledger.expiries.size());
        List<Double> left = new ArrayList<>(ledger.expiries.subList((int) spent, ledger.expiries.size()));
        if (left.isEmpty()) {
            ledgers.remove(key);
        } else {
            ledgers.put(key, new Ledger(List.copyOf(left), ledger.fallbackExpiry()));
        }
        return spent;
    }

    /** 清掉已过期的层数，返回被清掉的数量。 */
    public int expire(Key key, double nowSeconds) {
        Ledger ledger = ledgers.get(key);
        if (ledger == null) {
            return 0;
        }
        List<Double> left = new ArrayList<>();
        for (double expiry : ledger.expiries) {
            if (expiry > nowSeconds) {
                left.add(expiry);
            }
        }
        int dropped = ledger.expiries.size() - left.size();
        if (left.isEmpty()) {
            ledgers.remove(key);
        } else {
            ledgers.put(key, new Ledger(List.copyOf(left), ledger.fallbackExpiry()));
        }
        return dropped;
    }

    /** 按范围清空：一次动作结束（{@link StackClause.Scope#ACTION}）或体系被移除时调用。 */
    public void clearHeld(String holder) {
        ledgers.keySet().removeIf(key -> key.holder().equals(holder));
    }

    public void clearAll() {
        ledgers.clear();
    }

    /** 一份账本的可序列化事实：层数 + 最早到期还剩多少秒（无上限概念，仅记账）。 */
    public record Held(double layers, double remainingSeconds) {
    }

    /**
     * 可序列化快照（存档用）：只含"谁有多少层、最早还剩多久"，与插入顺序无关，
     * 因此同一串产出必然得到同一份快照——重算代替增删的前提。
     */
    public Map<String, Map<String, Held>> snapshot(double nowSeconds) {
        Map<String, Map<String, Held>> byHolder = new TreeMap<>();
        for (Map.Entry<Key, Ledger> entry : ledgers.entrySet()) {
            int alive = 0;
            double earliest = Double.POSITIVE_INFINITY;
            for (double expiry : entry.getValue().expiries) {
                if (expiry > nowSeconds) {
                    alive++;
                    earliest = Math.min(earliest, expiry - nowSeconds);
                }
            }
            if (alive > 0) {
                byHolder.computeIfAbsent(entry.getKey().holder(), key -> new TreeMap<>())
                        .put(entry.getKey().resource(),
                                new Held(alive, Double.isInfinite(earliest) ? 0.0 : earliest));
            }
        }
        return byHolder;
    }

    private static double remaining(int held, double cap) {
        if (cap == StackClause.UNBOUNDED || Double.isInfinite(cap)) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0.0, cap - held);
    }
}
