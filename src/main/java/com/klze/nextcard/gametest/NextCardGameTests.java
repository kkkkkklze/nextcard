package com.klze.nextcard.gametest;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.common.combat.CardCombat;
import com.klze.nextcard.common.load.CardContentReload;
import com.klze.nextcard.common.player.PlayerCardState;
import com.klze.nextcard.core.effect.Triggers;
import com.klze.nextcard.core.player.CardLedger;
import com.klze.nextcard.common.load.ContentBundle;
import com.klze.nextcard.common.registry.ModBlocks;
import com.klze.nextcard.common.registry.ModCreativeTabs;
import com.klze.nextcard.common.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests run a real server, so they can check things unit tests cannot: that every registry
 * actually got populated, that a block drops its item, that a machine ticks.
 *
 * <p>Run them with {@code gradlew runGameTestServer} when the project is configured with
 * {@code forge.enabledGameTestNamespaces} (see the {@code gameTestServer} run in build.gradle).</p>
 */
@GameTestHolder(NextCard.MODID)
@PrefixGameTestTemplate(false)
public class NextCardGameTests {
    /** Smoke test: the mod's content exists once the game has loaded. */
    @GameTest
    public void registriesArePopulated(GameTestHelper helper) {
        helper.assertTrue(ModBlocks.EXAMPLE_BLOCK.isPresent(), "example block must be registered");
        helper.assertTrue(ModItems.EXAMPLE_BLOCK_ITEM.isPresent(), "block item must be registered");
        helper.assertTrue(ModItems.EXAMPLE_ITEM.isPresent(), "example item must be registered");
        helper.assertTrue(ModCreativeTabs.EXAMPLE_TAB.isPresent(), "creative tab must be registered");

        // Force the class our mixin targets to load, so a mixin that cannot apply (wrong target,
        // stale refmap, dropped MixinExtras) fails this test loudly instead of showing up in-game.
        helper.assertTrue(Player.class != null, "Player must load with PlayerTickMixin applied");

        helper.succeed();
    }

    /**
     * 内容热重载真的跑通了：监听器加载到的张数，必须等于服务端资源里实际躺着的文件数。
     *
     * <p>这条断言不为"好看"存在。{@code SimpleJsonResourceReloadListener} 那类实现会在解析失败时
     * 只打一行日志然后<b>跳过那张卡</b>，于是模组照样启动、门照样绿，而内容已经少了几张——
     * 只有"条数对得上"这种粗判据能把它抓住。同时它也是 {@code CardContentReload.register()}
     * 有没有真的挂上游戏总线的判据：挂错总线的话这里会拿到 0。</p>
     */
    @GameTest
    public void contentReloadLoadsEveryFile(GameTestHelper helper) {
        ResourceManager manager = helper.getLevel().getServer().getResourceManager();
        long cardFilesOnDisk = manager.listResources("cards", path -> path.getPath().endsWith(".json")).size();
        long tagFilesOnDisk = manager.listResources("card_tags", path -> path.getPath().endsWith(".json")).size();
        ContentBundle loaded = CardContentReload.current();

        helper.assertTrue(cardFilesOnDisk > 0, "示例卡必须存在（0 张说明内容没打包进资源）");
        helper.assertTrue(loaded.cardFiles() == cardFilesOnDisk,
                "加载卡数 " + loaded.cardFiles() + " 与磁盘文件数 " + cardFilesOnDisk + " 不一致");
        helper.assertTrue(loaded.tagFiles() == tagFilesOnDisk,
                "加载标签数 " + loaded.tagFiles() + " 与磁盘文件数 " + tagFilesOnDisk + " 不一致");
        helper.assertTrue(!loaded.isEmpty(), "reload 监听器必须已经换过表（空表=没挂上或整次失败）");

        helper.succeed();
    }

    /**
     * 卡账 capability 真挂在玩家身上，并且写档/读档对得上。
     *
     * <p>{@code of()} 返回 null 代表"根本没挂上"——那只会在挂错总线或没注册时发生，
     * 而它的表现是"玩家莫名其妙没卡"，所以这里第一刀就砍在它身上。第三条断言防的是
     * "读档累加"：同一份 NBT 读两次必须还是同一份账。</p>
     */
    @GameTest
    public void playerCardStateRoundTripsThroughNbt(GameTestHelper helper) {
        Player player = helper.makeMockSurvivalPlayer();
        CardLedger ledger = PlayerCardState.of(player);
        helper.assertTrue(ledger != null, "card capability 必须挂在玩家身上（null = 没注册或挂错总线）");

        ResourceLocation card = CardContentReload.current().cards().byId().keySet().iterator().next();
        ledger.grant(card);
        ledger.recordDraw();
        CompoundTag saved = PlayerCardState.saveOf(ledger);

        CardLedger reloaded = new CardLedger();
        PlayerCardState.loadInto(reloaded, saved);
        helper.assertTrue(reloaded.owns(card), "写进去的卡要读得回来");
        helper.assertTrue(reloaded.drawCount() == 1, "抽卡次数要跟着往返：" + reloaded.drawCount());

        PlayerCardState.loadInto(reloaded, saved);
        helper.assertTrue(reloaded.size() == 1, "同一份档读两次不能翻倍：" + reloaded.size());
        helper.assertTrue(reloaded.drawCount() == 1, "抽卡次数也不能累加：" + reloaded.drawCount());

        helper.succeed();
    }

    /**
     * 卡的加成与减免真的改变世界里的一刀——不是只在纯逻辑里成立。
     *
     * <p>前面的门证明"能加载、能算"，这条证明"接在链路上"：{@code LivingDamageEvent} 是原版算完
     * 护甲之后、扣血之前的最后一道关口，只有真服务端能验。</p>
     *
     * <p>受害者一律用玩家而不是怪：怪会随难度随机穿上护甲（实测同一发 10 点只掉 9.84），
     * 那笔账是原版的，不是我们的；玩家空手空甲，进管线的数就是发过去的数。
     * 三条断言缺一不可：没卡时不许动数、攻方 +50% 打在最终值上、守方 −40% 落在同一发上。</p>
     */
    @GameTest
    public void cardsChangeDamageInALiveHit(GameTestHelper helper) {
        CardCombat.resetForTests();

        double untreated = dealtTo(helper.makeMockSurvivalPlayer(), helper.makeMockSurvivalPlayer(), 10.0F);
        helper.assertTrue(Math.abs(untreated - 10.0) < 1e-3, "谁都没有卡时原样落地，实际 " + untreated);
        helper.assertTrue(CardCombat.pipelineRuns() == 0,
                "没有可生效的账时，接管点一次都不该改数：" + CardCombat.pipelineRuns());

        Player striker = helper.makeMockSurvivalPlayer();
        grant(helper, striker, "ember_lash");
        double boosted = dealtTo(helper.makeMockSurvivalPlayer(), striker, 10.0F);
        helper.assertTrue(Math.abs(boosted - 15.0) < 1e-3,
                "全伤 +50% 要打在最终值上，实际 " + boosted + "；留痕 " + CardCombat.lastTrace());
        helper.assertTrue(CardCombat.pipelineRuns() == 1,
                "改过一次数就该记一次：" + CardCombat.pipelineRuns());
        helper.assertTrue(CardCombat.lastTrace().toString().contains("全伤"),
                "留痕要跟着进世界，否则线上问「为什么多打这点」没处指：" + CardCombat.lastTrace());

        Player guard = helper.makeMockSurvivalPlayer();
        grant(helper, guard, "ash_guard");
        // 攻方这里也得是玩家：原版在简单难度下会把怪的普攻削成 min(d/2+1, d)（实测 10 → 6），
        // 那笔账发生在我们的事件之前，用玩家攻方才能让三段站在同一发 10 点上比。
        double taken = dealtTo(guard, helper.makeMockSurvivalPlayer(), 10.0F);
        helper.assertTrue(Math.abs(taken - 6.0) < 1e-3,
                "守方 −40% 减伤要落在同一发上，实际 " + taken + "；留痕 " + CardCombat.lastTrace());
        helper.assertTrue(CardCombat.pipelineRuns() == 2,
                "两边各改一次：" + CardCombat.pipelineRuns());

        helper.succeed();
    }

    /**
     * 攻方的"命中时"在世界里兑现：打中一下攒一层「扎根」，下一发挨打时那层的减伤真的生效。
     *
     * <p>这条盯的是两个只在世界里才会暴露的错：① 层数变了但快照停在旧值（玩家看到
     * "层数在涨、减伤没动"）；② 兑现时机排在结算之前，把这一发的数也一起改了。</p>
     */
    @GameTest
    public void hitsBuildStacksThatFeedTheNextHit(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player striker = helper.makeMockSurvivalPlayer();
        grant(helper, striker, "iron_root");
        Player dummy = helper.makeMockSurvivalPlayer();
        String holder = striker.getUUID().toString();

        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "root") == 0.0,
                "开局该一层都没有");
        dealtTo(dummy, striker, 5.0F);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "root") == 1.0,
                "打中一下要攒一层（hit 这条事件在真实命中里跑到了）");

        double second = dealtTo(striker, dummy, 10.0F);
        helper.assertTrue(Math.abs(second - 9.0) < 1e-3,
                "一层的 10% 减伤要落在下一发上，实际 " + second + "；留痕 " + CardCombat.lastTrace());
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "root") == 1.0,
                "它挨的这一下不该也算命中：" + Triggers.layers(CardCombat.HOST.counters(), holder, "root"));

        dealtTo(dummy, striker, 5.0F);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "root") == 2.0,
                "第二次命中继续涨层");
        dealtTo(dummy, striker, 1.0F);
        dealtTo(dummy, striker, 1.0F);   // 只够攒层、不至于把靶子打死（死了的实体不会再触发兑现）
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "root") == 3.0,
                "声明的上限是 3 层，涨到那就停：" + Triggers.layers(CardCombat.HOST.counters(), holder, "root"));

        helper.succeed();
    }

    /**
     * 「风暴脉冲」的独立伤害与击退在世界里落到半径内的<em>第二个</em>目标上，并且不递归。
     *
     * <p>三个目标各自要证的事：① 没被直接打到的那只也掉了血 → 半径找目标真的跑了；
     * ② 掉的是<em>一发</em>的量（&lt; 2 点）→ {@code damage → hit → damage} 那条递归被
     * {@link CardDamageSource} 拦住了（拦不住的话这里不是红，是整条测试炸栈）；
     * ③ 那只同时被推动了，而它从没被直接攻击过 → 击退这条分支也真的落了地。</p>
     *
     * <p>反过来，<b>直接目标只吃到普通一击</b>：原版无敌帧（{@code invulnerableTime}）会把同一刻
     * 打到同一只的第二发吃掉，所以冲击波对刚被打中的目标不叠加。这是<em>顺带验实的现行行为</em>，
     * 不是漏了什么——要改它得给 damage_type 加 BYPASSES_COOLDOWN 标签，那属于玩法决定，
     * 得内容侧点头才动（见 {@link CardDamageSource} 的注释）。</p>
     */
    @GameTest
    public void shockwaveHitsANeighbourOnceAndDoesNotRecurse(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player striker = helper.makeMockSurvivalPlayer();
        grant(helper, striker, "storm_pulse");
        net.minecraft.world.phys.Vec3 anchor = helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5, 1.0, 0.5));
        striker.setPos(anchor.x, anchor.y, anchor.z);

        Zombie direct = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new net.minecraft.core.BlockPos(1, 1, 1));
        Zombie neighbour = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new net.minecraft.core.BlockPos(1, 1, 1));
        direct.setPos(anchor.x, anchor.y, anchor.z + 1.0);
        neighbour.setPos(anchor.x, anchor.y, anchor.z + 3.0);

        float directBefore = direct.getHealth();
        float neighbourBefore = neighbour.getHealth();
        direct.hurt(direct.damageSources().playerAttack(striker), 5.0F);
        double directLost = directBefore - direct.getHealth();
        double neighbourLost = neighbourBefore - neighbour.getHealth();

        helper.assertTrue(neighbourLost > 0.0, "半径 4 格内的邻居也该吃到这一发脉冲，实际 " + neighbourLost);
        helper.assertTrue(neighbourLost < 2.0,
                "只该吃到一发；吃到 " + neighbourLost + " 说明独立伤害把自己又触发了一次");
        helper.assertTrue(directLost > 4.0 && directLost < 5.05,
                "直接目标只吃到普通一击（5 点，被随机护甲削到 " + directLost + "）；脉冲那发被无敌帧吃掉");
        helper.assertTrue(neighbour.getDeltaMovement().horizontalDistanceSqr() > 0.0,
                "它从没被直接攻击过，被推动只能是击退动作的功劳");

        helper.succeed();
    }

    /**
     * 盾反在世界里跑得通：窗口内挡下 → 精准 → 攒一层壁障；出窗 → 只算普通格挡，不攒。
     *
     * <p>"举盾多久"这件事服务端只能靠 tick 推进（原版的 {@code useItemRemaining} 不公开写口），
     * 所以这里真的让假玩家 tick 几回——先把自己要的前提断言出来（{@code ticksBlocking} 落在
     * 期望区间内），免得"没攒层"其实是"根本没进入格挡状态"这种假绿。</p>
     */
    @GameTest
    public void parryingWithinTheWindowGrantsTheReward(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player guard = helper.makeMockSurvivalPlayer();
        grant(helper, guard, "cinder_step");
        String holder = guard.getUUID().toString();
        guard.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));
        net.minecraft.world.phys.Vec3 at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5, 1.0, 0.5));
        guard.setPos(at.x, at.y, at.z);
        Player attacker = helper.makeMockSurvivalPlayer();
        attacker.setPos(at.x, at.y, at.z + 2.0);  // 正面半球：原版只在正面取消伤害
        guard.setYRot(0.0F);
        guard.yHeadRot = 0.0F;  // 朝 +Z，所以 +Z 那边站着的就是正面

        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "wall") == 0.0,
                "开局没有壁障层");
        guard.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
        for (int i = 0; i < 6; i++) {
            guard.tick();
        }
        helper.assertTrue(guard.isBlocking(), "举盾 6 tick 后原版应当认他在挡");
        helper.assertTrue(CardCombat.ticksBlocking(guard) >= 5 && CardCombat.ticksBlocking(guard) <= 8,
                "窗口是 0.4 秒 = 8 tick，实测 ticksBlocking=" + CardCombat.ticksBlocking(guard));

        dealtTo(guard, attacker, 4.0F);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "wall") == 1.0,
                "窗口内挡下要算精准格挡并攒一层壁障");

        // 背后来的一律不算格挡：原版举盾只取消正面半球来的伤害，我们从背后挨的那发不该发奖励
        attacker.setPos(at.x, at.y, at.z - 2.0);
        guard.invulnerableTime = 0;
        dealtTo(guard, attacker, 4.0F);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "wall") == 1.0,
                "背后挨打不该算挡下、更不该攒壁障");
        attacker.setPos(at.x, at.y, at.z + 2.0);

        for (int i = 0; i < 40; i++) {
            guard.tick();
        }
        helper.assertTrue(guard.isBlocking() && CardCombat.ticksBlocking(guard) > 8,
                "前提：举久了还在挡，但已经出窗");
        guard.invulnerableTime = 0;
        dealtTo(guard, attacker, 4.0F);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "wall") == 1.0,
                "出窗的那一下只算普通格挡，不该攒层");

        helper.succeed();
    }

    /** 把卡记进玩家的卡账（卡账是真源，宿主由 {@code CardCombat} 在结算时同步）。 */
    private static void grant(GameTestHelper helper, Player player, String cardPath) {
        CardLedger ledger = PlayerCardState.of(player);
        helper.assertTrue(ledger != null, "card capability 必须挂在玩家身上");
        helper.assertTrue(ledger.grant(new ResourceLocation(NextCard.MODID, cardPath)),
                "示例卡必须存在：" + cardPath);
    }

    /**
     * 「背水一战」的免疫要在世界里成立：致命那一发被打成 0，不致命的那一发不许碰冷却，
     * 冷却在途时第二次致命要真的打死。
     *
     * <p>顺序是有意的：<b>先打一记会疼的</b>。若不先证明"这个受害者真的会掉血"，那么后面
     * "掉了 0 血"完全可能只是原版无敌帧把整发挡了（{@code invulnerableTime}），免疫根本没跑。
     * 每记之前把无敌帧清掉，也是为了不给这条假绿留通道。</p>
     */
    @GameTest
    public void lethalImmunityVetoesOnlyTheFatalHit(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player survivor = helper.makeMockSurvivalPlayer();
        grant(helper, survivor, "last_stand");
        String holder = survivor.getUUID().toString();
        double now = survivor.level().getGameTime() / 20.0;
        Player reaper = helper.makeMockSurvivalPlayer();

        double grazed = dealtTo(survivor, reaper, 5.0F);
        helper.assertTrue(Math.abs(grazed - 5.0) < 1e-3,
                "先要证明这一刀真的打得动（否则 0 伤害是假的）：" + grazed);
        helper.assertTrue(Triggers.inFlight(CardCombat.HOST.counters(), holder, now) == 0.0,
                "不致命的挨打不该把救命那次扣掉");

        double blocked = dealtTo(survivor, reaper, 30.0F);
        helper.assertTrue(blocked == 0.0,
                "致命那一发要被否决，实际掉血 " + blocked + "；留痕 " + CardCombat.lastTrace());
        helper.assertTrue(CardCombat.lastTrace().toString().contains("免疫"),
                "守方第①步的归因要指到那张卡：" + CardCombat.lastTrace());
        helper.assertTrue(Triggers.inFlight(CardCombat.HOST.counters(), holder, now) == 1.0,
                "用掉一次就该进冷却");

        double second = dealtTo(survivor, reaper, 30.0F);
        helper.assertTrue(second > 0.0, "冷却在途时第二次致命不该再被免疫：" + second);
        helper.assertTrue(survivor.isDeadOrDying(), "第二次要真的打死，剩 " + survivor.getHealth());

        helper.succeed();
    }

    /**
     * 接触事实在世界里成立：同一张「双牙」（背后 +30%），正面一刀不吃、背后一刀要吃。
     *
     * <p>这条是"方向增伤"第一次有世界内证据：之前那个乘区谁都没喂过值，喂了也只能证明
     * "卡面数字能折进快照"，证不了"引擎知道我从哪一刀砍的"。这里朝向与站位都由手摆，
     * 所以绿红只可能来自角度换算本身（{@link DamageContact#angleOffFront}）。</p>
     */
    @GameTest
    public void directionBonusNeedsTheActualContact(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player striker = helper.makeMockSurvivalPlayer();
        grant(helper, striker, "twin_fang");
        net.minecraft.world.phys.Vec3 anchor = helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5, 1.0, 0.5));

        place(striker, anchor.add(0.0, 0.0, 2.0));
        double fromFront = dealtTo(facing(helper, anchor, 0.0F), striker, 10.0F);
        place(striker, anchor.add(0.0, 0.0, -2.0));
        double fromBehind = dealtTo(facing(helper, anchor, 0.0F), striker, 10.0F);

        helper.assertTrue(Math.abs(fromFront - 10.0) < 1e-3,
                "正面那一刀不该吃方向加成，实际 " + fromFront + "；留痕 " + CardCombat.lastTrace());
        helper.assertTrue(Math.abs(fromBehind - 13.0) < 1e-3,
                "背后那一刀要吃 +30%，实际 " + fromBehind + "；留痕 " + CardCombat.lastTrace());
        helper.assertTrue(CardCombat.lastTrace().toString().contains("方向增伤 0.3")
                        && !CardCombat.lastTrace().toString().contains("未生效"),
                "背后那次的留痕要写明这一格真的进了乘区: " + CardCombat.lastTrace());

        helper.succeed();
    }

    /** 摆一个朝指定朝向的受害者（yaw 0 = 朝 +Z，所以 +Z 那边站的人就是正面）。 */
    private static Player facing(GameTestHelper helper, net.minecraft.world.phys.Vec3 at, float yaw) {
        Player victim = helper.makeMockSurvivalPlayer();
        victim.setPos(at.x, at.y, at.z);
        victim.setYRot(yaw);
        victim.yHeadRot = yaw;
        return victim;
    }

    private static void place(Player attacker, net.minecraft.world.phys.Vec3 at) {
        attacker.setPos(at.x, at.y, at.z);
    }

    /** 一发打在该玩家身上的实际掉血量（攻方是玩家就用玩家攻击，是怪就用怪物攻击）。 */
    private static double dealtTo(Player victim, LivingEntity attacker, float amount) {
        DamageSource source = attacker instanceof Player player
                ? victim.damageSources().playerAttack(player)
                : victim.damageSources().mobAttack(attacker);
        victim.invulnerableTime = 0;  // 无敌帧会把"没掉血"伪装成"被免疫了"
        float before = victim.getHealth();
        victim.hurt(source, amount);
        return before - victim.getHealth();
    }
}
