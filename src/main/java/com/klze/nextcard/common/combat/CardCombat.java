package com.klze.nextcard.common.combat;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.common.load.CardContentReload;
import com.klze.nextcard.common.player.PlayerCardState;
import com.klze.nextcard.core.effect.AttackPipeline;
import com.klze.nextcard.core.effect.DefencePipeline;
import com.klze.nextcard.core.effect.EffectHost;
import com.klze.nextcard.core.effect.Facts;
import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.Settlement;
import com.klze.nextcard.core.effect.Triggers;
import com.klze.nextcard.core.player.CardLedger;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;

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
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        net.minecraft.world.entity.Entity source = event.getSource().getEntity();
        State attacker = stateOf(source);
        State defender = stateOf(event.getEntity());
        double incoming = event.getAmount();
        double otherHp = source instanceof LivingEntity living
                ? living.getHealth() / Math.max(1.0e-6, living.getMaxHealth())
                : 1.0;
        Settlement.Result result = settle(attacker == null ? null : attacker.profile(),
                defender == null ? null : defender.profile(), vetoFor(defender, otherHp, incoming), incoming);
        if (result == null) {
            return;
        }
        event.setAmount((float) result.value());
        pipelineRuns++;
        lastTrace = result.trace();
    }

    /**
     * 一个玩家的生效状态。卡账同步只做一次，机制快照与触发子句都从这次同步里来——
     * 分两次同步就会出现"快照是新的、触发器还是旧卡表那批"。
     *
     * @param health 此刻血量（免疫判定要按"扣完这一发还剩多少"判致命）
     * @param nowSeconds 世界时刻（秒），与 {@code CounterStore} 同一口径
     */
    public record State(String holder, @Nullable MechanicProfile profile, List<Triggers.Bound> triggers,
                        double health, double nowSeconds) {
    }

    /** 谁的卡账折出来的生效状态；没有玩家身份、没卡时给 null。 */
    @Nullable
    public static State stateOf(@Nullable net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
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
        return new State(holder, profile.slotIds().isEmpty() ? null : profile, HOST.triggers(holder),
                player.getHealth(), player.level().getGameTime() / 20.0);
    }

    /**
     * 免疫否决：<b>只有这一发确实致命时才成立</b>。判错方向的代价不对称——把普攻当成致命会白扣一次
     * 20 秒冷却，玩家真正该活下来的那一下就没免疫了。
     *
     * @param otherHpRatio 打我那位当前的血量比例。打我的不是活体（箭、火、摔落）时调用方按 1.0 传，
     *                     因为现没有任何守方卡面条件读它——用假值之前先要说明它是假的
     */
    public static @Nullable String vetoFor(@Nullable State defender, double otherHpRatio, double incoming) {
        if (defender == null || defender.triggers().isEmpty()) {
            return null;
        }
        if (!Triggers.isFatal(defender.health(), incoming)) {
            return null;
        }
        Facts facts = defenderFacts(defender.health(), otherHpRatio, incoming);
        Triggers.Firing firing = Triggers.lethalImmunity(defender.holder(), facts, defender.triggers(),
                HOST.counters(), defender.nowSeconds());
        return firing == null ? null : firing.cardId() + "：" + firing.reason();
    }

    /**
     * 守方视角的事实。{@link Facts} 的两个血量字段是<b>这张卡的主人</b>与<b>对面那一位</b>，
     * 所以守方这份快照里 {@code attackerHp} 装的是"我"的血量——命名欠一笔，等接触事实
     * （入射角/索敌状态）真进来时一起改，不在只有两个字段的时候先改一遍。
     */
    public static Facts defenderFacts(double ownHealth, double otherHpRatio, double incoming) {
        Facts.Builder builder = Facts.builder()
                .attackerHp(ownHealth / 20.0)
                .targetHp(otherHpRatio);
        if (Triggers.isFatal(ownHealth, incoming)) {
            builder.with("fatal");
        }
        return builder.build();
    }

    /**
     * 跑完整链；两边都没有可生效的账时返回 null（不参与、不改数）。
     * 纯函数，可无头测试——绑定 {@code ServerPlayer} 会让它没法被证明。
     */
    @Nullable
    public static Settlement.Result settle(@Nullable MechanicProfile attack, @Nullable MechanicProfile defence,
                                           double incoming) {
        return settle(attack, defence, null, incoming);
    }

    @Nullable
    public static Settlement.Result settle(@Nullable MechanicProfile attack, @Nullable MechanicProfile defence,
                                           @Nullable String vetoReason, double incoming) {
        AttackPipeline.Input input = attackInputFor(attack, incoming);
        DefencePipeline.Options options = defenceOptionsFor(defence, vetoReason);
        if (input == null && options == null) {
            return null;
        }
        return Settlement.resolve(input == null ? Settlement.plain(incoming) : input, options);
    }

    /** 攻方的乘区输入；没有攻方快照时给 null（表示这一发不由卡来加成）。 */
    @Nullable
    public static AttackPipeline.Input attackInputFor(@Nullable MechanicProfile attack, double incoming) {
        if (attack == null) {
            return null;
        }
        double allDamage = attack.channel("all_damage");
        double melee = attack.channel("melee_damage");
        double direction = attack.channel("direction_bonus");
        return new AttackPipeline.Input(incoming, 1.0, melee > 0 ? "melee" : null,
                Map.of("melee", melee), allDamage, 0.0, 0.0, 1.0, direction, false, 0.0, 0.0,
                AttackPipeline.penetration());
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
