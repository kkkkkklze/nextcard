package com.klze.nextcard.core.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.klze.nextcard.core.load.LoadResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 效果子句词表。注册表按 {@code type} 字符串分派；M0 刻意为空——四原语在 M2 落地、
 * 机制挂点词表随首板卡表冻结（C3）。空词表 + 非空 effects 数组 = 加载错误，绝不静默。
 */
public final class EffectClauses {

    /** type → 解析器。M0 为空是设计立场，不是未完成状态。 */
    private static final Map<String, Parser> REGISTRY = Map.of();

    @FunctionalInterface
    public interface Parser {
        EffectClause parse(JsonObject body, List<String> errors);
    }

    private EffectClauses() {
    }

    /** 解析卡片 JSON 的 effects 数组；缺省（键不存在）= 空表，合法。 */
    public static LoadResult<List<EffectClause>> parseArray(JsonArray array) {
        List<String> errors = new ArrayList<>();
        List<EffectClause> clauses = new ArrayList<>();
        if (array != null) {
            for (JsonElement element : array) {
                if (!(element instanceof JsonObject obj) || !obj.has("type")) {
                    errors.add("effect clause must be an object with a \"type\" field");
                    continue;
                }
                String type = obj.get("type").getAsString();
                Parser parser = REGISTRY.get(type);
                if (parser == null) {
                    errors.add("unknown effect type: " + type);
                    continue;
                }
                clauses.add(parser.parse(obj, errors));
            }
        }
        return new LoadResult<>(List.copyOf(clauses), errors);
    }
}
