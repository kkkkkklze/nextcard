package com.klze.nextcard.common.combat;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

/**
 * 引擎自己发出去的那一发：带来源卡与归因，并且<em>不再叫醒攻方的触发器</em>。
 *
 * <p>为什么必须是 {@code DamageSource} 的子类而不是"当前命中"上的一个全局标记：1.20.1 里
 * 一次命中的唯一合法载体就是 source 本身（事件链从头到尾传的是同一个引用），而
 * {@code level.damageSources().xxx()} 交回来的是<em>共享缓存实例</em>——往它身上挂状态会污染
 * 别人的伤害。自己 new 一个子类是唯一不污染任何人的做法。</p>
 *
 * <p>它同时是递归闸门：{@code damage} 动作会再打一发，那一发同样会进 {@code LivingDamageEvent}；
 * 不做区分就是 {@code damage → hit → damage → …} 直到栈溢出。</p>
 *
 * <p><b>顺带验实的现行行为</b>：独立伤害遵守原版无敌帧（{@code invulnerableTime}），所以同一刻
 * 打到同一目标的第二发会被吃掉——冲击波不叠加在刚被打中的那只身上。要改成叠加得给这个
 * damage type 挂 {@code BYPASSES_COOLDOWN} 标签，那是玩法决定，等内容侧点头再动
 * （GameTest {@code shockwaveHitsANeighbourOnceAndDoesNotRecurse} 把现状钉住了）。</p>
 */
public final class CardDamageSource extends DamageSource {

    /**
     * 代码里造的杀伤类型（不进注册表）：{@code scaling = never} 表示原版难度不再改一次这个数——
     * 它已经过完整乘区了，再来一次难度倍率就是第二次生效。
     */
    private static final Holder<DamageType> TYPE = new Holder.Direct<>(
            new DamageType("nextcard.card_extra", DamageScaling.NEVER, 0.0F));

    private final ResourceLocation cardId;
    private final String attribution;

    public CardDamageSource(ResourceLocation cardId, Entity owner, String attribution) {
        super(TYPE, owner, owner);
        this.cardId = cardId;
        this.attribution = attribution;
    }

    /** 这一发是不是我们自己补出去的（递归闸门与"不再叫醒攻方触发器"都看它）。 */
    public static boolean isEngineExtra(DamageSource source) {
        return source instanceof CardDamageSource;
    }

    public ResourceLocation cardId() {
        return cardId;
    }

    /** 归因（"以护甲值为基数 ×0.5"这种），日志与调试命令读它。 */
    public String attribution() {
        return attribution;
    }
}
