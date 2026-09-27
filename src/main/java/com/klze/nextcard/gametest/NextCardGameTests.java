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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
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
