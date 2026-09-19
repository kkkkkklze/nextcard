package com.klze.nextcard.core.tag;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * 标签定义（v1.0 §4.1-2）。标签驱动池子与概率，是一等公民而不是裸字符串：
 * 没有这层注册，池子在 UI 里没有名字（第三批裁定 9）。
 *
 * <p>{@code name} 存语言键；颜色为 RRGGBB 十六进制；文件名 = id。</p>
 */
public record TagDefinition(ResourceLocation id, String name, String color, String icon, String description) {

    /** 不含 id（文件名 = id，加载器按路径补齐）。 */
    public record Body(String name, String color, String icon, String description) {
        public static final Codec<Body> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Body::name),
                Codec.STRING.optionalFieldOf("color", "").forGetter(Body::color),
                Codec.STRING.optionalFieldOf("icon", "").forGetter(Body::icon),
                Codec.STRING.optionalFieldOf("description", "").forGetter(Body::description)
        ).apply(i, Body::new));
    }

    public static TagDefinition of(ResourceLocation id, Body body) {
        return new TagDefinition(id, body.name(), body.color(), body.icon(), body.description());
    }
}
