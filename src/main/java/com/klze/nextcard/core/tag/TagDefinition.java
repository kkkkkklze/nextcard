package com.klze.nextcard.core.tag;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * 标签定义（v1.1 §4.1-2）。标签驱动池子与概率，是一等公民而不是裸字符串。
 *
 * <p>{@code judgmentOnly}（判断标签，第五批裁定）：{@code true} 时该标签<b>不参与概率</b>——
 * 不进标签权重 W、不投影出 (等级，标签) 池——<b>只参与判断</b>：作为 {@code requires} 谓词，
 * 拥有 ≥1 张带该标签的卡即满足（通用 C 卡的入场条件，§5.5）。默认 {@code false}（普通标签）。</p>
 *
 * <p>{@code name} 存语言键；颜色为 RRGGBB 十六进制；文件名 = id。</p>
 */
public record TagDefinition(ResourceLocation id, String name, String color, String icon,
                            String description, boolean judgmentOnly) {

    /** 不含 id（文件名 = id，加载器按路径补齐）。 */
    public record Body(String name, String color, String icon, String description, boolean judgmentOnly) {
        public static final Codec<Body> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Body::name),
                Codec.STRING.optionalFieldOf("color", "").forGetter(Body::color),
                Codec.STRING.optionalFieldOf("icon", "").forGetter(Body::icon),
                Codec.STRING.optionalFieldOf("description", "").forGetter(Body::description),
                Codec.BOOL.optionalFieldOf("judgment_only", false).forGetter(Body::judgmentOnly)
        ).apply(i, Body::new));
    }

    public static TagDefinition of(ResourceLocation id, Body body) {
        return new TagDefinition(id, body.name(), body.color(), body.icon(),
                body.description(), body.judgmentOnly());
    }
}
