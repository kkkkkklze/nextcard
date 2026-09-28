package com.klze.nextcard.common.combat;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.common.load.CardContentReload;
import com.klze.nextcard.common.player.CardAttributes;
import com.klze.nextcard.common.player.PlayerCardState;
import com.klze.nextcard.core.effect.AttackPipeline;
import com.klze.nextcard.core.effect.DefencePipeline;
import com.klze.nextcard.core.effect.EffectHost;
import com.klze.nextcard.core.effect.Facts;
import com.klze.nextcard.core.effect.MechanicClause;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.ParryTiming;
import com.klze.nextcard.core.effect.Predicates;
import com.klze.nextcard.core.effect.Settlement;
import com.klze.nextcard.core.effect.Triggers;
import com.klze.nextcard.core.player.CardLedger;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * 战斗接管：把折好的机制快照喂进 {@link Settlement}（攻方管线 → 交付 → 守方管线），
 * 并在最后一道关口覆盖伤害。
 *
 * <p>接管点选 {@code LivingDamageEvent} 而不是 {@code LivingHurtEvent}，是因为前者发生在原版的
 * 护甲减免与抗性等级<em>之后</em>、真正扣血<em>之前</em>——放这里的覆盖才是最终值。也正因如此，
 * 事件给的金额只当攻方的<b>基础值</b>，守方管线的减免段不再重复算护甲/抗性（同一次减免只能生效一次）。
 * 形状取自姊妹工程求仙问道已实测过的同一处（判据用计数器，不用血量差，因为原版会把小数取整，
 * "0.94 掉血"被读成"没打中"是踩过的坑）。</p>
 *
 * <p><b>只接了被词表证实存在的四条通道 + 一种动作</b>：{@code all_damage}（全伤）、
 * {@code melee_damage}（近战＝武器分类之一）、{@code direction_bonus}（方向增伤）、守方的
 * {@code damage_reduction}（减伤），以及 {@code lethal_immunity}（致命免疫——它走的是
 * {@link Triggers} → {@link Predicate} 的理由 → 守方管线第①步否决这条完整链路）。
 * 暴击、破甲、护盾点数、穿透声明都<em>故意留空</em>——它们的口径（概率从哪来、盾是谁的账、
 * 哪张卡能声明无视）在内容侧那份《02-名词表》与我们的通道表还没对齐（见交付文档 Q5），
 * 我自己发明一份就是第二真相。</p>
 */
@Mod.EventBusSubscriber(modid = NextCard.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CardCombat {

    /** 全服务器一个宿主：holder 键用玩家 UUID，卡账与内容换版都由它重算。 */
    public static final EffectHost HOST = new EffectHost(() -> CardContentReload.current().cards());

    private static int pipelineRuns;
    private static List<String> lastTrace = List.of();
    private static final Set<String> REPORTED_UNSUPPORTED = new LinkedHashSet<>();

    private CardCombat() {
    }

    /** 结算跑过几次——GameTest 用它证明"接管点真的在链路上"，而不是靠看血量猜。 */
    public static int pipelineRuns() {
        return pipelineRuns;
    }

    public static List<String> lastTrace() {
        return lastTrace;
    }

    /** 测试之间用来重置计数，避免彼此串扰（同 {@code gametest-helper-coords} 那条教训）。 */
    public static void resetForTests() {
        pipelineRuns = 0;
        lastTrace = List.of();
        REPORTED_UNSUPPORTED.clear();
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        DamageSource damageSource = event.getSource();
        Entity source = damageSource.getEntity();
        LivingEntity victim = event.getEntity();
        State attacker = stateOf(source);
        State defender = stateOf(victim);
        double incoming = event.getAmount();
        Facts defenceView = DamageContact.defendView(victim, source, incoming);
        Facts attackView = DamageContact.attackView(source, victim);

        Triggers.Result onTaken = fire(defender, defenceView, Triggers.DAMAGE_TAKEN,
                basesOf(defender, incoming));
        Settlement.Result result = settle(attacker == null ? null : attacker.profile(), attackView,
                defender == null ? null : defender.profile(), onTaken.vetoReason(), incoming);
        if (result != null) {
            event.setAmount((float) result.value());
            pipelineRuns++;
            lastTrace = result.trace();
        }
        perform(defender, victim, source, onTaken);

        // 攻方的"命中时"排在结算之后：这一发的数已经定了，攒下的层与补出去的伤害给下一发用。
        // 我们自己补的那一发不再叫醒攻方触发器——否则 damage → hit → damage 一路递归到栈溢出。
        if (!CardDamageSource.isEngineExtra(damageSource)) {
            Triggers.Result onHit = fire(attacker, attackView, Triggers.HIT, basesOf(attacker, incoming));
            perform(attacker, victim, source, onHit);
        }
    }

    /**
     * 格挡 / 精准格挡事件。挂在 {@link ShieldBlockEvent} 上，不是挂在伤害事件上：原版在
     * {@code hurt} 内部就把挡下的那部分扣掉了，完全挡住的伤害<em>根本走不到</em>
     * {@code LivingDamageEvent}——挂错地方就是"盾反永不响"这种最难查的静默。
     *
     * <p>用这个事件还省掉一批我们自己重述原版的规则："算不算挡住"（正面半球、不穿盾、
     * 非穿刺箭）由原版判，我们只往上加一条时机判定：举盾多久了。</p>
     *
     * <p>第三道闸门是能力本身：没被授予 {@code parry} 机制就两个事件都不发——挡下近战是原版
     * 一直在做的事，不是一张卡的触发器。</p>
     */
    @SubscribeEvent
    public static void onShieldBlock(ShieldBlockEvent blocked) {
        if (!(blocked.getEntity() instanceof Player owner)) {
            return;
        }
        State defender = stateOf(owner);
        if (defender == null || defender.triggers().isEmpty()) {
            return;
        }
        DamageSource damageSource = blocked.getDamageSource();
        if (CardDamageSource.isEngineExtra(damageSource)) {
            return;
        }
        MechanicClause parry = HOST.mechanics(defender.holder()).get("parry");
        if (parry == null) {
            return;
        }
        double incoming = blocked.getOriginalBlockedDamage();
        MechanicProfile profile = defender.profile() == null ? new MechanicProfile(Map.of())
                : defender.profile();
        double window = ParryTiming.windowSeconds(parry.param("base_window", 0.0), profile);
        boolean forced = Triggers.forcedPrecise(defender.holder(), defender.triggers(), HOST.counters(),
                defender.nowSeconds());
        String which = forced || ParryTiming.precise(ticksBlocking(owner), window)
                ? Triggers.PARRY_SUCCESS : Triggers.BLOCK_SUCCESS;
        Triggers.Result result = fire(defender,
                DamageContact.defendView(owner, damageSource.getEntity(), incoming), which,
                basesOf(defender, incoming));
        perform(defender, owner, damageSource.getEntity(), result);
    }

    /** 这次举盾已经举了多少 tick（原版的算法：总时长 − 剩余时长）。 */
    public static int ticksBlocking(Player player) {
        ItemStack stack = player.getUseItem();
        return stack.isEmpty() ? 0 : stack.getUseDuration() - player.getUseItemRemainingTicks();
    }

    /**
     * 把计划里"要落到世界上"的那部分落掉：独立伤害与击退。
     *
     * <p>半径 0 只碰直接目标；写了半径就以<em>持卡人</em>为圆心找活物（不打自己、不打队友）。
     * 独立伤害走 {@link CardDamageSource}，所以它会正常再过一遍目标的护甲与减免——
     * 它是新的一发，不是把刚才那个数再乘一遍。</p>
     */
    private static void perform(@Nullable State actor, @Nullable LivingEntity directTarget,
                                @Nullable Entity attacker, Triggers.Result result) {
        if (actor == null || result.extraHits().isEmpty() && result.knockbacks().isEmpty()) {
            return;
        }
        Player owner = actor.player();
        for (Triggers.ExtraHit hit : result.extraHits()) {
            if (hit.target() == Triggers.Target.ATTACKER) {
                // 反弹只还给打我的那位：找不到活体攻击者（箭、火、摔落）就是没对象，不搜半径
                if (attacker instanceof LivingEntity striker && striker != owner) {
                    striker.hurt(new CardDamageSource(hit.cardId(), owner, hit.attribution()),
                            (float) hit.amount());
                }
                continue;
            }
            for (LivingEntity target : around(owner, directTarget, hit.radius())) {
                target.hurt(new CardDamageSource(hit.cardId(), owner, hit.attribution()), (float) hit.amount());
            }
        }
        for (Triggers.KnockbackHit knock : result.knockbacks()) {
            for (LivingEntity target : around(owner, directTarget, knock.radius())) {
                target.knockback(knock.strength(), -Math.sin(Math.toRadians(owner.getYRot())),
                        Math.cos(Math.toRadians(owner.getYRot())));
            }
        }
    }

    private static List<LivingEntity> around(Player owner, LivingEntity directTarget, double radius) {
        if (radius <= 0.0) {
            return List.of(directTarget);
        }
        return owner.level().getEntitiesOfClass(LivingEntity.class,
                owner.getBoundingBox().inflate(radius),
                other -> other != owner && other.isAlive() && !owner.isAlliedTo(other))
                .stream().filter(other -> owner.distanceTo(other) <= radius).toList();
    }

    /** 动作可用的基数（护甲值 / 攻击力 / 这一发的量），只有这里读世界。 */
    private static Triggers.Bases basesOf(@Nullable State state, double incoming) {
        if (state == null) {
            return Triggers.Bases.NONE;
        }
        Player owner = state.player();
        // "护甲值"取 ARMOR 属性而不是装备栏的护甲点数：卡面 channel.armor 改的就是前者，
        // 取后者的话"+20 护甲值"那类附带数值根本进不了基数
        return new Triggers.Bases(owner.getAttributeValue(Attributes.ARMOR),
                owner.getAttributeValue(Attributes.ATTACK_DAMAGE), incoming);
    }

    /**
     * 这个持有者的触发器在某个事件上兑现一次。没有玩家状态（不是玩家、或一张卡都没有）时什么都不做。
     *
     * <p>两件事在这里兜住：① 兑现动过层数就把宿主标脏——每层映射折进快照的值是层数的函数，
     * 不重算会停在旧层数上（玩家看到的"层数在涨、减伤没动"就是这么来的）；
     * ② 引擎还没有执行器的动作要<em>报出来</em>，每个只报一次，不许静默跳过。</p>
     */
    public static Triggers.Result fire(@Nullable State state, @Nullable Facts facts, String event,
                                       Triggers.Bases bases) {
        if (state == null || state.triggers().isEmpty() || facts == null) {
            return Triggers.Result.NOTHING;
        }
        Triggers.Result result = Triggers.fire(event, state.holder(), facts, state.triggers(),
                HOST.counters(), HOST.declaredStacks(), state.profile(), bases, state.nowSeconds());
        for (String unsupported : result.unsupported()) {
            if (REPORTED_UNSUPPORTED.add(unsupported)) {
                NextCard.LOGGER.warn("[nextcard] {}", unsupported);
            }
        }
        if (!result.fired().isEmpty()) {
            HOST.markDirty(state.holder());
        }
        return result;
    }

    /**
     * 上线 / 重生时把附带数值挂回去：我们的属性修饰是 <em>transient</em> 的（不进存档），
     * 所以每次换实体都要重投影一次，否则"进了游戏护甲是 0、砍一刀才跳上来"。
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        stateOf(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        stateOf(event.getEntity());
    }

    /**
     * 一个玩家的生效状态。卡账同步只做一次，机制快照与触发子句都从这次同步里来——
     * 分两次同步就会出现"快照是新的、触发子句还是旧卡表那批"。
     *
     * @param nowSeconds 世界时刻（秒），与 {@code CounterStore} 同一口径
     */
    public record State(String holder, @Nullable MechanicProfile profile, List<Triggers.Bound> triggers,
                        Player player, double nowSeconds) {
    }

    /** 谁的卡账折出来的生效状态；没有玩家身份、没卡时给 null。 */
    @Nullable
    public static State stateOf(@Nullable Entity entity) {
        if (!(entity instanceof Player player)) {
            return null;
        }
        CardLedger ledger = PlayerCardState.of(player);
        if (ledger == null || ledger.size() == 0) {
            return null;
        }
        String holder = player.getUUID().toString();
        HOST.syncOwned(holder, ledger.owned());
        HOST.flush(List.of());
        MechanicProfile profile = HOST.profile(holder);
        MechanicProfile folded = profile.slotIds().isEmpty() ? null : profile;
        // 附带数值（护甲 / 生命上限 / 移速 / 攻速）要真的挂上身：折好却不挂，卡面那一列就是空话。
        // 传 null 也照样调用——那正是"把我们的修饰摘干净"的那一路。
        CardAttributes.apply(player, folded);
        return new State(holder, folded, HOST.triggers(holder), player,
                player.level().getGameTime() / 20.0);
    }

    /**
     * 跑完整链；两边都没有可生效的账时返回 null（不参与、不改数）。
     * 纯函数，可无头测试——绑定 {@code ServerPlayer} 会让它没法被证明。
     *
     * @param contact 攻方视角的接触事实（决定方向增伤这格吃不吃得到）；null = 不看接触
     */
    @Nullable
    public static Settlement.Result settle(@Nullable MechanicProfile attack, @Nullable Facts contact,
                                           @Nullable MechanicProfile defence, @Nullable String vetoReason,
                                           double incoming) {
        AttackPipeline.Input input = attackInputFor(attack, incoming, contact);
        DefencePipeline.Options options = defenceOptionsFor(defence, vetoReason);
        if (input == null && options == null) {
            return null;
        }
        return Settlement.resolve(input == null ? Settlement.plain(incoming) : input, options);
    }

    /** 不看接触事实的那条入口（纯数值推演与既有断言）。 */
    @Nullable
    public static Settlement.Result settle(@Nullable MechanicProfile attack, @Nullable MechanicProfile defence,
                                           @Nullable String vetoReason, double incoming) {
        return settle(attack, null, defence, vetoReason, incoming);
    }

    /**
     * 攻方的乘区输入；没有攻方快照时给 null（表示这一发不由卡来加成）。
     *
     * <p>方向增伤是<em>条件乘区</em>：卡面写的那个值只有在"从目标背后 120° 内"这一发才进乘区
     * （《00》背刺的定义），接触不成立时留痕会写"未生效"，好让人看出是"没吃到"而不是"没写"。</p>
     */
    @Nullable
    public static AttackPipeline.Input attackInputFor(@Nullable MechanicProfile attack, double incoming,
                                                      @Nullable Facts contact) {
        if (attack == null) {
            return null;
        }
        double allDamage = attack.channel("all_damage");
        double melee = attack.channel("melee_damage");
        double direction = attack.channel("direction_bonus");
        boolean directionApplies = contact == null
                || contact.fromBehind(Predicates.BACK_SECTOR_HALF_ANGLE);
        return new AttackPipeline.Input(incoming, 1.0, melee > 0 ? "melee" : null,
                Map.of("melee", melee), allDamage, 0.0, 0.0, 1.0, direction, false, 0.0, 0.0,
                AttackPipeline.penetration(), directionApplies);
    }

    /**
     * 守方的来源清单。减伤通道 &lt;= 0 是"没这项账"而不是负数加成；否决理由与它各自独立，
     * 所以"有免疫没减伤"和"有减伤没免疫"都能只带自己那半。两者都没有时给 null（不参与）。
     */
    @Nullable
    public static DefencePipeline.Options defenceOptionsFor(@Nullable MechanicProfile defence,
                                                            @Nullable String vetoReason) {
        boolean veto = vetoReason != null && !vetoReason.isEmpty();
        double reduction = defence == null ? 0.0 : defence.channel("damage_reduction");
        if (!veto && reduction <= 0) {
            return null;
        }
        List<DefencePipeline.Source> ratios = reduction > 0
                ? List.of(new DefencePipeline.Source("减伤通道", reduction))
                : List.of();
        return new DefencePipeline.Options(veto ? vetoReason : null, List.of(), List.of(), ratios);
    }
}
