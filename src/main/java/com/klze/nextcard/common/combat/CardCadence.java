package com.klze.nextcard.common.combat;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.core.effect.Cadence;
import com.klze.nextcard.core.effect.Triggers;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 周期驱动：卡面写 {@code "on": "tick", "every": 30.0}，这里负责<em>真的按节奏跑</em>。
 *
 * <p>裁定是「周期由引擎驱动，但卡自己声明周期」，{@link Cadence} 早就把"到点没有"的判据与
 * 唯一的秒→tick 换算写好了；缺的只是这个把玩家和他们的触发器过一遍的调用点。没有它，
 * "每 30 秒获得一层护盾"这类卡面写了也不会响——而 {@code every} 的解析与校验都是通的，
 * 于是看起来像"引擎支持"。</p>
 *
 * <p>三条读法（都跟着 {@link Cadence} 的既有裁定，不在这里改口）：</p>
 * <ul>
 *   <li><b>错过的那些不追补</b>：服务器卡了 3 秒不会把一次触发变成三次。</li>
 *   <li><b>起算点是"引擎第一次看见这张卡"的那一 tick</b>，不是世界开局，也不是玩家抽卡那一 tick
 *       之外的什么隐藏时刻；重启世界会重新起算（要跨存档精确对齐就得把 onset 存进卡账，
 *       那是另一件事，需要时再说）。</li>
 *   <li><b>只跑该跑的那几张</b>：同一个 tick 里多个周期都到点时按卡 id 字典序，
 *       而没到点的那张<em>不会</em>被顺带叫醒——所以 {@code fire} 收到的是筛过的子集。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = NextCard.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CardCadence {

    /** 周期触发唯一认的事件名（其它事件由各自的时刻派发）。 */
    public static final String TICK_EVENT = "tick";

    private static final Map<String, Long> onsets = new LinkedHashMap<>();

    private CardCadence() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }
        run(event.getServer().getPlayerList().getPlayers(), event.getServer().overworld().getGameTime());
    }

    /**
     * 走一遍所有在线玩家，把到点的周期触发兑现掉。
     *
     * <p>参数收在 {@code Player} 而不是 {@code ServerPlayer}，是为了能被 GameTest <em>手动拨表</em>：
     * 1.20.1 的 GameTest 拿不到真 {@code ServerPlayer}，而"等真 tick"的断言正是时绿时红的来源。</p>
     */
    public static void run(Iterable<? extends Player> players, long nowTick) {
        Set<String> alive = new HashSet<>();
        for (Player player : players) {
            CardCombat.State state = CardCombat.stateOf(player);
            if (state == null) {
                continue;
            }
            List<Triggers.Bound> due = new ArrayList<>();
            for (Triggers.Bound entry : state.triggers()) {
                if (!TICK_EVENT.equals(entry.clause().on()) || !entry.clause().periodic()) {
                    continue;
                }
                String key = state.holder() + "|" + entry.cardId();
                alive.add(key);
                long onset = onsets.computeIfAbsent(key, ignored -> nowTick);
                if (Cadence.isDue(Cadence.ticksOf(entry.clause().everySeconds()), onset, nowTick)) {
                    due.add(entry);
                }
            }
            if (due.isEmpty()) {
                continue;
            }
            Triggers.Result result = CardCombat.fire(state, DamageContact.stance(player), TICK_EVENT,
                    Triggers.Bases.NONE, due);
            CardCombat.perform(state, null, null, result);
        }
        onsets.keySet().retainAll(alive);
    }

    /** 测试与调试命令读它：现在引擎记着哪些周期起点。 */
    public static int trackedPeriods() {
        return onsets.size();
    }

    public static void resetForTests() {
        onsets.clear();
    }
}
