package com.klze.nextcard.core.load;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 全局策略（data/nextcard/manifest.json）。移除策略在这里而不在物品代码里：
 * 退次数（第三批裁定 8）、生存一次性（想法 9）都是数据，物品只是读取者（特例消灭表）。
 * 物品本身无内置获取途径——发放方式归整合包（第三批裁定 9）。
 */
public record Manifest(RemoveItem removeItem) {

    public record RemoveItem(int refundDraws, boolean consumeSurvival) {
        public static final Codec<RemoveItem> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(0, 15).optionalFieldOf("refund_draws", 1).forGetter(RemoveItem::refundDraws),
                Codec.BOOL.optionalFieldOf("consume_survival", true).forGetter(RemoveItem::consumeSurvival)
        ).apply(i, RemoveItem::new));
    }

    public static final Manifest DEFAULT =
            new Manifest(new RemoveItem(1, true));

    public static final Codec<Manifest> CODEC = RecordCodecBuilder.create(i -> i.group(
            RemoveItem.CODEC.optionalFieldOf("remove_item", DEFAULT.removeItem).forGetter(Manifest::removeItem)
    ).apply(i, Manifest::new));
}
