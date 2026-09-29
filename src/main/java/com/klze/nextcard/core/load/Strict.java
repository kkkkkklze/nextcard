package com.klze.nextcard.core.load;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 未知键检查（§6 原则：「字段全部可校验，未知字段报错」）。
 *
 * <p>这也是 DFU {@code optionalFieldOf} 吞错误陷阱的正面防线：optional 字段里写错<b>键名</b>
 * 会被这里拦下，而不是变成「字段不存在」静默通过。</p>
 *
 * <p><b>值层面的静默它拦不住，所以还有第二道</b>：{@code OptionalFieldCodec#decode}（DFU 6.0.8
 * 源码第 30~35 行）在子 codec 解析失败时返回的是 {@code success(Optional.empty())}——
 * 越界值、类型写错的值都变成"这个键没写过"，接着落到 {@code optionalFieldOf} 的默认值上。
 * {@link #valuesMustParse} 就是把 codec 之外这一层补回来：<em>写了的键必须解得过</em>。</p>
 */
public final class Strict {

    private Strict() {
    }

    public static List<String> unknownKeys(JsonObject json, Set<String> allowed) {
        List<String> unknown = new ArrayList<>();
        for (String key : json.keySet()) {
            if (!allowed.contains(key)) {
                unknown.add(key);
            }
        }
        return unknown;
    }

    /**
     * 同上，但每条报错带上上下文——效果子句的键名如果只报一个孤立单词（{@code meters}），
     * 内容作者定位不到是哪个子句写错了。
     */
    public static List<String> unknownKeys(JsonObject json, Set<String> allowed, String context) {
        List<String> unknown = new ArrayList<>();
        for (String key : json.keySet()) {
            if (!allowed.contains(key)) {
                unknown.add(context + ": unknown key " + key);
            }
        }
        return unknown;
    }

    /** 某一段里的未知键（{@code path} 用点分，空串 = 整份的根）。那一段没写不算错。 */
    public static List<String> unknownKeysAt(JsonElement root, String path, Set<String> allowed) {
        Optional<JsonObject> section = section(root, path);
        if (section.isEmpty()) {
            return List.of();
        }
        return unknownKeys(section.get(), allowed, path.isEmpty() ? "(root)" : path);
    }

    /**
     * 写了的键必须值合法。{@code fields} 的键是点分路径，值<em>就是 codec 里用的那一个</em>——
     * 再声明一份区间就是第二个真相。
     */
    public static List<String> valuesMustParse(JsonElement root, Map<String, Codec<?>> fields) {
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, Codec<?>> field : fields.entrySet()) {
            String path = field.getKey();
            int lastDot = path.lastIndexOf('.');
            Optional<JsonObject> parent = section(root, path.substring(0, lastDot));
            if (parent.isEmpty()) {
                continue;
            }
            JsonElement leaf = parent.get().get(path.substring(lastDot + 1));
            if (leaf == null) {
                continue;                       // 缺席是合法的：它走默认值，并且默认值在表里写着
            }
            field.getValue().parse(JsonOps.INSTANCE, leaf).error()
                    .ifPresent(err -> errors.add(path + " 写了就必须合法（否则它等于没写）: " + err.message()));
        }
        return errors;
    }

    /** 沿点分路径找到那一段对象。任何一段不存在、或途中不是对象 → 空。 */
    private static Optional<JsonObject> section(JsonElement root, String path) {
        JsonElement node = root;
        for (String segment : path.isEmpty() ? new String[0] : path.split("\\.")) {
            if (!(node instanceof JsonObject object) || !object.has(segment)) {
                return Optional.empty();
            }
            node = object.get(segment);
        }
        return node instanceof JsonObject object ? Optional.of(object) : Optional.empty();
    }
}
