package com.klze.nextcard.logic;

import com.google.gson.JsonObject;
import com.klze.nextcard.core.load.ContentCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 内容清单收口（{@code core/load/ContentCatalog}）：id 撞车必须报错，不许后写覆盖先写。 */
public class ContentCatalogTest {

    @Test
    public void keysKeepNamespaceAndDirSoTheReaderCanValidateThem() {
        assertEquals("nextcard:cards/iron_wall_grip.json",
                ContentCatalog.keyOf("nextcard", "cards", "iron_wall_grip"));
        assertEquals("nextcard:card_tags/attack.json",
                ContentCatalog.keyOf("nextcard", "card_tags", "attack"));
    }

    /** 两个目录里同名文件算出同一个 id —— 这必须是一次加载失败，而不是"谁后读到谁赢"。 */
    @Test
    public void sameNameInDifferentDirsIsADuplicateId() {
        List<ContentCatalog.Entry> entries = List.of(
                new ContentCatalog.Entry(ContentCatalog.keyOf("nextcard", "cards", "iron_wall_grip"), new JsonObject()),
                new ContentCatalog.Entry(ContentCatalog.keyOf("nextcard", "card_tags", "attack"), new JsonObject()));
        var clean = ContentCatalog.collect(entries);
        assertTrue(clean.errors().isEmpty(), "不该误报: " + clean.errors());
        assertEquals(2, clean.value().size());

        List<ContentCatalog.Entry> clash = List.of(
                new ContentCatalog.Entry("nextcard:cards/multistrike/wild_swing.json", new JsonObject()),
                new ContentCatalog.Entry("nextcard:cards/frenzy/wild_swing.json", new JsonObject()));
        var result = ContentCatalog.collect(clash);
        assertEquals(1, result.errors().size(), "撞车只报一条且点名两份文件: " + result.errors());
        assertTrue(result.errors().get(0).contains("duplicate content id"), result.errors().toString());
        assertTrue(result.errors().get(0).contains("multistrike") && result.errors().get(0).contains("frenzy"),
                "报错必须能定位到两个来源: " + result.errors());
        assertTrue(result.value().isEmpty(), "有撞车时不给半成品清单");
    }

    /**
     * 1.20.1 的 {@code ResourceLocation} 只收 {@code [a-z0-9._-]}：中文文件名会抛异常。
     * 这里必须把它收成一条加载错误——内容侧的 txt 卡名是中文的，转换器迟早会撞上来，
     * 不能让一个坏文件名把整次 reload 炸掉。
     */
    @Test
    public void nonAsciiFileNameBecomesAnErrorNotACrash() {
        var result = ContentCatalog.collect(List.of(
                new ContentCatalog.Entry("nextcard:cards/乱击.json", new JsonObject())));
        assertEquals(1, result.errors().size(), "要报错而不是抛异常");
        assertTrue(result.errors().get(0).contains("invalid content id"), result.errors().toString());
        assertTrue(result.value().isEmpty());
    }

    /** 路径写坏（缺命名空间 / 缺 .json / 以冒号结尾）一律拦下，不静默少一张卡。 */
    @Test
    public void malformedKeysAreRejected() {
        List<String> bad = List.of(
                "cards/no_namespace.json",
                "nextcard:cards/no_ext",
                "nextcard:cards/dir-with-colon:");
        for (String key : bad) {
            var result = ContentCatalog.collect(List.of(new ContentCatalog.Entry(key, new JsonObject())));
            assertEquals(1, result.errors().size(), key + " 必须报错");
            assertTrue(result.errors().get(0).contains("malformed content path"),
                    key + " → " + result.errors());
            assertTrue(result.value().isEmpty(), key + " 不该留下半成品");
        }
    }
}
