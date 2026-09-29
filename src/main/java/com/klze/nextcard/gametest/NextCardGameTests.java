package com.klze.nextcard.gametest;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.common.combat.CardCadence;
import com.klze.nextcard.common.combat.CardCombat;
import com.klze.nextcard.common.combat.CardDamageSource;
import com.klze.nextcard.common.load.CardContentReload;
import com.klze.nextcard.common.player.CardAttributes;
import com.klze.nextcard.common.player.PlayerCardState;
import com.klze.nextcard.common.player.PlayerStillness;
import com.klze.nextcard.core.effect.AttackPipeline;
import com.klze.nextcard.core.effect.CritRules;
import com.klze.nextcard.core.effect.ParryTiming;
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
     * <p>反过来，<b>直接目标吃到两发</b>：普通一击 + 冲击波那发。2026-09-29 之前这里只会掉一发
     * （原版无敌帧把同一刻打到同一只的第二发整个吞掉），klze 裁定"本 mod 的所有伤害无视无敌帧"之后，
     * 自家杀伤类型挂上了 {@code BYPASSES_COOLDOWN} 标签，第二发才真的落下。
     * 标签本身由 {@code engineDamageIgnoresTheInvulnerabilityWindow} 直接断言。</p>
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
        helper.assertTrue(directLost > 5.0 && directLost < 6.4,
                "直接目标现在吃到两发（普通一击 5 点 + 脉冲 0.6 点，都被随机护甲削一点，实测 "
                        + directLost + "）——2026-09-29 起自家伤害绕过无敌帧，不再只吃一发");
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
        helper.assertTrue(Math.abs(20.0f - attacker.getHealth() - 2.0) < 1e-3,
                "弹反成功要把这发的 50% 还给攻击者（原始 blocked 4 点 → 2 点），实际掉血 "
                        + (20.0f - attacker.getHealth()));

        // 背后来的一律不算格挡：原版举盾只取消正面半球来的伤害，我们从背后挨的那发不该发奖励
        attacker.setPos(at.x, at.y, at.z - 2.0);
        guard.invulnerableTime = 0;
        float attackerBeforeBehind = attacker.getHealth();
        dealtTo(guard, attacker, 4.0F);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "wall") == 1.0,
                "背后挨打不该算挡下、更不该攒壁障");
        helper.assertTrue(attacker.getHealth() == attackerBeforeBehind,
                "没弹反成功就没有反弹：攻击者的血一分都不该少");
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

    /**
     * "附带数值"真的挂到身上：+10 护甲、+20% 移速进属性；卡没了就得摘干净。
     *
     * <p>这条堵的是覆盖表里排第一的那半：通道折进快照了，但没人把它们投影到原版属性上，
     * 于是卡表那一整列 {@code +20 护甲值} 是空话——而且表现得很像"这张卡没用"。</p>
     */
    @GameTest
    public void vanillaChannelsLandOnAttributes(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player owner = helper.makeMockSurvivalPlayer();
        grant(helper, owner, "venom_edge");

        double armorBefore = owner.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        double speedBefore = owner.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        CardCombat.stateOf(owner);   // 上线/命中时同步一次，属性就跟着投影

        double armorAfter = owner.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        double speedAfter = owner.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        helper.assertTrue(Math.abs(armorAfter - armorBefore - 10.0) < 1e-6,
                "护甲该 +10 点，实际 " + (armorAfter - armorBefore));
        helper.assertTrue(Math.abs(speedAfter - speedBefore * 1.2) < 1e-9,
                "移速该是 ×1.2（比值通道，不是点值），实际 " + speedAfter + " vs " + speedBefore);

        CardCombat.stateOf(owner);
        helper.assertTrue(Math.abs(owner.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.ARMOR) - armorAfter) < 1e-9,
                "同一个值重算两遍不能加成两遍（幂等，按 UUID 换）");

        CardAttributes.apply(owner, null);
        helper.assertTrue(Math.abs(owner.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.ARMOR) - armorBefore) < 1e-9,
                "快照清空后我们的修饰必须摘干净");
        helper.assertTrue(Math.abs(owner.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) - speedBefore) < 1e-9,
                "移速那条也要一起摘");

        helper.succeed();
    }

    /**
     * 强制精准：先实打实挨一下（拿窗口），再举盾出窗挡一下——那一下要按精准算、要攒到层。
     *
     * <p>与 {@code parryingWithinTheWindowGrantsTheReward} 成对：那张证"出窗不攒"，这张证
     * "被强制时出窗也攒"。顺序是故意的：**先不举盾挨一下**才有 {@code damage_taken}（举盾挡下的
     * 那发被原版整个取消，走不到伤害事件，也就拿不到窗口）。</p>
     */
    @GameTest
    public void forcedParryWindowMakesLateBlocksCountAsPrecise(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player guard = helper.makeMockSurvivalPlayer();
        grant(helper, guard, "cinder_step");
        grant(helper, guard, "iron_root");
        String holder = guard.getUUID().toString();
        net.minecraft.world.phys.Vec3 at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5, 1.0, 0.5));
        guard.setPos(at.x, at.y, at.z);
        guard.setYRot(0.0F);
        guard.yHeadRot = 0.0F;
        Player attacker = helper.makeMockSurvivalPlayer();
        attacker.setPos(at.x, at.y, at.z + 2.0);

        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "wall") == 0.0,
                "开局没有壁障层");
        dealtTo(guard, attacker, 4.0F);   // 没举盾，这发实打实落在身上
        helper.assertTrue(Triggers.forcedPrecise(holder, CardCombat.HOST.triggers(holder),
                        CardCombat.HOST.counters(), guard.level().getGameTime() / 20.0),
                "挨过一下之后应当有强制精准的在途窗口");
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "wall") == 0.0,
                "没举盾就没有格挡，窗口拿到了也不该直接攒层");

        guard.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));
        guard.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
        for (int i = 0; i < 40; i++) {
            guard.tick();
        }
        helper.assertTrue(guard.isBlocking() && !ParryTiming.precise(CardCombat.ticksBlocking(guard), 0.4),
                "前提：还在挡，但按真窗口已经出窗（ticksBlocking=" + CardCombat.ticksBlocking(guard) + "）");

        guard.invulnerableTime = 0;
        dealtTo(guard, attacker, 4.0F);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "wall") == 1.0,
                "被强制的这一次要按精准算，才会攒到壁障层");

        helper.succeed();
    }

    /**
     * 周期驱动真的按节奏跑：卡面写 {@code every: 2.0}，每 40 tick 兑现一次，没到点不追加。
     *
     * <p>表是手动拨的（{@link CardCadence#run}），因为 1.20.1 的 GameTest 拿不到真
     * {@code ServerPlayer}，而"等真 tick"的断言正是时绿时红的来源。四步各钉一件事：
     * 起算那一 tick 不触发、到点触发一次、下一个周期再来一次、没到点的那一 tick 不动。</p>
     */
    @GameTest
    public void periodicTriggersFireOnTheirOwnClock(GameTestHelper helper) {
        CardCombat.resetForTests();
        CardCadence.resetForTests();
        Player owner = helper.makeMockSurvivalPlayer();
        grant(helper, owner, "phoenix_breath");
        String holder = owner.getUUID().toString();
        long start = owner.level().getGameTime();
        java.util.List<net.minecraft.world.entity.player.Player> online = java.util.List.of(owner);

        CardCadence.run(online, start);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "ember") == 0.0,
                "第一次看见这张卡只是起算，不该马上兑现");
        CardCadence.run(online, start + 39);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "ember") == 0.0,
                "39 tick 还没到一个 2 秒周期");
        CardCadence.run(online, start + 40);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "ember") == 1.0,
                "到点该攒一层");
        CardCadence.run(online, start + 79);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "ember") == 1.0,
                "没到点的那一 tick 不能顺带叫醒它");
        CardCadence.run(online, start + 80);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "ember") == 2.0,
                "第二个周期照常");
        helper.assertTrue(CardCadence.trackedPeriods() == 1,
                "引擎只该记着这一个周期起点：" + CardCadence.trackedPeriods());

        helper.succeed();
    }

    /**
     * 出手与造成伤害是两条事件：一刀被对面的免疫打成 0，"出手"照记，"造成伤害"不许记。
     *
     * <p>这条盯的是{@code attack} 与 {@code damage_dealt} 被写成一件事的错法——它们本来就该分开：
     * {@code AttackEntityEvent} 由 {@code Player#attack} 的第一行发出（原版源码核过），那一刻
     * 这一发<em>还没有数</em>；而数落在没落在身上要等结算完。用一个"必然致命、必然被免疫"的
     * 受害者把两者劈开：0.05 点血让任何一点伤害都算致命，免疫把最终值打成 0。</p>
     *
     * <p>第二步反过来验"落地才记账"：满血挨一刀，掉了血才有第二层。</p>
     */
    @GameTest
    public void swingsAreCountedEvenWhenTheBladeDoesNotLand(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player striker = helper.makeMockSurvivalPlayer();
        grant(helper, striker, "venom_cascade");
        grant(helper, striker, "grave_bloom");
        Player victim = helper.makeMockSurvivalPlayer();
        grant(helper, victim, "last_stand");
        String holder = striker.getUUID().toString();

        victim.setHealth(0.05F);
        victim.invulnerableTime = 0;
        striker.attack(victim);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "venom") == 1.0,
                "出手就要叠毒，哪怕这一刀最后一点伤害都没落地");
        helper.assertTrue(Math.abs(victim.getHealth() - 0.05F) < 1e-6,
                "前提：那发致命被免疫打成 0，受害者一分血都不该少，实际 " + victim.getHealth());
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "bloom") == 0.0,
                "这一发没造成任何伤害，damage_dealt 不该响");

        victim.setHealth(20.0F);
        victim.invulnerableTime = 0;
        striker.attack(victim);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "venom") == 2.0,
                "第二刀照样算出手");
        helper.assertTrue(victim.getHealth() < 20.0F,
                "先确认这一刀真的落地了（否则下面那条 bloom 是假的绿），实际 " + victim.getHealth());
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "bloom") == 1.0,
                "真的造成了伤害才该记账");

        helper.succeed();
    }

    /**
     * 击杀在世界里叫醒 {@code kill}，并且只叫醒<em>真的打死</em>的那一次。
     *
     * <p>「连锁反应」写的是 {@code on: kill, when: [{target_kind: normal}]}，所以这条一次证两件事：
     * ① 击杀事件真的派发（不致命的削血之后邻居一点事没有，致命那发之后邻居掉血并被推开）；
     * ② {@code target_kind} 判的是<em>对面</em>那位而不是持卡人自己——修之前这里取的是
     * {@code kindOf(self)}，玩家持卡永远读到 {@code player}，条件恒不成立，邻居永远不会被波及。</p>
     */
    @GameTest
    public void killingANormalTargetChainsOntoANeighbour(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player striker = helper.makeMockSurvivalPlayer();
        grant(helper, striker, "chain_reaction");
        net.minecraft.world.phys.Vec3 anchor = helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5, 1.0, 0.5));
        striker.setPos(anchor.x, anchor.y, anchor.z);

        Zombie doomed = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        Zombie neighbour = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        doomed.setPos(anchor.x, anchor.y, anchor.z + 1.0);
        neighbour.setPos(anchor.x, anchor.y, anchor.z + 2.0);

        double untouchedBefore = neighbour.getHealth();
        doomed.invulnerableTime = 0;
        doomed.hurt(doomed.damageSources().playerAttack(striker), 1.0F);
        helper.assertTrue(!doomed.isDeadOrDying(), "前提：这一刀只是削血，还没打死");
        helper.assertTrue(Math.abs(neighbour.getHealth() - untouchedBefore) < 1e-6,
                "没打死就没有连锁，邻居不该掉血：" + untouchedBefore + " → " + neighbour.getHealth());

        doomed.invulnerableTime = 0;
        doomed.hurt(doomed.damageSources().playerAttack(striker), 999.0F);
        helper.assertTrue(doomed.isDeadOrDying(), "这一刀要真的打死它");
        double chained = untouchedBefore - neighbour.getHealth();
        helper.assertTrue(chained > 0.0, "半径 4 格内的邻居该吃到击杀连锁那一发，实际 " + chained);
        helper.assertTrue(chained < 2.0,
                "只该吃到一发；吃到 " + chained + " 说明连锁把自己又触发了一次");
        helper.assertTrue(neighbour.getDeltaMovement().horizontalDistanceSqr() > 0.0,
                "它从没被直接攻击过，被推动只能是连锁那一发的击退");

        helper.succeed();
    }

    /**
     * 没有对面的事件上，"只碰直接目标"的动作要<em>报出来</em>并跳过，不许把服务器打抛。
     *
     * <p>这条是给内容侧看的：{@code tick}（周期）与 {@code attack} 上没有"打我的那位"，
     * 半径 0 的动作在这里天然没有对象。计划由测试直接交给 {@link CardCombat#perform}，
     * 因为要的是那条<em>非正常路径</em>——写卡的人踩到时是周期触发，而周期触发的表要手动拨时钟才有。</p>
     */
    @GameTest
    public void actionsNeedingATargetAreReportedWhenThereIsNone(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player owner = helper.makeMockSurvivalPlayer();
        grant(helper, owner, "storm_pulse");
        CardCombat.State state = CardCombat.stateOf(owner);
        helper.assertTrue(state != null, "有卡的玩家必须拿得到生效状态");

        Triggers.Result plan = new Triggers.Result(null, java.util.List.of(),
                java.util.List.of(new Triggers.ExtraHit(new ResourceLocation(NextCard.MODID, "storm_pulse"),
                        3.0, 0.0, Triggers.Target.AROUND_OWNER, "测试用：半径 0 且没有对面")),
                java.util.List.of(), java.util.List.of());

        CardCombat.perform(state, null, null, plan);
        helper.assertTrue(CardCombat.reportedGaps().stream()
                        .anyMatch(gap -> gap.contains("storm_pulse") && gap.contains("没有对面")),
                "没有对象的动作要报出来，不许静默跳过：" + CardCombat.reportedGaps());

        helper.succeed();
    }

    /**
     * 层数条件在世界里读的是<em>当下</em>的账，不是恒 0。
     *
     * <p>{@code {"stacks": {"id": "venom", "at_least": 3}}} 这类"叠满才怎样"的写法，判定读的是
     * 事实快照；如果接管点没把 {@code CounterStore} 的层数贴进快照，这条条件在游戏里<em>恒不成立</em>，
     * 表现和"这张卡没用"一模一样。三刀各钉一件事：一、两层时不额外补伤害；二、第三刀补了；
     * 三、层数账本身按刀涨。</p>
     */
    @GameTest
    public void stackGatedConditionsReadTheLiveLayerCount(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player striker = helper.makeMockSurvivalPlayer();
        grant(helper, striker, "venom_cascade");
        Player victim = helper.makeMockSurvivalPlayer();
        String holder = striker.getUUID().toString();

        double first = swing(striker, victim);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "venom") == 1.0,
                "一刀该叠一层");
        helper.assertTrue(first < 1.0, "一层时只有出手那一击本身，掉了 " + first);

        double second = swing(striker, victim);
        helper.assertTrue(second < 1.0,
                "两层还没到 at_least: 3，不该有额外那一发，掉了 " + second);

        double third = swing(striker, victim);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "venom") == 3.0,
                "三刀三层");
        helper.assertTrue(third > second + 1.0,
                "叠满三层的那一刀要额外补一发（2 点常数伤害，被原版无敌帧按差值削成 1.8），"
                        + "实际 " + third + " vs 上一刀的 " + second);

        helper.succeed();
    }

    /**
     * "本局累计 N 次之后"这类条件在世界里读的是真实账本。
     *
     * <p>「墓畔花开」写的是 {@code on: damage_dealt, when: [{count: {on: damage_dealt, at_least: 2}}]}
     * ——第一次真伤害只有「花开」，第二次起才同时有「回声」。这两层分开断言，才能证明计数是
     * <em>把这一次也算进去</em>再加的（反过来"先判后加"会让第一次回声等到第三次，方向是"该响没响"）。</p>
     */
    @GameTest
    public void countedEventsReachConditionsInTheWorld(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player striker = helper.makeMockSurvivalPlayer();
        grant(helper, striker, "grave_bloom");
        Player victim = helper.makeMockSurvivalPlayer();
        String holder = striker.getUUID().toString();

        swing(striker, victim);
        helper.assertTrue(Triggers.times(CardCombat.HOST.counters(), holder, Triggers.DAMAGE_DEALT) == 1,
                "这一发的数要跟着记账");
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "bloom") == 1.0,
                "没有门槛的那条每次都兑现");
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "echo") == 0.0,
                "at_least: 2 在第一次不该成立");

        swing(striker, victim);
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "bloom") == 2.0,
                "花开照旧涨到第二层");
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "echo") == 1.0,
                "第二次真伤害就该有回声——计数把这一次算进去了");

        helper.succeed();
    }

    /**
     * 静止时长在世界里是真的按"位置有没有变"数的，而且一动就归零。
     *
     * <p>「凤凰吐息」写的是 {@code on: hit, when: [{still_seconds: 1.0}]}（站定一秒后打出的那一下
     * 攒一层「蓄燃」）。三段各钉一件事：</p>
     * <ol>
     *   <li><b>还没采过样 = 0 秒</b>，不是"已经站了很久"——默认值只会让卡不触发；</li>
     *   <li><b>站着等够 25 tick（1.25 秒）</b>再打，才攒到那一层（这一步走真 tick，
     *       因为时长用的是世界时刻，假玩家不会自己 tick）；</li>
     *   <li><b>挪开三个方块再打</b>：层数不涨，而且 {@code moving} 当场为真——这钉的是
     *       "动了就没有时长可言"这条同源关系（早先 {@code moving} 由速度算、时长没人算，两者会矛盾）。</li>
     * </ol>
     */
    @GameTest
    public void stillnessIsMeasuredFromRealMovement(GameTestHelper helper) {
        CardCombat.resetForTests();
        PlayerStillness.resetForTests();
        Player striker = helper.makeMockSurvivalPlayer();
        grant(helper, striker, "phoenix_breath");
        Player victim = helper.makeMockSurvivalPlayer();
        String holder = striker.getUUID().toString();
        net.minecraft.world.phys.Vec3 at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5, 1.0, 0.5));
        striker.setPos(at.x, at.y, at.z);

        swing(striker, victim);
        helper.assertTrue(PlayerStillness.trackedPlayers() == 0,
                "没人 tick 过就不该有采样（读操作不写状态）");
        helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "kindled") == 0.0,
                "第一眼不该算已经站满一秒");

        striker.tick();   // 采一帧：起点 = 这一刻的世界时刻
        helper.assertTrue(PlayerStillness.stillSeconds(striker) < 0.1,
                "刚采样完的时长必须接近 0");

        helper.runAfterDelay(25, () -> {
            double stood = PlayerStillness.stillSeconds(striker);
            helper.assertTrue(stood >= 1.0, "站了 25 tick 该读到 ≥ 1 秒，实际 " + stood);
            helper.assertTrue(!PlayerStillness.moving(striker),
                    "站着的时候不该判成在动");
            swing(striker, victim);
            helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "kindled") == 1.0,
                    "站定那一秒之后打出的那一下才该攒「蓄燃」");

            striker.setPos(at.x + 3.0, at.y, at.z);
            helper.assertTrue(PlayerStillness.moving(striker),
                    "位置变了就该当场判成在动");
            helper.assertTrue(PlayerStillness.stillSeconds(striker) == 0.0,
                    "动了之后时长归零");
            swing(striker, victim);
            helper.assertTrue(Triggers.layers(CardCombat.HOST.counters(), holder, "kindled") == 1.0,
                    "挪开之后打的那一下不该再攒层");

            helper.succeed();
        });
    }

    /**
     * 暴击在世界里成立，而且<em>原版那一格被关掉了</em>。
     *
     * <p>三段各钉一件事：① 卡面的 {@code channel.crit_chance}/{@code crit_damage} 真的折进快照，
     * 面板 = 5% 基线 + 卡面增量、倍率 = 130% 基线 + 卡面增量；② 我们订阅的
     * {@code CriticalHitEvent} 把原版跳跃暴击判成 {@code DENY}——不关的话一记空中暴击会变成
     * "原版 1.5 × 我们 1.3"，同一个乘区生效两遍；③ 同一个快照喂进乘区，roll 交进来时
     * 得到的就是那条链的数（世界里的随机数来自 {@code level.random}，所以这里注入而不是等运气）。</p>
     */
    @GameTest
    public void critComesFromTheEngineNotFromTheJump(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player striker = helper.makeMockSurvivalPlayer();
        grant(helper, striker, "venom_edge");
        CardCombat.State state = CardCombat.stateOf(striker);
        helper.assertTrue(state != null && state.profile() != null, "示例卡的通道要折进快照");

        helper.assertTrue(Math.abs(CritRules.chance(state.profile()) - 0.10) < 1e-9,
                "面板暴击率 = 5% 基线 + 卡面 5%，实际 " + CritRules.chance(state.profile()));
        helper.assertTrue(Math.abs(CritRules.multiplier(state.profile()) - 1.4) < 1e-9,
                "暴伤倍率 = 130% 基线 + 卡面 10%，实际 " + CritRules.multiplier(state.profile()));

        net.minecraftforge.event.entity.player.CriticalHitEvent vanillaCrit =
                new net.minecraftforge.event.entity.player.CriticalHitEvent(striker,
                        helper.makeMockSurvivalPlayer(), 1.5F, true);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(vanillaCrit);
        helper.assertTrue(vanillaCrit.getResult() == net.minecraftforge.eventbus.api.Event.Result.DENY,
                "原版跳跃暴击必须被关掉（否则与我们的乘区叠成双暴击），实际 result="
                        + vanillaCrit.getResult());

        AttackPipeline.Input input = CardCombat.attackInputFor(state.profile(), 100.0, null, 0.0);
        helper.assertTrue(input != null && Math.abs(input.critMultiplier() - 1.4) < 1e-9,
                "同一份快照喂进乘区要拿到 ×1.4，实际 " + (input == null ? "null" : input.critMultiplier()));
        helper.assertTrue(Math.abs(CardCombat.attackInputFor(state.profile(), 100.0, null,
                CardCombat.NEVER_CRITS).critMultiplier() - 1.0) < 1e-9,
                "不判定的那条入口不能带进任何暴击系数");

        // 端到端：把 roll 钉成"一定中"，真实一刀就该是 10 → 14（面板 10% 暴、倍率 ×1.4）
        CardCombat.pinCritRoll(0.0);
        double critHit = dealtTo(helper.makeMockSurvivalPlayer(), striker, 10.0F);
        helper.assertTrue(Math.abs(critHit - 14.0) < 1e-3,
                "真实一刀要吃到我们自己的暴击，实际 " + critHit + "；留痕 " + CardCombat.lastTrace());
        helper.assertTrue(CardCombat.lastTrace().toString().contains("× 暴击 1.4"),
                "留痕要指到暴击那一格：" + CardCombat.lastTrace());

        CardCombat.resetForTests();
        double plainHit = dealtTo(helper.makeMockSurvivalPlayer(), striker, 10.0F);
        helper.assertTrue(Math.abs(plainHit - 10.0) < 1e-3,
                "不判定暴击时原样落地（这条同时钉住「原版跳跃暴击没在数值里留下痕迹」），实际 " + plainHit);

        helper.succeed();
    }

    /**
     * 自家伤害绕过原版无敌帧（klze 裁定 2026-09-29："让本 mod 的所有伤害无视无敌帧"）。
     *
     * <p>两条断言各钉一层，缺一不可：</p>
     * <ol>
     *   <li><b>标签真的挂在那条注册表 damage_type 上</b>。这条最容易自欺：代码里
     *       {@code new Holder.Direct<>(…)} 造出来的杀伤类型，{@code is(TagKey)} <em>永远返回 false</em>
     *       （{@code Holder.Direct} 里那几个 {@code is} 是硬写死的），所以"我给它加了标签"这种话
     *       只有查注册表才算数。</li>
     *   <li><b>行为真的变了</b>：同一 tick 内对同一目标打两发同样大小的数，两发都要落下
     *       ——原版那条 {@code invulnerableTime > 10 && amount <= lastHurt} 会把第二发整个吞掉。</li>
     * </ol>
     */
    @GameTest
    public void engineDamageIgnoresTheInvulnerabilityWindow(GameTestHelper helper) {
        CardCombat.resetForTests();
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        helper.assertTrue(level.registryAccess()
                        .registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(CardDamageSource.TYPE_KEY)
                        .is(net.minecraft.tags.DamageTypeTags.BYPASSES_COOLDOWN),
                "nextcard:card_extra 必须在 bypasses_cooldown 标签里（代码造的 Holder.Direct 挂不上标签）");

        Player striker = helper.makeMockSurvivalPlayer();
        Player victim = helper.makeMockSurvivalPlayer();
        ResourceLocation card = new ResourceLocation(NextCard.MODID, "storm_pulse");
        float before = victim.getHealth();
        boolean first = victim.hurt(CardDamageSource.of(level, card, striker, "第一发"), 3.0F);
        boolean second = victim.hurt(CardDamageSource.of(level, card, striker, "第二发"), 3.0F);
        float lost = before - victim.getHealth();

        helper.assertTrue(first && second, "两发都该被认成打中：" + first + " / " + second);
        helper.assertTrue(Math.abs(lost - 6.0) < 1e-3,
                "两发都要落下（3 + 3），实际掉血 " + lost + "（第二发被无敌帧吞了就是 3）");

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
     * 接触事实在世界里成立：同一张「双牙」（背刺 +30%），正面一刀不吃、背后一刀要吃。
     *
     * <p>这条第一次有世界内证据时，它验的是"方向增伤"；2026-09-30 按《00》《02》纠正之后，
     * 几何那一格改叫<b>背刺增伤</b>（"方向增伤"归还给方向卡那一族，即火/雷/冰流派，不分角度）。
     * 卡名与站位都由手摆，所以绿红只可能来自角度换算本身
     * （{@link DamageContact#angleOffFront}）与那一道接触门。</p>
     */
    @GameTest
    public void backstabNeedsTheActualContact(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player striker = helper.makeMockSurvivalPlayer();
        grant(helper, striker, "twin_fang");
        net.minecraft.world.phys.Vec3 anchor = helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5, 1.0, 0.5));

        place(striker, anchor.add(0.0, 0.0, 2.0));
        double fromFront = dealtTo(facing(helper, anchor, 0.0F), striker, 10.0F);
        place(striker, anchor.add(0.0, 0.0, -2.0));
        double fromBehind = dealtTo(facing(helper, anchor, 0.0F), striker, 10.0F);

        helper.assertTrue(Math.abs(fromFront - 10.0) < 1e-3,
                "正面那一刀不该吃背刺加成，实际 " + fromFront + "；留痕 " + CardCombat.lastTrace());
        helper.assertTrue(Math.abs(fromBehind - 13.0) < 1e-3,
                "背后那一刀要吃基础值的 +30%，实际 " + fromBehind + "；留痕 " + CardCombat.lastTrace());
        helper.assertTrue(CardCombat.lastTrace().toString().contains("背刺 0.3")
                        && !CardCombat.lastTrace().toString().contains("未生效"),
                "背后那次的留痕要写明这一格真的加了: " + CardCombat.lastTrace());

        helper.succeed();
    }

    /**
     * manifest 里那个数真的在链路上：同一个 4 点伤害、同一个人、同一个站位，只改"挡掉多少"，
     * 落到身上的数就跟着它变。
     *
     * <p>这位<b>没有戴任何卡</b>——这正是这条断言的形状：挡掉多少是<em>全局</em>战斗口径，
     * 排在 {@code parry} 那三道闸门之前。哪天有人把 {@code setBlockedDamage} 挪回闸门后面，
     * 或者把它写成"减伤通道"（顺带把盾的磨损也缩了），这条会红。</p>
     */
    @GameTest
    public void theBlockNumberSaysHowMuchTheShieldCancels(GameTestHelper helper) {
        CardCombat.resetForTests();
        Player guard = helper.makeMockSurvivalPlayer();
        guard.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));
        net.minecraft.world.phys.Vec3 at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5, 1.0, 0.5));
        guard.setPos(at.x, at.y, at.z);
        guard.setYRot(0.0F);
        guard.yHeadRot = 0.0F;
        Player attacker = helper.makeMockSurvivalPlayer();
        attacker.setPos(at.x, at.y, at.z + 2.0);   // 正面半球，否则原版根本不发这个事件

        guard.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
        for (int i = 0; i < 6; i++) {
            guard.tick();
        }
        helper.assertTrue(guard.isBlocking(), "前提：举盾 6 tick 后原版应当认他在挡");
        helper.assertTrue(PlayerCardState.of(guard).size() == 0, "前提：这位一张卡都没有");
        helper.assertTrue(Math.abs(CardContentReload.current().manifest().combat().blockReduction() - 1.0) < 1e-9,
                "出厂 manifest 里那个数就得是 1.0——下面换的只是同一个入口读到的数");

        CardCombat.pinBlockReduction(1.0);
        double vanilla = dealtTo(guard, attacker, 4.0F);
        helper.assertTrue(Math.abs(vanilla) < 1e-3,
                "1.0 = 出厂值 = 与原版一样整个取消，实际掉血 " + vanilla);

        CardCombat.pinBlockReduction(0.5);
        double halved = dealtTo(guard, attacker, 4.0F);
        helper.assertTrue(Math.abs(halved - 2.0) < 1e-3,
                "0.5 只取消一半，剩下 2 点要照常走护甲（护甲 0），实际掉血 " + halved);

        CardCombat.pinBlockReduction(0.0);
        double nothing = dealtTo(guard, attacker, 4.0F);
        helper.assertTrue(Math.abs(nothing - 4.0) < 1e-3,
                "0 = 这盾白举，整发照算，实际掉血 " + nothing);

        helper.assertTrue(guard.getUseItem().getDamageValue() == 15,
                "盾的磨损按原版算法只看原始那一发（1+floor(4)=5，三下共 15），不跟着这个数缩水，实际 "
                        + guard.getUseItem().getDamageValue());

        helper.succeed();
    }

    /**
     * 「下一次攻击」那条一次性武装（klze 2026-09-30："给个攻击就消失的 100% 暴击率效果就行"）
     * 在世界里走完整个生命周期：挨一发 → 武装 → 下一刀必暴 → 再一刀回到没武装的数。
     *
     * <p>示例卡是「灰守」（{@code ash_guard}，{@code damage_taken} 上挂 {@code crit}）——引擎侧
     * 没有一句按卡名的特例，这条链全靠词表走。三段各钉一件事：</p>
     * <ol>
     *   <li>roll 钉在 0.5：<em>没武装时这个数绝不暴</em>（面板只有 5% 基线），武装之后同一发 roll
     *       吃到"必定那一次"。所以绿红只可能来自武装本身，不来自运气。</li>
     *   <li>用完就没了：紧跟的第二刀回到 10，证明它是"下一次"而不是"接下来所有的"。</li>
     *   <li>被盾<em>完全挡下</em>的那发不算一次攻击：那一发根本走不到结算，所以武装保留。</li>
     * </ol>
     */
    @GameTest
    public void anArmedCritBurnsOnTheNextLandedSwing(GameTestHelper helper) {
        CardCombat.resetForTests();
        CardCombat.pinCritRoll(0.5);   // 面板 5%：没武装时这个 roll 一定不暴
        Player riposte = helper.makeMockSurvivalPlayer();
        grant(helper, riposte, "ash_guard");
        net.minecraft.world.phys.Vec3 anchor = helper.absoluteVec(new net.minecraft.world.phys.Vec3(0.5, 1.0, 0.5));
        place(riposte, anchor);

        // 每个数都打在一个<em>全新的</em>靶子上：上一发已经把靶子打到 10 血的话，这一发的 13 点
        // 会被"扣到 0 就停"夹成 10，测出来的就不是结算本身（这条实测踩过一次）。
        double plain = dealtTo(helper.makeMockSurvivalPlayer(), riposte, 10.0F);
        helper.assertTrue(Math.abs(plain - 10.0) < 1e-3, "前提：没挨打就没有武装，实际 " + plain);

        dealtTo(riposte, helper.makeMockSurvivalPlayer(), 3.0F);
        double armed = dealtTo(helper.makeMockSurvivalPlayer(), riposte, 10.0F);
        helper.assertTrue(Math.abs(armed - 13.0) < 1e-3,
                "挨过一发之后的那一刀要必暴（10 × 1.3），实际 " + armed + "；留痕 " + CardCombat.lastTrace());
        helper.assertTrue(CardCombat.lastTrace().toString().contains("× 暴击 1.3"),
                "留痕要指到暴击那一格：" + CardCombat.lastTrace());

        double spent = dealtTo(helper.makeMockSurvivalPlayer(), riposte, 10.0F);
        helper.assertTrue(Math.abs(spent - 10.0) < 1e-3,
                "一次性：第二刀回到没武装的数，实际 " + spent);

        // 被盾完全挡下的那一发没进结算，所以不该把武装用掉
        dealtTo(riposte, helper.makeMockSurvivalPlayer(), 3.0F);
        Player blocker = helper.makeMockSurvivalPlayer();
        blocker.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));
        place(blocker, anchor);
        blocker.setYRot(0.0F);
        blocker.yHeadRot = 0.0F;   // 朝 +Z：那一边站的就是正面，原版只在正面取消伤害
        place(riposte, anchor.add(0.0, 0.0, 2.0));
        blocker.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
        for (int i = 0; i < 6; i++) {
            blocker.tick();
        }
        helper.assertTrue(blocker.isBlocking(), "前提：举盾 6 tick 后原版应当认他在挡");
        double blockedFlat = dealtTo(blocker, riposte, 10.0F);
        helper.assertTrue(Math.abs(blockedFlat) < 1e-3,
                "前提：这一发被整个挡下（不掉血），实际 " + blockedFlat);

        double stillArmed = dealtTo(helper.makeMockSurvivalPlayer(), riposte, 10.0F);
        helper.assertTrue(Math.abs(stillArmed - 13.0) < 1e-3,
                "完全挡下的那发没进结算，所以不该把武装用掉，实际 " + stillArmed);

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

    /**
     * 让这位玩家真的挥一刀（走原版 {@code Player#attack} 的完整链路，所以
     * {@code AttackEntityEvent}→伤害→{@code hit} 一路都会过）。返回这一刀掉的血。
     */
    private static double swing(Player striker, Player victim) {
        victim.invulnerableTime = 0;
        double before = victim.getHealth();
        striker.attack(victim);
        return before - victim.getHealth();
    }
}
