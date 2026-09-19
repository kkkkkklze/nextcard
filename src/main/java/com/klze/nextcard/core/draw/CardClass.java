package com.klze.nextcard.core.draw;

import com.mojang.serialization.Codec;

/**
 * 卡的三类（v1.0 §5.2）：A = 方向卡（开启次级体系，T3 起入场）、B = 方式卡（战斗形式为题）、
 * C = 质变卡（改变机制，T5 起入场，C1 裁定）。
 *
 * <p>对抽卡引擎它只是候选过滤器的一个取值——引擎里没有任何按类分支的逻辑（铁律 2，门 G2）。
 * 类的机制语义住在效果层（体系声明 / 机制修饰槽位）。</p>
 */
public enum CardClass {
    A, B, C;

    public static final Codec<CardClass> CODEC =
            Codec.STRING.xmap(s -> CardClass.valueOf(s.toUpperCase()), CardClass::name);
}
