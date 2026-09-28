package com.klze.nextcard.common.combat;

import com.klze.nextcard.core.effect.Facts;
import com.klze.nextcard.core.effect.Triggers;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;

/**
 * 把一次真实命中折成 {@link Facts}（判定条件唯一的世界侧入口）。
 *
 * <p>为什么单独一类：入射角、距离、光照这三样原版拿得到，但算法只有一种正确写法——
 * 夹角要按<em>被打那位</em>的头朝向算，不是攻击者的朝向。写在调用点上就会被抄两遍，
 * 抄出两个方向就有一个是反的（背刺与格挡会同时错）。</p>
 *
 * <p><b>拿不到的留默认值，不猜</b>：发声量（要潜行/噪声系统）、"刚从视野外出现"与
 * "被吸引 / 被控制"（要索敌与控制状态系统）、精英/boss 归类（要内容侧标记）今天都没有世界侧来源，
 * 所以这些条件在世界里恒不成立。恒不成立是安全方向——只会让卡"没触发"，不会让它"错触发"。</p>
 *
 * <p><b>没有对面的那些事件（{@code tick}、以及伤害来自非活体的 {@code damage_taken}）里，
 * {@code target_kind} 会读到 {@code normal}、对面血量读到满格</b>。这不是"引擎认定它是普通怪"，
 * 而是这项事实无从谈起，只能落回 {@link Facts} 的默认值。所以<em>按目标写的条件不要挂在
 * {@code tick} 上</em>——那条事件没有"对面"。</p>
 */
public final class DamageContact {

    private DamageContact() {
    }

    /**
     * 攻方视角：{@code attackerHp} = 出手的那位，{@code targetHp} = 被打的那位，
     * 入射角按目标的朝向算。
     *
     * @return 出手的不是活体（箭、火、摔落）时给 {@code null}——那没有"从哪个方向打"可言，
     *         调用方按"方向条件不成立"处理
     */
    @Nullable
    public static Facts attackView(@Nullable Entity attacker, @Nullable Entity victim) {
        if (!(attacker instanceof LivingEntity striker) || !(victim instanceof LivingEntity target)) {
            return null;
        }
        return view(striker, target, angleOffFront(target, striker));
    }

    /**
     * 守方视角：{@code attackerHp} 这个位置装的永远是<em>这张卡的主人</em>（这里是我），
     * {@code targetHp} 是对面那位。{@code fatal} 按"原版算完护甲之后、扣血之前"判——
     * 免疫只有在这道关口成立才有意义。
     */
    public static Facts defendView(LivingEntity victim, @Nullable Entity attacker, double incoming) {
        Facts.Builder builder = viewBuilder(victim,
                attacker instanceof LivingEntity striker ? striker : null,
                attacker instanceof LivingEntity striker ? angleOffFront(victim, striker) : 0.0);
        if (Triggers.isFatal(victim.getHealth(), incoming)) {
            builder.with("fatal");
        }
        return builder.build();
    }

    /** 入射角：0 = 打在正面，180 = 正背后（{@link Facts#angleOffBack()} 取它的补）。 */
    public static double angleOffFront(LivingEntity target, Entity attacker) {
        double dx = attacker.getX() - target.getX();
        double dz = attacker.getZ() - target.getZ();
        if (dx == 0.0 && dz == 0.0) {
            return 0.0;  // 完全重合时没有方向可言，按正面算（吃不到方向增伤）
        }
        // 实体朝向：yaw 0 = +Z，方向向量是 (-sin yaw, cos yaw)，所以反推 atan2(-dx, dz)
        float yawToAttacker = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return Math.abs(Mth.wrapDegrees(yawToAttacker - target.yHeadRot));
    }

    private static Facts view(LivingEntity self, LivingEntity other, double angleOffFront) {
        return viewBuilder(self, other, angleOffFront).build();
    }

    /**
     * 没有对手的那一份事实：周期触发（{@code on: tick}）与调试命令读它。
     * 对面血量取 1.0、入射角取正面——<em>不是"知道在正面"，而是这件事无从谈起</em>，
     * 所以读这两项的条件本来就不该写进周期触发的 {@code when}。
     */
    public static Facts stance(Player owner) {
        return viewBuilder(owner, null, 0.0).build();
    }

    private static Facts.Builder viewBuilder(@Nullable LivingEntity self, @Nullable LivingEntity other,
                                             double angleOffFront) {
        Facts.Builder builder = Facts.builder()
                .angleOffFront(angleOffFront)
                .light(self == null ? 15 : self.level().getRawBrightness(self.blockPosition(), 0))
                // target_kind 说的永远是<em>对面</em>那位（"处决只打普通敌人"判的是被打的，不是打人的）。
                // 早先这里传的是 self，于是玩家自己出手时类别恒为 player——"只打普通敌人"那类卡
                // 永远不响，而"对玩家生效"那类卡永远响：这是会错触发的那一方向。
                .targetKind(kindOf(other));
        if (self != null) {
            builder.attackerHp(self.getHealth() / Math.max(1.0e-6, self.getMaxHealth()));
            if (self.onGround() && self.getDeltaMovement().horizontalDistanceSqr() > 1.0e-4) {
                builder.with("moving");
            }
            if (self.isCrouching()) {
                builder.with("sneaking");
            }
            if (self instanceof Player player && player.isBlocking()) {
                builder.with("blocking");
            }
        }
        if (other != null) {
            builder.distance(self == null ? 0.0 : self.distanceTo(other))
                    .targetHp(other.getHealth() / Math.max(1.0e-6, other.getMaxHealth()));
        }
        return builder;
    }

    /**
     * 目标类别。原版没有"精英"这个概念，所以只有玩家与 dragon 分得出来，其余一律 {@code normal}——
     * 卡表要按精英/boss 分账的话，得由内容侧给怪打标（已记进口径问题）。
     */
    private static String kindOf(@Nullable LivingEntity entity) {
        if (entity instanceof Player) {
            return "player";
        }
        if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) {
            return "boss";
        }
        return "normal";
    }
}
