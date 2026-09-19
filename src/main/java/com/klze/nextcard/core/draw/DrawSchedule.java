package com.klze.nextcard.core.draw;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 抽卡日程（v1.0 §5.1）：等级进度是数据——第 N 抽用哪个 profile、各等级权重多少。
 * T5 第 9 抽首现（第一批裁定 1）、T1 后期淡出（第三批裁定 6）、15 次上限（= 日程覆盖范围，
 * 第 16 抽查不到行，调用方据此拒绝）全部由这张表表达，代码零分支。
 */
public record DrawSchedule(List<Row> rows) {

    /** 抽次区间："1" 或 "2-4"。 */
    public record DrawRange(int fromInclusive, int toInclusive) {
        public static final Codec<DrawRange> CODEC = Codec.STRING.xmap(DrawRange::parse, DrawRange::write);

        public static DrawRange parse(String s) {
            String trimmed = s.trim();
            int dash = trimmed.indexOf('-');
            if (dash < 0) {
                int single = Integer.parseInt(trimmed);
                return new DrawRange(single, single);
            }
            return new DrawRange(Integer.parseInt(trimmed.substring(0, dash).trim()),
                    Integer.parseInt(trimmed.substring(dash + 1).trim()));
        }

        public String write() {
            return fromInclusive == toInclusive
                    ? String.valueOf(fromInclusive)
                    : fromInclusive + "-" + toInclusive;
        }

        public boolean contains(int drawNumber) {
            return drawNumber >= fromInclusive && drawNumber <= toInclusive;
        }
    }

    /**
     * 等级权重表。JSON 对象键是字符串，必须经字符串解码成 int——直接用
     * {@code Codec.INT} 做键会在 JsonOps 上失败并被 optionalFieldOf 吞成空表
     * （DFU optionalFieldOf 陷阱的现场，踩坑记录条目：constrained fields need a loader-side check）。
     */
    public static final Codec<Map<Integer, Integer>> TIER_WEIGHTS_CODEC = Codec.unboundedMap(
            Codec.STRING.flatXmap(
                    s -> {
                        try {
                            return DataResult.success(Integer.parseInt(s.trim()));
                        } catch (NumberFormatException e) {
                            return DataResult.error(() -> "bad tier key: " + s);
                        }
                    },
                    i -> DataResult.success(String.valueOf(i))),
            Codec.INT);

    /** 一行日程：抽次区间 → profile → 等级权重（权重缺失/为 0 的等级不出现；全零 → 有候选等级间均匀）。 */
    public record Row(DrawRange draws, ResourceLocation profile, Map<Integer, Integer> tierWeights) {
        public static final Codec<Row> CODEC = RecordCodecBuilder.create(i -> i.group(
                DrawRange.CODEC.fieldOf("draws").forGetter(Row::draws),
                ResourceLocation.CODEC.fieldOf("profile").forGetter(Row::profile),
                TIER_WEIGHTS_CODEC.optionalFieldOf("tier_weights", Map.of())
                        .forGetter(Row::tierWeights)
        ).apply(i, Row::new));

        public Row {
            tierWeights = Map.copyOf(tierWeights);
        }
    }

    public static final Codec<DrawSchedule> CODEC =
            Row.CODEC.listOf().xmap(DrawSchedule::new, DrawSchedule::rows);

    /** 第 N 抽的行（先匹配先赢）；空 = 该抽不存在（15 次上限的机制形态）。 */
    public Optional<Row> forDraw(int drawNumber) {
        return rows.stream().filter(row -> row.draws().contains(drawNumber)).findFirst();
    }
}
