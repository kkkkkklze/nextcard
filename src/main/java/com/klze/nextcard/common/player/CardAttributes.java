package com.klze.nextcard.common.player;

import com.klze.nextcard.core.effect.MechanicProfile;
import com.klze.nextcard.core.effect.Mechanics;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 把折好快照里<em>能落成原版属性</em>的那四条通道，真的挂到玩家身上。
 *
 * <p>这是《挂点覆盖表》里排第一的阻塞项的一半：卡表那一列"附带数值"写的是
 * {@code +20 护甲值}、{@code +15% 移速}、{@code +20% 生命上限}——引擎早就把它们折进
 * {@link MechanicProfile#vanillaChannels()} 了，但<em>没有任何一处把它们挂上去</em>。
 * 少这一步，那些卡面数字全部静默不生效，而且表现得很像"卡没用"。</p>
 *
 * <p>三条形状：</p>
 * <ul>
 *   <li><b>幂等、按 UUID 换</b>：每条通道一个稳定的修饰符（UUID 由通道名派生），
 *       值没变就一个字节都不动；变了先摘旧的再挂新的。于是"重算代替增删"成立，
 *       不需要撤销路径，也不会因为同一张卡被重算两次而加成两遍。</li>
 *   <li><b>点值与比值不猜</b>：{@code armor} / {@code max_health} 是点值（ADDITION），
 *       {@code move_speed} / {@code attack_speed} 是比值（MULTIPLY_TOTAL）——
 *       分法由 {@link Mechanics#FLAT_CHANNELS} 声明，不在这里再抄一遍。</li>
 *   <li><b>上限降下来要夹血</b>：摘掉"+20% 生命上限"之后如果人还满血，原版会在下一 tick 才收，
 *       我们当场夹一次，免得出现"上限 20、当前 24"这种状态。</li>
 * </ul>
 */
public final class CardAttributes {

    /** 通道 → 原版属性（只有这四个 {@link Mechanics#VANILLA_CHANNELS}，其余走自定义管线）。 */
    private static final Map<String, Attribute> BY_CHANNEL = channels();

    private CardAttributes() {
    }

    private static Map<String, Attribute> channels() {
        Map<String, Attribute> map = new LinkedHashMap<>();
        map.put("armor", Attributes.ARMOR);
        map.put("max_health", Attributes.MAX_HEALTH);
        map.put("move_speed", Attributes.MOVEMENT_SPEED);
        map.put("attack_speed", Attributes.ATTACK_SPEED);
        return Map.copyOf(map);
    }

    /** 通道 → 稳定 UUID（同一通道永远同一个，摘与挂都按它）。 */
    public static UUID idOf(String channel) {
        return UUID.nameUUIDFromBytes(("nextcard:channel:" + channel).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按快照把四条通道挂到玩家身上。幂等：值没动就不碰属性。
     *
     * @param profile 折好的快照；null 表示"这玩家一张卡都没有"，要的是<em>把我们的都摘干净</em>
     */
    public static void apply(Player owner, MechanicProfile profile) {
        for (Map.Entry<String, Attribute> entry : BY_CHANNEL.entrySet()) {
            String channel = entry.getKey();
            AttributeInstance instance = owner.getAttribute(entry.getValue());
            if (instance == null) {
                continue;
            }
            applyOne(instance, channel, profile == null ? 0.0 : profile.channel(channel));
        }
        if (owner.getHealth() > owner.getMaxHealth()) {
            owner.setHealth(owner.getMaxHealth());
        }
    }

    /** 我们挂过哪些（诊断与测试读它，不再各写一份读法）。 */
    public static double applied(Player owner, String channel) {
        Attribute attribute = BY_CHANNEL.get(channel);
        AttributeInstance instance = attribute == null ? null : owner.getAttribute(attribute);
        AttributeModifier modifier = instance == null ? null : instance.getModifier(idOf(channel));
        return modifier == null ? 0.0 : modifier.getAmount();
    }

    private static void applyOne(AttributeInstance instance, String channel, double amount) {
        AttributeModifier existing = instance.getModifier(idOf(channel));
        if (existing == null && amount == 0.0) {
            return;
        }
        if (existing != null && existing.getAmount() == amount) {
            return;  // 值没变：一次属性抖动都不该有（抖动会让原版重算客户端同步）
        }
        if (existing != null) {
            instance.removeModifier(existing);
        }
        if (amount != 0.0) {
            // transient 而不是 permanent：这份账的真相在卡账里，属性只是投影。
            // 挂成永久修饰会被存进实体数据，模组卸载或卡被删时就留下摘不掉的残留。
            instance.addTransientModifier(new AttributeModifier(idOf(channel), "nextcard:" + channel,
                    amount, operation(channel)));
        }
    }

    private static AttributeModifier.Operation operation(String channel) {
        return Mechanics.FLAT_CHANNELS.contains(channel)
                ? AttributeModifier.Operation.ADDITION
                : AttributeModifier.Operation.MULTIPLY_TOTAL;
    }
}
