package com.klze.nextcard.core.effect;

/**
 * 效果子句标记接口（v1.0 §4.4 效果落点）。四原语（属性修饰 / 事件触发 / 周期 tick / 即时，
 * 第三批裁定 11 认可）与 C 类质变的 {@code mechanic_modifier} 子句在 M2 起注册进词表；
 * C3 裁定：机制挂点词表随首板卡表冻结，此前词表保持为空——空词表下任何非空 effects 数组
 * 都是加载错误（fail-fast），不假装认识任何类型。
 */
public interface EffectClause {
}
