package com.klze.nextcard.core.load;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 未知键检查（§6 原则：「字段全部可校验，未知字段报错」）。
 *
 * <p>这也是 DFU {@code optionalFieldOf} 吞错误陷阱的正面防线：optional 字段里写错<b>键名</b>
 * 会被这里拦下，而不是变成「字段不存在」静默通过。字段值层面的约束仍由各 Codec 显式校验。</p>
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
}
