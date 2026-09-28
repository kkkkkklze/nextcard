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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.LinkedHashMap;
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
 * <p><b>接管点叫醒的事件（2026-09-28）</b>：{@code attack}（出手）／{@code hit}（打中）／
 * {@code damage_dealt}（真的造成了伤害）／{@code kill}（打死）／{@code damage_taken}（挨的这一下）／
 * {@code block_success} 与 {@code parry_success}（挡下与精准挡下）／{@code tick}（周期到点，
 * 在 {@code CardCadence} 里）。结算读四条通道：{@code all_damage}、{@code melee_damage}、
 * {@code direction_bonus}、守方 {@code damage_reduction}；六种动作有执行器（{@code lethal_immunity}、
 * {@code stacks}、{@code damage}、{@code knockback}、{@code reflect}、{@code force_parry}）。
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

    /**
     * 已经报出来的缺口（没有执行器的动作、没有对象的动作）。
     * GameTest 用它证明"报出来了"这件事本身，而不是只信日志。
     */
    public static List<String> reportedGaps() {
        return List.copyOf(REPORTED_UNSUPPORTED);
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

            // "造成伤害时"要的是<em>结算后</em>那个真的落到身上的数：被对面免疫成 0 的那发不算，
            // 所以它比 hit 少一条通道（同一个"打中了"，一个看动作成没成，一个看数落没落）。
            double dealt = result == null ? incoming : result.value();
            if (dealt > 0.0) {
                Triggers.Result onDealt = fire(attacker, attackView, Triggers.DAMAGE_DEALT,
                        basesOf(attacker, dealt));
                perform(attacker, victim, source, onDealt);
            }
        }
    }

    /**
     * 出手。挂在 {@link AttackEntityEvent} 上，而不是挂在伤害事件上：原版在
     * {@code Player#attack} 的<em>第一行</em>就发它（源码 {@code ForgeHooks#onPlayerAttackTarget}），
     * 后面那一刀哪怕被无敌帧吃掉、哪怕伤害算出来是 0，"我出手打了它"这件事仍然成立。
     *
     * <p>与 {@code hit} 的分工就在这个"废刀也算"上：连击/叠毒那类卡要的是挥出去的每一下，
     * 而打空、打不疼都要照记。</p>
     *
     * <p>基数里 {@code incoming} 是 0——出手那一刻这发<em>还没有数</em>。卡面若在 {@code attack}
     * 上写 {@code damage} 且 {@code basis: incoming}，会照实报"基数 × 系数 = 0"，不会静默，
     * 也不假装有个数。</p>
     */
    @SubscribeEvent
    public static void onPlayerAttack(AttackEntityEvent swing) {
        State striker = stateOf(swing.getEntity());
        if (striker == null || striker.triggers().isEmpty()) {
            return;
        }
        Entity target = swing.getTarget();
        Triggers.Result result = fire(striker, DamageContact.attackView(striker.player(), target),
                Triggers.ATTACK, basesOf(striker, 0.0));
        // 出手这一侧没有"打我的那位"，所以反弹类的动作在这里天然没有对象
        perform(striker, target instanceof LivingEntity living ? living : null, null, result);
    }

    /**
     * 击杀。1.20.1 <b>只有</b> {@link LivingDeathEvent}（"死亡正在被处理"），没有别的模组生态里
     * 那个 {@code LivingDiedEvent}（源码核过：这个版本的 Forge 根本没有这个类）。它是可取消的，
     * 所以万一别的模组把这次死亡取消掉，我们的 {@code kill} 已经响过一次了——这条偏差记在
     * {@code docs/引擎侧进度-2026-09-25.md}，不做"看起来更准"的修补。
     *
     * <p>归因用 {@code DamageSource#getEntity()}（箭与三叉戟会归到<em>射手</em>身上），与
     * {@code hit}／{@code reflect} 同一位。自伤致死不算击杀：那没有"击杀对象"。</p>
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent death) {
        LivingEntity victim = death.getEntity();
        Entity killer = death.getSource().getEntity();
        if (killer == null || killer == victim) {
            return;
        }
        State striker = stateOf(killer);
        if (striker == null || striker.triggers().isEmpty()) {
            return;
        }
        Triggers.Result result = fire(striker, DamageContact.attackView(killer, victim),
                Triggers.KILL, basesOf(striker, 0.0));
        perform(striker, victim, killer, result);
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
    public static void perform(@Nullable State actor, @Nullable LivingEntity directTarget,
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
            if (directTarget == null && noRadius(hit.radius(), hit.cardId(), "damage")) {
                continue;
            }
            for (LivingEntity target : around(owner, directTarget, hit.radius())) {
                target.hurt(new CardDamageSource(hit.cardId(), owner, hit.attribution()), (float) hit.amount());
            }
        }
        for (Triggers.KnockbackHit knock : result.knockbacks()) {
            if (directTarget == null && noRadius(knock.radius(), knock.cardId(), "knockback")) {
                continue;
            }
            for (LivingEntity target : around(owner, directTarget, knock.radius())) {
                target.knockback(knock.strength(), -Math.sin(Math.toRadians(owner.getYRot())),
                        Math.cos(Math.toRadians(owner.getYRot())));
            }
        }
    }

    /**
     * "半径 0 = 只碰直接目标"，而这个事件上<em>没有直接目标</em>（{@code tick} 与 {@code attack}
     * 之外的路径都算）——那这一发没有对象。原先这里会把 {@code null} 塞进 {@code List.of}，
     * 于是服务器在周期触发上直接抛异常；现在<em>报出来再跳过</em>，因为静默跳过会让人以为
     * "这卡没用"，而真相是这张卡要写成带半径的。
     *
     * @return true 表示这件事已经报过并该跳过
     */
    private static boolean noRadius(double radius, ResourceLocation cardId, String action) {
        if (radius > 0.0) {
            return false;
        }
        report(cardId + " 的 " + action + " 要打在直接目标上，而这个事件没有对面（写半径才能搜目标）");
        return true;
    }

    /** 未落地的事报一次，不许静默（同 {@link #REPORTED_UNSUPPORTED} 的那条纪律）。 */
    private static void report(String message) {
        if (REPORTED_UNSUPPORTED.add(message)) {
            NextCard.LOGGER.warn("[nextcard] {}", message);
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
        return fire(state, facts, event, bases, state == null ? List.of() : state.triggers());
    }

    /**
     * 同一件事，但<em>只过筛过的子集</em>：周期驱动要的就是"这一 tick 只叫醒到点的那几张"，
     * 没到点的那张不能因为共用一个事件名就被顺带跑一次。
     */
    public static Triggers.Result fire(@Nullable State state, @Nullable Facts facts, String event,
                                       Triggers.Bases bases, List<Triggers.Bound> bound) {
        if (state == null || bound.isEmpty() || facts == null) {
            return Triggers.Result.NOTHING;
        }
        Triggers.Result result = Triggers.fire(event, state.holder(), withLayers(state, facts), bound,
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
     * 叫醒触发器之前，把这个持有者<em>当前</em>的叠层账贴进事实快照。
     *
     * <p>没有这一步，卡面写的 {@code {"stacks": {"id": "venom", "at_least": 3}}}（"叠满三层才怎样"）
     * 在世界里恒不成立——判定读的是快照，而快照没人喂层数。引擎自有的资源（冷却、已开的
     * 强制精准窗口）在 {@code trigger.} 命名空间下，不算叠层，不贴。</p>
     */
    private static Facts withLayers(State state, Facts facts) {
        Map<String, Integer> current = new LinkedHashMap<>();
        HOST.counters().snapshot(state.nowSeconds()).getOrDefault(state.holder(), Map.of())
                .forEach((resource, held) -> {
                    if (!resource.startsWith(Triggers.COOLDOWN_PREFIX)) {
                        current.put(resource, (int) held.layers());
                    }
                });
        return facts.withLayers(current);
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
