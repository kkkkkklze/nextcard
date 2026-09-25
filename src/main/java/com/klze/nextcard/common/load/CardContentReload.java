package com.klze.nextcard.common.load;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klze.nextcard.NextCard;
import com.klze.nextcard.core.card.CardIndex;
import com.klze.nextcard.core.load.ContentCatalog;
import com.klze.nextcard.core.load.ContentReader;
import com.klze.nextcard.core.load.LoadResult;
import com.klze.nextcard.core.tag.TagIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 卡牌内容热重载（M1 第一片）。
 *
 * <p><b>为什么不用 {@code SimpleJsonResourceReloadListener}</b>：它的 {@code scanDirectory} 对
 * {@code IllegalArgumentException | IOException | JsonParseException} 只是
 * {@code LOGGER.error} 后跳过这张卡——写坏一个 json 会变成"这张卡不见了"，而整合包作者不会察觉；
 * 而它用 {@code Map.put} 装表，重复 id 直接抛 {@code IllegalStateException} 掀掉整次 reload。
 * 两者都是我们不能接受的行为（内容错误的容错口径见规格 §4.5：语法坏 → 明确报错并整次不换表）。</p>
 *
 * <p>所以这里走 {@code SimplePreparableReloadListener}：自己列文件、逐文件解析并把错误收集成表，
 * {@code apply} 阶段<b>只在零错误时换表</b>，否则保留上一版并打印全部错误。</p>
 */
public final class CardContentReload extends SimplePreparableReloadListener<CardContentReload.Result> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CardContentReload.class);

    public static final String CARD_DIR = "cards";
    public static final String TAG_DIR = "card_tags";

    private static final CardContentReload INSTANCE = new CardContentReload();
    private static volatile ContentBundle current = ContentBundle.EMPTY;

    private CardContentReload() {
    }

    /** 挂到游戏事件总线（在 {@code NextCard} 构造里调一次）。 */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(new Subscriber());
    }

    /** 当前生效的内容；未加载或整次失败时为 {@link ContentBundle#EMPTY}。 */
    public static ContentBundle current() {
        return current;
    }

    public static final class Subscriber {

        @SubscribeEvent
        public void onAddReloadListeners(AddReloadListenerEvent event) {
            event.addListener(INSTANCE);
        }
    }

    /** prepare 与 apply 之间传递的结果（错误表必须一路带到 apply，否则换表决策就丢了依据）。 */
    public record Result(ContentBundle bundle, List<String> errors, List<String> parseFailures) {
    }

    @Override
    protected CardContentReload.Result prepare(ResourceManager manager, ProfilerFiller profiler) {
        List<String> parseFailures = new ArrayList<>();
        List<ContentCatalog.Entry> tagEntries = collect(manager, TAG_DIR, parseFailures);
        List<ContentCatalog.Entry> cardEntries = collect(manager, CARD_DIR, parseFailures);

        List<String> errors = new ArrayList<>(parseFailures);
        LoadResult<Map<String, JsonObject>> tagFiles = ContentCatalog.collect(tagEntries);
        errors.addAll(tagFiles.errors());
        LoadResult<Map<String, JsonObject>> cardFiles = ContentCatalog.collect(cardEntries);
        errors.addAll(cardFiles.errors());
        if (!errors.isEmpty()) {
            return new Result(ContentBundle.EMPTY, errors, parseFailures);
        }

        LoadResult<TagIndex> tags = ContentReader.readTags(tagFiles.value());
        errors.addAll(tags.errors());
        if (!errors.isEmpty()) {
            return new Result(ContentBundle.EMPTY, errors, parseFailures);
        }
        LoadResult<CardIndex> cards = ContentReader.readCards(cardFiles.value(), tags.value());
        errors.addAll(cards.errors());
        if (!errors.isEmpty()) {
            return new Result(ContentBundle.EMPTY, errors, parseFailures);
        }
        return new Result(new ContentBundle(tags.value(), cards.value(),
                tagFiles.value().size(), cardFiles.value().size()), List.of(), parseFailures);
    }

    @Override
    protected void apply(CardContentReload.Result result, ResourceManager manager, ProfilerFiller profiler) {
        if (!result.errors().isEmpty()) {
            LOGGER.error("[nextcard] 内容加载失败，保留上一版（{} 张卡 / {} 个标签）。共 {} 条错误：",
                    current().cardFiles(), current().tagFiles(), result.errors().size());
            for (String error : result.errors()) {
                LOGGER.error("  {}", error);
            }
            return;
        }
        current = result.bundle();
        LOGGER.info("[nextcard] 内容已加载：{} 张卡 / {} 个标签", result.bundle().cardFiles(),
                result.bundle().tagFiles());
    }

    private static List<ContentCatalog.Entry> collect(ResourceManager manager, String dir, List<String> failures) {
        List<ContentCatalog.Entry> entries = new ArrayList<>();
        Map<ResourceLocation, Resource> found = manager.listResources(dir, path -> path.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
            String key = ContentCatalog.keyFromPath(NextCard.MODID, dir, entry.getKey().getPath());
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    failures.add(key + ": content file must hold a JSON object");
                    continue;
                }
                entries.add(new ContentCatalog.Entry(key, parsed.getAsJsonObject()));
            } catch (Exception parseFailure) {
                // 单个文件解析坏 → 记成错误（apply 阶段据此整次不换表），而不是静默少一张卡
                failures.add(key + ": unreadable (" + parseFailure.getClass().getSimpleName() + ": "
                        + parseFailure.getMessage() + ")");
            }
        }
        return entries;
    }
}
