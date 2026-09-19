package com.klze.nextcard.core.load;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.card.CardIndex;
import com.klze.nextcard.core.effect.EffectClause;
import com.klze.nextcard.core.effect.EffectClauses;
import com.klze.nextcard.core.tag.TagDefinition;
import com.klze.nextcard.core.tag.TagIndex;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 纯内容读取（无 MC 依赖）：JSON 对象 → 注册表。文件 IO 归调用方（M1 的 reload 监听器 /
 * 无头测试都只递 JsonObject 进来），这里只做严格校验与组装。
 *
 * <p>id 约定：路径 {@code &lt;namespace&gt;:cards/&lt;name&gt;.json} → 卡 id
 * {@code &lt;namespace&gt;:&lt;name&gt;}——整合包可以用自己的命名空间加卡（§4.5 整合包适配）。
 * 注意：本类是引擎包，javadoc 里也不得出现具体内容 id（门 G2 连注释一起查）。</p>
 */
public final class ContentReader {

    /** 卡片 JSON 允许的键（name/texture 是内容字符串，引擎不解析，M1 起供语言/模型查找）。 */
    public static final Set<String> CARD_KEYS = Set.of(
            "tier", "card_class", "tags", "system", "requires", "effects", "name", "texture");
    /** 标签 JSON 允许的键。 */
    public static final Set<String> TAG_KEYS = Set.of("name", "color", "icon", "description");

    private ContentReader() {
    }

    /** key = 完整资源路径（如 {@code &lt;namespace&gt;:cards/&lt;name&gt;.json}）。 */
    public static LoadResult<TagIndex> readTags(Map<String, JsonObject> files) {
        List<String> errors = new ArrayList<>();
        Map<ResourceLocation, TagDefinition> tags = new TreeMap<>();
        for (Map.Entry<String, JsonObject> entry : new TreeMap<>(files).entrySet()) {
            String path = entry.getKey();
            ResourceLocation id = idOfPath(path, errors);
            if (id == null) {
                continue;
            }
            errors.addAll(prefixed(path, Strict.unknownKeys(entry.getValue(), TAG_KEYS)));
            TagDefinition.Body body = TagDefinition.Body.CODEC
                    .parse(JsonOps.INSTANCE, entry.getValue())
                    .resultOrPartial(msg -> errors.add(path + ": " + msg))
                    .orElse(null);
            if (body != null) {
                tags.put(id, TagDefinition.of(id, body));
            }
        }
        return new LoadResult<>(new TagIndex(Map.copyOf(tags)), errors);
    }

    public static LoadResult<CardIndex> readCards(Map<String, JsonObject> files, TagIndex tags) {
        List<String> errors = new ArrayList<>();
        List<CardDefinition> cards = new ArrayList<>();
        for (Map.Entry<String, JsonObject> entry : new TreeMap<>(files).entrySet()) {
            String path = entry.getKey();
            ResourceLocation id = idOfPath(path, errors);
            if (id == null) {
                continue;
            }
            JsonObject json = entry.getValue();
            errors.addAll(prefixed(path, Strict.unknownKeys(json, CARD_KEYS)));
            CardDefinition.Body body = CardDefinition.Body.CODEC
                    .parse(JsonOps.INSTANCE, json)
                    .resultOrPartial(msg -> errors.add(path + ": " + msg))
                    .orElse(null);
            if (body == null) {
                continue;
            }
            JsonArray effects = json.has("effects") && json.get("effects").isJsonArray()
                    ? json.getAsJsonArray("effects") : null;
            LoadResult<List<EffectClause>> parsedEffects = EffectClauses.parseArray(effects);
            errors.addAll(prefixed(path, parsedEffects.errors()));
            cards.add(CardDefinition.of(id, body, parsedEffects.value()));
        }
        LoadResult<CardIndex> index = CardIndex.build(cards, tags);
        List<String> all = new ArrayList<>(errors);
        all.addAll(index.errors());
        return new LoadResult<>(index.value(), all);
    }

    private static ResourceLocation idOfPath(String path, List<String> errors) {
        int colon = path.indexOf(':');
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (colon < 0 || slash < colon || dot < slash || !path.endsWith(".json")) {
            errors.add("malformed content path: " + path);
            return null;
        }
        String name = path.substring(slash + 1, dot);
        return new ResourceLocation(path.substring(0, colon), name);
    }

    private static List<String> prefixed(String path, List<String> errors) {
        List<String> out = new ArrayList<>();
        for (String error : errors) {
            out.add(path + ": " + error);
        }
        return out;
    }
}
