package com.klze.nextcard.core.load;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局策略（data/nextcard/manifest.json）。移除策略在这里而不在物品代码里：
 * 退次数（第三批裁定 8）、生存一次性（想法 9）都是数据，物品只是读取者（特例消灭表）。
 * 物品本身无内置获取途径——发放方式归整合包（第三批裁定 9）。
 */
public record Manifest(RemoveItem removeItem, Combat combat) {

    /** 每个字段的 codec 只声明一次：record 用它解码，{@link #validate} 用<em>同一个</em>对象再解一遍。 */
    private static final Codec<Integer> REFUND_DRAWS = Codec.intRange(0, 15);
    private static final Codec<Boolean> CONSUME_SURVIVAL = Codec.BOOL;
    private static final Codec<Double> BLOCK_REDUCTION = Codec.doubleRange(0.0, 1.0);

    /** 每一段允许的键名（空串 = 整份的根）。 */
    private static final Map<String, Set<String>> KEYS = Map.of(
            "", Set.of("remove_item", "combat"),
            "remove_item", Set.of("refund_draws", "consume_survival"),
            "combat", Set.of("block_reduction"));

    /** 键路径 → 该键的 codec。 */
    private static final Map<String, Codec<?>> VALUES = Map.of(
            "remove_item.refund_draws", REFUND_DRAWS,
            "remove_item.consume_survival", CONSUME_SURVIVAL,
            "combat.block_reduction", BLOCK_REDUCTION);

    /**
     * 读一份总表，把<em>缺席</em>与<em>写坏</em>分开判——这两件事的含义相反：
     *
     * <ul>
     *   <li><b>缺席 + 整包内容也是空的</b>（卡数 0）＝合法状态。铁律一要求"删光
     *       {@code data/nextcard/} 引擎照跑"，那种情况下每次 reload 都报一条错误就是拿内容反过来
     *       约束结构。</li>
     *   <li><b>缺席 + 有卡</b>＝错误。带卡的内容包却没带这份全局策略，只可能是文件名写错或漏打包，
     *       静默用默认值等于"手感数悄悄变了"。</li>
     *   <li><b>在场但非法</b>（越界、类型错、未知键）＝永远是错误。{@code optionalFieldOf} 会把
     *       非法值吞成"没写"（{@code OptionalFieldCodec#decode}：子 codec 失败返回
     *       {@code success(empty)}），所以值的合法性只能靠 {@link #validate} 在 codec 外面查。</li>
     * </ul>
     */
    public static LoadResult<Manifest> read(JsonElement raw, int cardFiles) {
        if (raw == null) {
            return cardFiles == 0
                    ? new LoadResult<>(DEFAULT, List.of())
                    : new LoadResult<>(DEFAULT, List.of(MANIFEST_FILE
                            + "：有 " + cardFiles + " 张卡却没有这份全局策略（文件名写错或漏打包）"));
        }
        List<String> errors = new ArrayList<>(validate(raw));
        Manifest decoded = CODEC.parse(JsonOps.INSTANCE, raw)
                .resultOrPartial(msg -> errors.add(MANIFEST_FILE + ": " + msg))
                .orElse(DEFAULT);
        return new LoadResult<>(decoded, errors);
    }

    /** 这份策略的文件名（缺席与非法两条报错都用它点名）。 */
    public static final String MANIFEST_FILE = "manifest.json";

    /**
     * codec 之外的第二道，加载时<em>先</em>跑它：返回错误清单，空 = 放行。
     *
     * <p>为什么不能只靠 codec：{@code optionalFieldOf(name, default)} 底下的
     * {@code OptionalFieldCodec#decode} 在子 codec 失败时返回 {@code success(empty)}，
     * 于是 {@code block_reduction: 1.5} 与"没写这一行"在 codec 看来是同一件事——
     * 内容侧改 JSON 试手感时，这就是"我明明改了怎么没反应"。</p>
     */
    public static List<String> validate(JsonElement root) {
        if (!(root instanceof JsonObject)) {
            return List.of("manifest.json: 整份必须是一个对象");
        }
        List<String> errors = new ArrayList<>();
        KEYS.forEach((section, allowed) -> errors.addAll(Strict.unknownKeysAt(root, section, allowed)));
        errors.addAll(Strict.valuesMustParse(root, VALUES));
        return errors;
    }

    public record RemoveItem(int refundDraws, boolean consumeSurvival) {
        public static final Codec<RemoveItem> CODEC = RecordCodecBuilder.create(i -> i.group(
                REFUND_DRAWS.optionalFieldOf("refund_draws", 1).forGetter(RemoveItem::refundDraws),
                CONSUME_SURVIVAL.optionalFieldOf("consume_survival", true).forGetter(RemoveItem::consumeSurvival)
        ).apply(i, RemoveItem::new));
    }

    /**
     * 战斗手感参数。<b>为什么放这里而不是写进代码或配置 TOML</b>：这几个数是要在
     * 游戏里改 JSON 反复试手感的（klze 2026-09-30："格挡先随便填个数，到时候到游戏里改 JSON 尝试手感"），
     * 数据包目录正好是 {@code /reload} 就能换、又跟着内容一起自洽换版的那一份。
     */
    public record Combat(double blockReduction) {
        public static final double DEFAULT_BLOCK_REDUCTION = 1.0;

        public static final Codec<Combat> CODEC = RecordCodecBuilder.create(i -> i.group(
                BLOCK_REDUCTION.optionalFieldOf("block_reduction", DEFAULT_BLOCK_REDUCTION)
                        .forGetter(Combat::blockReduction)
        ).apply(i, Combat::new));
    }

    public static final Manifest DEFAULT =
            new Manifest(new RemoveItem(1, true), new Combat(Combat.DEFAULT_BLOCK_REDUCTION));

    public static final Codec<Manifest> CODEC = RecordCodecBuilder.create(i -> i.group(
            RemoveItem.CODEC.optionalFieldOf("remove_item", DEFAULT.removeItem).forGetter(Manifest::removeItem),
            Combat.CODEC.optionalFieldOf("combat", DEFAULT.combat).forGetter(Manifest::combat)
    ).apply(i, Manifest::new));
}
