package com.klze.nextcard.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门 G2（无特例，v1.0 §4.3）：core/ 引擎包里不得出现任何具体内容标识——
 * 卡 id / 标签 / 体系 / 卡名语言键。引擎对内容零知识由源码扫描机械证明；
 * 新内容自动进入被查清单，不用记得更新这个测试。
 */
public class ContentLiteralAuditTest {

    private static Path coreSrc() {
        String value = System.getProperty("nextcard.core.src");
        assertTrue(value != null, "nextcard.core.src must be provided by the logicTest or test task");
        return Path.of(value);
    }

    private static Path contentDir() {
        String value = System.getProperty("nextcard.content.dir");
        assertTrue(value != null, "nextcard.content.dir must be provided by the logicTest or test task");
        return Path.of(value);
    }

    @Test
    public void engineCoreContainsNoContentLiterals() throws IOException {
        Set<String> tokens = contentTokens(contentDir());
        assertTrue(!tokens.isEmpty(), "content scan must find tokens (empty = wrong dir?)");

        Set<String> violations = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(coreSrc())) {
            walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).forEach(source -> {
                String text;
                try {
                    text = Files.readString(source);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                for (String token : tokens) {
                    if (text.contains(token)) {
                        violations.add(source.getFileName() + " contains \"" + token + "\"");
                    }
                }
            });
        }
        assertTrue(violations.isEmpty(), "core/ must be content-free, found:\n  " + String.join("\n  ", violations));
    }

    /** 从内容 JSON 抽取所有身份标识：完整 id + 卡/标签/体系的路径末段。 */
    private static Set<String> contentTokens(Path dir) throws IOException {
        Set<String> tokens = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        String relative = dir.relativize(p).toString().replace('\\', '/');
                        String stem = Path.of(relative).getFileName().toString().replace(".json", "");
                        if (relative.startsWith("cards/")) {
                            tokens.add("nextcard:" + stem);
                            tokens.add(stem);
                        } else if (relative.startsWith("card_tags/")) {
                            tokens.add("nextcard:" + stem);
                        }
                        try {
                            JsonElement parsed = com.google.gson.JsonParser
                                    .parseReader(Files.newBufferedReader(p));
                            if (parsed.isJsonArray()) {
                                for (JsonElement element : parsed.getAsJsonArray()) {
                                    if (element.isJsonObject()) {
                                        collect(element.getAsJsonObject(), tokens);
                                    }
                                }
                            } else if (parsed.isJsonObject()) {
                                collect(parsed.getAsJsonObject(), tokens);
                            }
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }
        return tokens;
    }

    private static void collect(JsonObject json, Set<String> tokens) {
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String s = value.getAsString();
                if (s.contains(":") || s.startsWith("card.") || s.startsWith("tag.")) {
                    tokens.add(s);
                }
            } else if (value.isJsonArray()) {
                for (JsonElement element : value.getAsJsonArray()) {
                    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                            && element.getAsString().contains(":")) {
                        tokens.add(element.getAsString());
                    }
                }
            } else if (value.isJsonObject()) {
                collect(value.getAsJsonObject(), tokens);
            }
        }
    }
}
