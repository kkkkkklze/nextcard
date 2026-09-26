package com.klze.nextcard.gametest;

import com.klze.nextcard.NextCard;
import com.klze.nextcard.common.load.CardContentReload;
import com.klze.nextcard.common.player.PlayerCardState;
import com.klze.nextcard.core.player.CardLedger;
import com.klze.nextcard.common.load.ContentBundle;
import com.klze.nextcard.common.registry.ModBlocks;
import com.klze.nextcard.common.registry.ModCreativeTabs;
import com.klze.nextcard.common.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;
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
}
