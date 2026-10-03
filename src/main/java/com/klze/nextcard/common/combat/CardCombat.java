package com.klze.nextcard.common.combat;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.common.load.CardContentReload;
import com.klze.nextcard.common.player.CardAttributes;
import com.klze.nextcard.common.player.PlayerCardState;
import com.klze.nextcard.core.effect.ArmourPiercing;
import com.klze.nextcard.core.effect.AttackPipeline;
import com.klze.nextcard.core.effect.CritRules;
import com.klze.nextcard.core.effect.DefencePipeline;
import com.klze.nextcard.core.effect.EffectHost;
import com.klze.nextcard.core.effect.Facts;
import com.klze.nextcard.core.effect.Lifesteal;
import com.klze.nextcard.core.effect.MechanicClause;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.ParryTiming;
import com.klze.nextcard.core.effect.Predicates;
import com.klze.nextcard.core.effect.ShieldBlock;
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
import net.minecraftforge.event.entity.player.CriticalHitEvent;
import net.minecraftforge.eventbus.api.Event;
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
 * <p><b>接管点叫醒的事件（2026-09-28）</b>：{@code attack}（出手）／{@code hit}（打中）／
 * {@code damage_dealt}（真的造成了伤害）／{@code kill}（打死）／{@code damage_taken}（挨的这一下）／
 * {@code block_success} 与 {@code parry_success}（挡下与精准挡下）／{@code tick}（周期到点，
 * 在 {@code CardCadence} 里）。结算读五条通道：{@code all_damage}、{@code melee_damage}、
 * {@code direction_bonus}、{@code backstab_bonus}、守方 {@code damage_reduction}，
 * 加上暴击（{@link CritRules}：面板 5% 起、倍率 130% 起、溢出再暴击，原版跳跃暴击由
 * {@link #onCriticalHit} 关掉）；六种动作有执行器（{@code lethal_immunity}、
 * {@code stacks}、{@code damage}、{@code knockback}、{@code reflect}、{@code force_parry}）。
 * 破甲、护盾点数、穿透声明、元素与抗性都<em>故意留空</em>——它们的口径（盾是谁的账、
 * 哪张卡能声明无视、元素那一族要不要开）在内容侧那份《02-名词表》与我们的通道表还没对齐
 * （见交付文档 Q5 与《口径对齐·暴击与方向增伤》），我自己发明一份就是第二真相。</p>
 */
@Mod.EventBusSubscriber(modid = NextCard.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CardCombat {

    /** 全服务器一个宿主：holder 键用玩家 UUID，卡账与内容换版都由它重算。 */
    public static final EffectHost HOST = new EffectHost(() -> CardContentReload.current().cards());

    private static int pipelineRuns;
    private static List<String> lastTrace = List.of();
    private static final Set<String> REPORTED_UNSUPPORTED = new LinkedHashSet<>();

    /**
     * 暴击随机数的来源。<b>null = 用世界的 {@code level.random}</b>（生产走这条）；
     * 非 null 表示被测试钉住了。
     *
     * <p>为什么要有这个口子：面板有 5% 基线暴击之后，任何"这一刀正好掉 15 点"的世界内断言
     * 都会变成 5% 概率飘红（实测就飘了——一条写了十三遍全绿的门，第十四遍读到 19.5）。
     * 飘的不是 bug，是断言不成立。{@link #resetForTests()} 把测试态钉成"不判定暴击"，
     * 要验暴击的那条再显式 {@link #pinCritRoll(double)}。生产永远不会调这两个方法。</p>
     */
    @Nullable
    private static java.util.function.DoubleSupplier critRollOverride;

    /**
     * 格挡减伤的比例来源。<b>null = 读 manifest 的 {@code combat.block_reduction}</b>（生产走这条）；
     * 非 null 表示被测试钉住了——否则这个数只在 JSON 里，世界内断言就无法证明"改了它，结果真的变了"。
     */
    @Nullable
    private static Double blockReductionOverride;

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
        critRollOverride = () -> CritRules.NO_ROLL;   // 测试态默认"不判定暴击"，精确掉血断言才成立
        blockReductionOverride = null;                // 测试态回到 manifest 的数，不留下上一次的钉值
    }

    /** 只给测试用：把这一发的暴击判定钉成指定的 roll（0.0 = 一定中到概率那一次）。 */
    public static void pinCritRoll(double roll) {
        critRollOverride = () -> roll;
    }

    /** 只给测试用：把"挡掉多少"钉成指定比例，用来证明这个数真的在链路上（生产不碰）。 */
    public static void pinBlockReduction(double reduction) {
        blockReductionOverride = reduction;
    }

    /** 挡掉的比例：被钉住时用钉住的值，否则读 manifest（缺省 1.0 = 与原版一致）。 */
    private static double blockReduction() {
        return blockReductionOverride != null ? blockReductionOverride
                : CardContentReload.current().manifest().combat().blockReduction();
    }

    /** 生产用世界的随机源；被钉住时用钉住的那个值。 */
    private static double critRoll(LivingEntity victim) {
        return critRollOverride != null ? critRollOverride.getAsDouble()
                : victim.level().random.nextDouble();
    }

    /**
     * 护甲穿透：在原版算护甲<em>之前</em>把该无视的那部分借走，算完在
     * {@link #onLivingDamage} 里立刻还。曲线仍然只由原版算一次——见
     * {@link ArmourPiercing} 那段"为什么不自己算护甲"。
     *
     * <p>选 {@code LivingHurtEvent} 而不是更早的 {@code LivingAttackEvent}，是因为前者已经在
     * 举盾抵消与无敌帧吞刀<em>之后</em>：那些情况下护甲压根没参与，借了就是白借（还得靠扫表兜底）。
     * 唯一剩下的早退路径是"别人把这一发改成 0"，那条由每 tick 扫表收尾，最坏漏一 tick。</p>
     */
    @SubscribeEvent
    public static void onLivingHurt(net.minecraftforge.event.entity.living.LivingHurtEvent hurt) {
        DamageSource damageSource = hurt.getSource();
        if (!(damageSource.getEntity() instanceof LivingEntity striker)) {
            return;   // 摔落、火焰这类没有"攻击者"的伤害，谈不上穿透
        }
        State attacker = stateOf(striker);
        if (attacker == null || attacker.profile() == null) {
            return;
        }
        LivingEntity victim = hurt.getEntity();
        double strip = ArmourPiercing.armorToStrip(victim.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR),
                attacker.profile().channel("armor_pierce"));
        if (strip > 0.0) {
            TargetStates.applyArmourPierce(victim, striker.getUUID().toString(), strip,
                    victim.level().getGameTime());
        }
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        DamageSource damageSource = event.getSource();
        Entity source = damageSource.getEntity();
        LivingEntity victim = event.getEntity();
        if (source instanceof LivingEntity striker) {
            // 护甲已经算完了，把借走的那部分还回去（不等扫表）
            TargetStates.releaseArmourPierce(victim, striker.getUUID().toString());
        }
        State attacker = stateOf(source);
        State defender = stateOf(victim);
        double incoming = event.getAmount();
        Facts defenceView = DamageContact.defendView(victim, source, incoming);
        Facts attackView = DamageContact.attackView(source, victim);

        Triggers.Result onTaken = fire(defender, defenceView, Triggers.DAMAGE_TAKEN,
                basesOf(defender, incoming));
        // "下一次攻击"的武装在<em>这一发进结算</em>的时候用掉。两个边界要说明：被盾完全挡下的那发
        // 根本走不到这里，所以武装保留（"挡下了就不算我打过"，与 {@code hit} 同一口径）；
        // 自家补出去的那一发<em>不算</em>"我的下一次攻击"，否则一次弹反就把玩家攒着的必暴吃掉了。
        CritRules.Armed armed = attacker == null || CardDamageSource.isEngineExtra(damageSource)
                ? CritRules.Armed.NONE
                : Triggers.spendArmedCrit(attacker.holder(), attacker.triggers(), HOST.counters(),
                        attacker.nowSeconds());
        // 暴击那一次随机数从世界的随机源取（原版跳跃暴击已经被 onCriticalHit 关掉，
        // 所以这里是唯一的暴击来源，不会与原版叠成双暴击）
        Settlement.Result result = settle(attacker == null ? null : attacker.profile(), attackView,
                defender == null ? null : defender.profile(), onTaken.vetoReason(), incoming,
                critRoll(victim), armed);
        if (result != null) {
            event.setAmount((float) result.value());
            pipelineRuns++;
            lastTrace = result.trace();
        }
        perform(defender, victim, source, onTaken);

        // 结算后真的落到身上多少，攻击者就按 `channel.lifesteal` 的比例回复多少（比例为 0 时什么都没发生）。
        double dealt = result == null ? incoming : result.value();
        healLifesteal(attacker, source, dealt);

        // 攻方的"命中时"排在结算之后：这一发的数已经定了，攒下的层与补出去的伤害给下一发用。
        // 我们自己补的那一发不再叫醒攻方触发器——否则 damage → hit → damage 一路递归到栈溢出。
        if (!CardDamageSource.isEngineExtra(damageSource)) {
            Triggers.Result onHit = fire(attacker, attackView, Triggers.HIT, basesOf(attacker, incoming));
            perform(attacker, victim, source, onHit);

            // "造成伤害时"要的是<em>结算后</em>那个真的落到身上的数：被对面免疫成 0 的那发不算，
            // 所以它比 hit 少一条通道（同一个"打中了"，一个看动作成没成，一个看数落没落）。
            if (dealt > 0.0) {
                Triggers.Result onDealt = fire(attacker, attackView, Triggers.DAMAGE_DEALT,
                        basesOf(attacker, dealt));
                perform(attacker, victim, source, onDealt);
            }
        }
    }

    /**
     * 吸血落点。基数取<em>落到目标身上的最终值</em>（{@link Lifesteal} 那条口径，与 {@code damage_dealt}
     * 同一条律），而不是结算前的总量。
     *
     * <p>自家 {@code damage}/{@code reflect} 补出去的那一发<em>也吸</em>：把引擎自伤排除在触发器之外
     * 只是为了断递归（{@code damage→hit→damage→…}），而回复不产生任何事件，不会自己叫醒自己。</p>
     *
     * <p>不做"吸血上限"这类夹子——原版 {@code heal} 自己夹在生命上限内，再夹一层就是第二处真相。</p>
     */
    private static void healLifesteal(@Nullable State attacker, @Nullable Entity source, double dealt) {
        if (attacker == null || !(source instanceof LivingEntity living)) {
            return;
        }
        double amount = Lifesteal.amount(attacker.profile(), dealt);
        if (amount > 0.0) {
            living.heal((float) amount);
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
     * 原版跳跃暴击一律关掉（klze 裁定 2026-09-29："不需要跳跃"）。
     *
     * <p>为什么必须关：我们的暴击是攻方乘区里的一格（{@link CritRules}），而原版的 ×1.5 发生在
     * {@code Player#attack} 里、<em>早于</em>我们的接管点——不关的话一记空中暴击会变成
     * "原版 1.5 × 我们 1.3"，同一个乘区生效两遍（与"倍率只能生效一次"是同一条纪律）。</p>
     *
     * <p>用 {@code DENY} 而不是取消事件：1.20.1 的 {@code CriticalHitEvent} 是
     * {@code @HasResult}、<b>不可 cancel</b>（源码核过 {@code ForgeHooks#getCriticalHit}：
     * 只有 {@code ALLOW}、或"原版判成暴击且结果为 DEFAULT"时才把事件对象还回去）。
     * {@code DENY} 让 {@code Player#attack} 里的 {@code flag2} 变 false，于是<em>粒子与音效也一起停</em>
     * ——不会出现"看着像暴击、数值不是"那种错位（另一作者的实现正踩在那里）。</p>
     *
     * <p>顺带一条原版联动要记进口径：横扫（{@code flag3}）的前提之一是"这一发不是暴击"，
     * 关掉跳跃暴击之后，原本会被判成暴击的那几刀改为<em>可能触发横扫</em>。这是可观察的手感变化，
     * 不是 bug。</p>
     */
    @SubscribeEvent
    public static void onCriticalHit(CriticalHitEvent hit) {
        hit.setResult(Event.Result.DENY);
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
     *
     * <p>"挡掉多少"与这三道闸门无关，所以在它们<em>之前</em>就写回事件：它是全局战斗口径，
     * 一个没戴任何卡的人举盾也该按同一张表来（{@code manifest.json} 的
     * {@code combat.block_reduction}，出厂 1.0 = 原版整个取消）。</p>
     */
    @SubscribeEvent
    public static void onShieldBlock(ShieldBlockEvent blocked) {
        double incoming = blocked.getOriginalBlockedDamage();
        // 挡掉多少是<em>全局战斗口径</em>，与"这个人的卡要不要响"无关，所以它排在所有闸门之前：
        // 没戴卡的玩家举盾也按 manifest 的数来。数从 {@code data/nextcard/manifest.json} 的
        // {@code combat.block_reduction} 读（klze 2026-09-30："格挡先随便填个数，到时候到游戏里
        // 改 JSON 尝试手感"）。出厂值 1.0 与原版一致（整个取消），所以这条改动今天不动任何手感；
        // 0.2 = 只取消两成，其余照常走护甲与减免。
        double cancelled = ShieldBlock.blockedOf(incoming, blockReduction());
        if (cancelled != incoming) {
            blocked.setBlockedDamage((float) cancelled);
        }
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
        // 触发器的基数用<em>原始那一发</em>：判定看的是"举盾多久"，与挡掉多少无关；而奖励
        // （"反弹该次伤害 50%"）如果跟着这个数一起缩水，内容侧调手感就等于在偷偷改卡面。
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
        if (actor == null || result.extraHits().isEmpty() && result.knockbacks().isEmpty()
                && result.debuffs().isEmpty()) {
            return;
        }
        Player owner = actor.player();
        for (Triggers.Debuff debuff : result.debuffs()) {
            if (directTarget == null && noRadius(debuff.radius(), debuff.cardId(), "slow")) {
                continue;
            }
            for (LivingEntity target : around(owner, directTarget, debuff.radius())) {
                TargetStates.applySlow(target, debuff.cardId().toString(), debuff.percent(),
                        debuff.seconds(), owner.level().getGameTime());
            }
        }
        for (Triggers.ExtraHit hit : result.extraHits()) {
            if (hit.target() == Triggers.Target.ATTACKER) {
                // 反弹只还给打我的那位：找不到活体攻击者（箭、火、摔落）就是没对象，不搜半径
                if (attacker instanceof LivingEntity striker && striker != owner) {
                    striker.hurt(CardDamageSource.of(owner.level(), hit.cardId(), owner, hit.attribution()),
                            (float) hit.amount());
                }
                continue;
            }
            if (directTarget == null && noRadius(hit.radius(), hit.cardId(), "damage")) {
                continue;
            }
            for (LivingEntity target : around(owner, directTarget, hit.radius())) {
                target.hurt(CardDamageSource.of(owner.level(), hit.cardId(), owner, hit.attribution()), (float) hit.amount());
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
        Triggers.Result result = Triggers.fire(event, state.holder(), facts, bound,
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
        return settle(attack, contact, defence, vetoReason, incoming, NEVER_CRITS);
    }

    /**
     * 同一条链，但把<em>暴击那一次随机数</em>交进来。
     *
     * <p>为什么是 {@code double critRoll} 而不是 {@code Random}：暴击要在无头环境里被证明，
     * 而 {@code level.random} 的取值随世界状态变。传一个 {@code [0,1)} 的数进来，
     * "5% 初始暴击率""溢出再暴击"这类判据就能钉死具体数值；接管点负责从世界取那个数。</p>
     */
    @Nullable
    public static Settlement.Result settle(@Nullable MechanicProfile attack, @Nullable Facts contact,
                                           @Nullable MechanicProfile defence, @Nullable String vetoReason,
                                           double incoming, double critRoll) {
        return settle(attack, contact, defence, vetoReason, incoming, critRoll, CritRules.Armed.NONE);
    }

    /** 同一条链，再加"下一次攻击"那一格一次性武装（世界内走这条）。 */
    @Nullable
    public static Settlement.Result settle(@Nullable MechanicProfile attack, @Nullable Facts contact,
                                           @Nullable MechanicProfile defence, @Nullable String vetoReason,
                                           double incoming, double critRoll, CritRules.Armed armed) {
        AttackPipeline.Input input = attackInputFor(attack, incoming, contact, critRoll, armed);
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

    /** 不掷暴击判定的那个值（{@link CritRules#NO_ROLL}）：纯推演与既有断言走这条，暴击那一格不参与。 */
    public static final double NEVER_CRITS = CritRules.NO_ROLL;

    /**
     * 攻方的乘区输入；没有攻方快照时给 null（表示这一发不由卡来加成）。
     *
     * <p>2026-09-30 纠正过一次语义：{@code channel.direction_bonus} 是《00》公式末尾那句
     * <em>方向卡（火/雷/冰流派）带来的最外层加法</em>，与"从哪个方向打"无关，所以这里<em>不再</em>
     * 按接触门控它；几何那一族走 {@code channel.backstab_bonus}（《02》"背后 120° 额外一次 30%"），
     * 由接触决定加不加，接触不成立时管线仍会留一条"未生效"的痕。</p>
     *
     * <p>暴击走 {@link CritRules}：面板 = 5% 基线 + {@code channel.crit_chance}，
     * 倍率 = 130% 基线 + {@code channel.crit_damage}（无上限），超过 100% 的溢出<em>再暴击一次</em>。
     * 原版那套跳跃暴击由 {@link #onCriticalHit} 关掉，两处必须同时成立，否则就是双暴击。</p>
     */
    @Nullable
    public static AttackPipeline.Input attackInputFor(@Nullable MechanicProfile attack, double incoming,
                                                      @Nullable Facts contact) {
        return attackInputFor(attack, incoming, contact, NEVER_CRITS);
    }

    /** 带暴击 roll、但<em>没有</em>一次性武装的那一条（纯推演与既有断言走这条）。 */
    @Nullable
    public static AttackPipeline.Input attackInputFor(@Nullable MechanicProfile attack, double incoming,
                                                      @Nullable Facts contact, double critRoll) {
        return attackInputFor(attack, incoming, contact, critRoll, CritRules.Armed.NONE);
    }

    /**
     * 再带上<em>一次性武装</em>那一格（"下一次攻击必定暴击"那类）。
     *
     * <p>武装是<em>加进面板</em>的：+100% 与基线 5% 相加得 105%，按 {@link CritRules} 那条溢出规则
     * 就是"必定一次 + 5% 再来一次"。这里不特殊处理"武装了就一定只暴一次"，因为那会让裁定里
     * 两条规则互相打架。</p>
     */
    @Nullable
    public static AttackPipeline.Input attackInputFor(@Nullable MechanicProfile attack, double incoming,
                                                      @Nullable Facts contact, double critRoll,
                                                      CritRules.Armed armed) {
        if (attack == null) {
            return null;
        }
        double allDamage = attack.channel("all_damage");
        double melee = attack.channel("melee_damage");
        double direction = attack.channel("direction_bonus");
        double backstab = attack.channel("backstab_bonus");
        // 卡面说"必暴"就是要判定这一格：调用方那条"不判定"（{@code NO_ROLL}，纯推演与测试态用的）
        // 不能把已经消费掉的武装无声吞掉——否则账扣了、数没变，是最难查的那种静默。
        // 补进去的是"必定那一部分"，概率那一次仍然不赌。
        double roll = armed.chance() > 0.0 && critRoll < 0.0 ? CritRules.GUARANTEED_ONLY : critRoll;
        int crits = CritRules.critCount(CritRules.chance(attack, armed), roll);
        double crit = CritRules.totalMultiplier(CritRules.multiplier(attack, armed), crits);
        return new AttackPipeline.Input(incoming, 1.0, melee > 0 ? "melee" : null,
                Map.of("melee", melee), allDamage, 0.0, 0.0, crit, direction, false, 0.0, 0.0,
                AttackPipeline.penetration(), backstab, backstabHolds(contact), crits);
    }

    /**
     * 背刺这一格吃不吃：只有"这一发落在目标背后 120° 扇区内"才算。
     * 没有接触事实（纯推演）时按"算"处理，与管线里其他接触无关的格子一致。
     */
    public static boolean backstabHolds(@Nullable Facts contact) {
        return contact == null || contact.fromBehind(Predicates.BACK_SECTOR_HALF_ANGLE);
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
