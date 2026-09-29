package com.klze.nextcard.common.combat;

import com.klze.nextcard.NextCard;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

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
 * <p><b>杀伤类型走数据包注册表，不走 {@code Holder.Direct}</b>（klze 裁定 2026-09-29：
 * "让本 mod 的所有伤害无视无敌帧"）。原因是一条源码事实：{@code Holder.Direct#is(TagKey)}
 * <em>永远返回 false</em>（{@code Holder} 里那几个 {@code is} 全是硬写死的 false），
 * 所以代码里造的杀伤类型<em>挂不上任何标签</em>，{@code BYPASSES_COOLDOWN} 也就无从生效。
 * 要标签就必须让 {@code DamageType} 成为注册表里的一条：
 * {@code data/nextcard/damage_type/card_extra.json} + 标签
 * {@code data/minecraft/tags/damage_type/bypasses_cooldown.json}（1.20.1 里 damage_type 的标签目录
 * 就是注册表键名，只有 block/item/fluid/game_event/entity_type 五个用复数目录，见
 * {@code TagManager#CUSTOM_REGISTRY_DIRECTORIES}）。</p>
 *
 * <p>绕开无敌帧之后可以叠加了：{@code LivingEntity#hurt} 里那句
 * {@code if (invulnerableTime > 10 && !source.is(BYPASSES_COOLDOWN))} 不再拦我们，
 * 于是"冲击波打刚被砍中的那只""背刺的额外一发"都会真的落下第二发。</p>
 */
public final class CardDamageSource extends DamageSource {

    /** 注册表里那一条（JSON 在 {@code data/nextcard/damage_type/card_extra.json}）。 */
    public static final ResourceKey<DamageType> TYPE_KEY = ResourceKey.create(Registries.DAMAGE_TYPE,
            new ResourceLocation(NextCard.MODID, "card_extra"));

    private final ResourceLocation cardId;
    private final String attribution;

    private CardDamageSource(Holder<DamageType> type, ResourceLocation cardId, Entity owner,
                             String attribution) {
        super(type, owner, owner);
        this.cardId = cardId;
        this.attribution = attribution;
    }

    /**
     * 造一发"卡补出来的伤害"。杀伤类型从<em>这个世界</em>的注册表里取（动态注册表按存档加载，
     * 拿不到 {@code Holder.Reference} 就谈不上标签）。
     *
     * @throws IllegalStateException 那条 damage_type 没加载进来——宁可当场点名，
     *         也不要静默地少一发伤害（那种表现和"这张卡没用"一模一样）
     */
    public static CardDamageSource of(Level level, ResourceLocation cardId, Entity owner,
                                      String attribution) {
        return of(level.registryAccess(), cardId, owner, attribution);
    }

    /** 同上，给只拿得到 {@link RegistryAccess} 的调用点用。 */
    public static CardDamageSource of(RegistryAccess registries, ResourceLocation cardId, Entity owner,
                                      String attribution) {
        Holder<DamageType> type = registries.registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(TYPE_KEY);
        return new CardDamageSource(type, cardId, owner, attribution);
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
