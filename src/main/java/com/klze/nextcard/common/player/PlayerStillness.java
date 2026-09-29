package com.klze.nextcard.common.player;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.core.effect.Stillness;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * 每个玩家的静止采样：{@link Stillness} 这套纯算法在世界里唯一的调用点。
 *
 * <p>采样挂在 {@link TickEvent.PlayerTickEvent} 的 END 阶段（1.20.1 的 Forge 把它包在
 * {@code Player#tick} 的首尾，源码核过），所以<em>假玩家被手动 {@code tick()} 一次也会走这里</em>
 * ——GameTest 不需要额外的驱动口。</p>
 *
 * <p>读操作不写状态：<b>没见过的人</b>读出来是"静止 0 秒 + 没在动"。这不是"知道他站着"，而是
 * 无从谈起；两个方向的默认值都只会让卡<em>没触发</em>，不会让它错触发。第一次真正的采样之后
 * 才谈得上时长。</p>
 *
 * <p>账本按 {@code UUID} 存，玩家下线时摘掉（{@link #onPlayerLoggedOut}），不随世界时间增长。</p>
 */
@Mod.EventBusSubscriber(modid = NextCard.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerStillness {

    private static final Map<UUID, Stillness> SAMPLES = new ConcurrentHashMap<>();

    private PlayerStillness() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) {
            return;
        }
        sample(event.player);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SAMPLES.remove(event.getEntity().getUUID());
    }

    /** 采一帧当前位置：动了就把计时重新开始，没动就留着旧坐标慢慢累计。 */
    public static void sample(Player player) {
        long now = player.level().getGameTime();
        SAMPLES.compute(player.getUUID(), (uuid, previous) ->
                Stillness.observe(previous, player.getX(), player.getY(), player.getZ(), now));
    }

    /** 连续静止了多少秒（{@code still_seconds} 的出处）。 */
    public static double stillSeconds(Player player) {
        return Stillness.secondsStill(current(player), player.getX(), player.getY(), player.getZ(),
                player.level().getGameTime());
    }

    /** 相对上一次采样动了没有（{@code moving} 的出处，与上面那个时长同源）。 */
    public static boolean moving(Player player) {
        Stillness track = current(player);
        return track != null && track.moving(player.getX(), player.getY(), player.getZ());
    }

    /** 测试与调试用：当前记着几个人、以及把整本采样清空。 */
    public static int trackedPlayers() {
        return SAMPLES.size();
    }

    public static void resetForTests() {
        SAMPLES.clear();
    }

    /** 这个人的当前采样；还没见过他时给 null（{@link Stillness} 把 null 读成"0 秒 / 没动"）。 */
    @Nullable
    private static Stillness current(Player player) {
        return SAMPLES.get(player.getUUID());
    }
}
