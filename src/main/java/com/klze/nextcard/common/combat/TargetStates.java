package com.klze.nextcard.common.combat;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.core.effect.Cadence;
import com.klze.nextcard.core.effect.Slowness;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 目标侧<em>带时长的属性状态</em>——目前唯一的住户是减速（{@code slow} 动作）。
 *
 * <p>为什么要这么一块，而不是把原版药水效果用起来：注册一条 {@code MobEffect} 时
 * 属性修正的<em>量</em>就钉死在效果定义里，之后只有 {@code amplifier} 那一档
 * （原版算法是 {@code amount × (amplifier + 1)}，源码 {@code MobEffect#getAttributeModifierValue}），
 * 于是卡面写 30% 还是 22% 都只能靠"就近取整到级"来表达——那是第二个真相。
 * 我们自己挂一条带稳定 UUID 的 transient 修正，数值就是卡面那个数。</p>
 *
 * <p>三条形状：</p>
 * <ul>
 *   <li><b>一张卡对同一个目标只有一条</b>：再触发只是<em>刷新</em>时长，不会叠成两层减速。
 *       多张卡各自挂一条（UUID 由卡 id 派生），这才符合"不同来源的 debuff 各算各的"。</li>
 *   <li><b>transient（不写存档）</b>：与 {@code CounterStore} 那本账同一条律——运行期状态不跨重启，
 *       见《引擎侧进度》补十七。</li>
 *   <li><b>过期靠服务端 tick 摘</b>：不做"读取时惰性判活"，因为属性值是原版随时读的，
 *       没有我们的读口可以插判据；漏摘就是一条永久 debuff。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = NextCard.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TargetStates {

    /** 减速修正的来源键前缀（同一张卡对同一个人只有一条）。 */
    public static final String SLOW_FAMILY = "slow:";

    /** 护甲穿透修正的来源键前缀（同一个攻击者对同一个人只有一条）。 */
    public static final String ARMOR_FAMILY = "armor:";

    /** "只服务这一发"的那一档：0.05 秒 = 1 tick，正常路径不等它。 */
    public static final double ONE_TICK_SECONDS = 0.05;

    private record Entry(LivingEntity target, Attribute attribute, AttributeModifier modifier,
                         long expiresAtTick) {
    }

    /** 键是 (目标, 来源)：同一个来源对同一个人只有一条。 */
    private record Key(UUID target, String sourceKey) {
    }

    private static final Map<Key, Entry> active = new LinkedHashMap<>();

    private TargetStates() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }
        run(event.getServer().overworld().getGameTime());
    }

    /**
     * 给目标挂一条减速。秒→tick 的换算只走 {@link Cadence#ticksOf}（引擎唯一的换算处）。
     *
     * @param sourceKey 来源标识（现在是发卡那张卡的 id）——它同时决定修正的 UUID
     */
    public static void applySlow(LivingEntity target, String sourceKey, double percent,
                                 double seconds, long nowTick) {
        apply(target, Attributes.MOVEMENT_SPEED, SLOW_FAMILY + sourceKey, Slowness.amount(percent),
                AttributeModifier.Operation.MULTIPLY_TOTAL, seconds, nowTick);
    }

    /**
     * 临时摘掉目标这么多点护甲，只服务<em>这一发</em>：正常路径在 {@code LivingDamageEvent}
     * 里 {@link #releaseArmourPierce} 摘掉，那条早退的路径（有人把伤害改成 0）由每 tick 扫表兜住，
     * 所以最坏是"漏一 tick"而不是"永久减防"。
     *
     * <p>为什么要绕这么一圈：原版护甲曲线我们<em>不许再算一遍</em>（接管点在它之后，再算就是生效两次，
     * 见 {@code DefencePipeline} 的类注释）。而 {@code LivingHurtEvent} 恰好就发在
     * {@code getDamageAfterArmorAbsorb} 的<em>前一行</em>——于是"无视 30% 护甲"可以是
     * "把那 30% 在算之前借走"，曲线仍然只由原版算。不需要 AT，也不需要 Mixin。</p>
     */
    public static void applyArmourPierce(LivingEntity target, String attackerKey, double points,
                                         long nowTick) {
        apply(target, Attributes.ARMOR, ARMOR_FAMILY + attackerKey, -Math.max(0.0, points),
                AttributeModifier.Operation.ADDITION, ONE_TICK_SECONDS, nowTick);
    }

    /** 这一发算完护甲了，把借走的那部分还回去。 */
    public static void releaseArmourPierce(LivingEntity target, String attackerKey) {
        release(target, ARMOR_FAMILY + attackerKey);
    }

    /** 摘掉某个来源挂在这位身上的那条修正（没有就是没挂过，不报错）。 */
    public static void release(LivingEntity target, String key) {
        detach(active.remove(new Key(target.getUUID(), key)));
    }

    private static void apply(LivingEntity target, Attribute attribute, String key, double amount,
                              AttributeModifier.Operation operation, double seconds, long nowTick) {
        if (amount == 0.0) {
            return;   // 0 就是"什么都没改"，不挂一条空修正占位
        }
        AttributeInstance instance = target.getAttribute(attribute);
        if (instance == null) {
            return;   // 这个实体根本没有那条属性（不该挂，也不该编一个出来）
        }
        UUID id = idOf(key);
        instance.removeModifier(id);
        AttributeModifier modifier = new AttributeModifier(id, "nextcard " + key, amount, operation);
        instance.addTransientModifier(modifier);
        active.put(new Key(target.getUUID(), key),
                new Entry(target, attribute, modifier, nowTick + Cadence.ticksOf(seconds)));
    }

    /**
     * 修正的身份由来源键派生（v3 名字 UUID，格式与 {@code CardAttributes.idOf} 同一族）。
     * 一个来源一个 UUID：换 UUID 就等于留下摘不掉的孤儿修正。
     */
    public static UUID idOf(String key) {
        return UUID.nameUUIDFromBytes(
                ("nextcard:target:" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 摘掉过期的那些。GameTest 手动拨表调用它，而不是等真 tick（等真 tick 的断言时绿时红）。 */
    public static void run(long nowTick) {
        List<Key> due = new ArrayList<>();
        for (Map.Entry<Key, Entry> entry : active.entrySet()) {
            if (entry.getValue().expiresAtTick() <= nowTick) {
                due.add(entry.getKey());
            }
        }
        for (Key key : due) {
            detach(active.remove(key));
        }
    }

    private static void detach(Entry entry) {
        if (entry == null) {
            return;
        }
        AttributeInstance instance = entry.target().getAttribute(entry.attribute());
        if (instance != null) {
            instance.removeModifier(entry.modifier().getId());
        }
    }

    /** 现在挂着多少条（调试命令与断言读它）。 */
    public static int active() {
        return active.size();
    }

    /** 只给测试：整个清掉（属性侧与账本侧一起，不留孤儿修正）。 */
    public static void resetForTests() {
        for (Entry entry : new ArrayList<>(active.values())) {
            detach(entry);
        }
        active.clear();
    }
}
