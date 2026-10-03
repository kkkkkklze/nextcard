package com.klze.nextcard.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.card.CardIndex;
import com.klze.nextcard.core.draw.CardClass;
import com.klze.nextcard.core.draw.DrawProfile;
import com.klze.nextcard.core.draw.DrawSchedule;
import com.klze.nextcard.core.load.ContentReader;
import com.klze.nextcard.core.load.LoadResult;
import com.klze.nextcard.core.load.Manifest;
import com.klze.nextcard.sim.Scenario;
import com.klze.nextcard.core.tag.TagIndex;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容一致性门：data/nextcard 的真实 JSON 必须通过严格校验，且与 {@link Scenario}
 * 的参考场景同一身份（同一批卡、同一张日程）。示例内容改坏时，这里在无头环境先红。
 */
public class ContentParityTest {

    private static Path contentDir() {
        String value = System.getProperty("nextcard.content.dir");
        assertTrue(value != null, "nextcard.content.dir must be provided by the logicTest or test task");
        return Path.of(value);
    }

    private static Map<String, com.google.gson.JsonElement> readAll(Path dir) throws IOException {
        Map<String, com.google.gson.JsonElement> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        String relative = dir.relativize(p).toString().replace('\\', '/');
                        try {
                            files.put("nextcard:" + relative,
                                    JsonParser.parseReader(Files.newBufferedReader(p)));
                        } catch (Exception e) {
                            throw new IllegalStateException("cannot read " + p, e);
                        }
                    });
        }
        return files;
    }

    @Test
    public void exampleContentLoadsCleanAndMatchesScenario() throws IOException {
        Map<String, com.google.gson.JsonElement> files = readAll(contentDir());

        Map<String, JsonObject> tagFiles = new LinkedHashMap<>();
        Map<String, JsonObject> cardFiles = new LinkedHashMap<>();
        Map<String, JsonObject> profileFiles = new LinkedHashMap<>();
        files.forEach((path, element) -> {
            if (path.contains(":card_tags/")) tagFiles.put(path, element.getAsJsonObject());
            else if (path.contains(":cards/")) cardFiles.put(path, element.getAsJsonObject());
            else if (path.contains(":draw_profiles/")) profileFiles.put(path, element.getAsJsonObject());
        });

        LoadResult<TagIndex> tags = ContentReader.readTags(tagFiles);
        assertTrue(tags.ok(), "tag errors: " + tags.errors());
        LoadResult<CardIndex> cards = ContentReader.readCards(cardFiles, tags.value());
        assertTrue(cards.ok(), "card errors: " + cards.errors());

        assertEquals(Scenario.load().cards().byId().keySet(), cards.value().byId().keySet(),
                "JSON cards and Scenario cards must be the same identity set");

        CardDefinition venomEdge = cards.value().byId().get(Scenario.VENOM_EDGE);
        assertEquals(3, venomEdge.tier());
        assertEquals(CardClass.A, venomEdge.cardClass());
        assertEquals(Scenario.SYSTEM_POISON, venomEdge.system().orElseThrow());

        CardDefinition venomCascade = cards.value().byId().get(Scenario.VENOM_CASCADE);
        assertEquals(5, venomCascade.tier());
        assertEquals(CardClass.C, venomCascade.cardClass());
        assertEquals(List.of(Scenario.SYSTEM_POISON), venomCascade.requires());

        DrawProfile first = DrawProfile.CODEC.parse(JsonOps.INSTANCE, files.get("nextcard:draw_profiles/first.json"))
                .resultOrPartial(msg -> {
                    throw new AssertionError("first profile: " + msg);
                }).orElseThrow();
        assertEquals(Scenario.STARTERS, first.fixedCards().orElseThrow());
        assertEquals(DrawProfile.Grant.PICK_ONE, first.grant());

        DrawProfile standard = DrawProfile.CODEC.parse(JsonOps.INSTANCE, files.get("nextcard:draw_profiles/standard.json"))
                .resultOrPartial(msg -> {
                    throw new AssertionError("standard profile: " + msg);
                }).orElseThrow();
        assertEquals(5, standard.slots().size());
        assertEquals(CardClass.A, standard.slots().get(0).filter().cardClass().orElseThrow());
        assertTrue(standard.slots().stream().skip(1).allMatch(s -> s.filter().cardClass().isEmpty()),
                "slots 2-5 must be unfiltered (不分AB类)");

        DrawSchedule schedule = DrawSchedule.CODEC.parse(JsonOps.INSTANCE, files.get("nextcard:draw_schedule.json"))
                .resultOrPartial(msg -> {
                    throw new AssertionError("schedule: " + msg);
                }).orElseThrow();
        Scenario scenario = Scenario.load();
        for (int draw = 1; draw <= 15; draw++) {
            DrawSchedule.Row expected = scenario.schedule().forDraw(draw).orElseThrow();
            DrawSchedule.Row actual = schedule.forDraw(draw).orElseThrow();
            assertEquals(expected.profile(), actual.profile(), "draw " + draw);
            assertEquals(expected.tierWeights(), actual.tierWeights(), "draw " + draw);
        }
        assertTrue(schedule.forDraw(16).isEmpty(), "15 次上限 = 日程只覆盖 1..15");

        Manifest manifest = Manifest.CODEC.parse(JsonOps.INSTANCE, files.get("nextcard:manifest.json"))
                .resultOrPartial(msg -> {
                    throw new AssertionError("manifest: " + msg);
                }).orElseThrow();
        assertEquals(1, manifest.removeItem().refundDraws());
        assertTrue(manifest.removeItem().consumeSurvival());
        assertEquals(1.0, manifest.combat().blockReduction(), 1e-9,
                "格挡减伤的出厂值必须是 1.0（= 与原版一样整个取消）：这个数是给内容侧在游戏里改 JSON"
                        + "试手感用的，出厂就改手感等于替他们做决定");
        assertTrue(Manifest.validate(files.get("nextcard:manifest.json")).isEmpty(),
                "发出去的这份 manifest 自己得先过 strict 那道闸门");
    }

    /**
     * 手感数越界必须是错误，不能"夹一下"，也不能"当它没写"。
     *
     * <p>{@code block_reduction} 是"挡掉的比例"，0~1 之外没有意义，写成 1.5 或 -0.2 一定是笔误；
     * 带引号的 {@code "0.5"} 是手改 JSON 最常见的那种错。<b>codec 自己拦不住</b>——
     * {@code OptionalFieldCodec#decode}（DFU 6.0.8）在子 codec 失败时返回的是
     * {@code success(Optional.empty())}，越界值与"没写这一行"是同一件事，接着落到默认值上。
     * 所以下面先用一条断言把"codec 确实静默放行"钉成事实（它哪天不静默了这条会红，那是好事），
     * 再要求 {@link Manifest#validate} 必须报出来。真正的闸门在 validate 这一道。</p>
     */
    @Test
    public void combatTuningNumbersRejectValuesOutOfRange() {
        for (String bad : new String[]{"1.5", "-0.2", "\"0.5\""}) {
            JsonElement json = JsonParser.parseString("{\"combat\": {\"block_reduction\": " + bad + "}}");
            assertTrue(Manifest.CODEC.parse(JsonOps.INSTANCE, json).result().isPresent(),
                    "前提：codec 对 " + bad + " 是静默的（strict 那一道存在的理由就在这里）");
            List<String> errors = Manifest.validate(json);
            assertEquals(1, errors.size(), "越界的 block_reduction 必须正好报一条错，实际 " + errors);
            assertTrue(errors.get(0).contains("block_reduction"),
                    "报错要点名是哪个键: " + errors);
        }

        // 键名写错也等于"没写"，同一道闸门要一起拦
        List<String> typo = Manifest.validate(JsonParser.parseString(
                "{\"combat\": {\"block_reducton\": 0.2}}"));
        assertEquals(1, typo.size(), "写错的键名必须报错，实际 " + typo);
        assertTrue(typo.get(0).contains("block_reducton"), "要报出那个写错的键名: " + typo);

        // 同一个闸门也管另外两段（它们带的是同一个静默）
        assertEquals(1, Manifest.validate(JsonParser.parseString(
                "{\"remove_item\": {\"refund_draws\": 99}}")).size(), "refund_draws 越界同样要报错");

        assertTrue(Manifest.validate(JsonParser.parseString(
                "{\"combat\": {\"block_reduction\": 0.2}}")).isEmpty(), "范围内的值不该报错");
        assertTrue(Manifest.validate(JsonParser.parseString("{}")).isEmpty(),
                "整段缺省是合法的：缺省走默认值，不是错误");

        assertEquals(0.2, Manifest.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"combat\": {\"block_reduction\": 0.2}}")).result().orElseThrow()
                .combat().blockReduction(), 1e-9, "范围内的值要照收（内容侧要能改）");
        assertEquals(1.0, Manifest.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}"))
                .result().orElseThrow().combat().blockReduction(), 1e-9,
                "整段缺省时回到 1.0（缺省 = 不改手感，不是悄悄改成别的数）");
    }

    /**
     * 铁律一（删光 {@code data/nextcard/} 引擎照跑）也会被 manifest 这条破掉：上一批把
     * <em>这份文件不存在</em>一律判成错误，于是空内容包每次 reload 都报一条——那正是
     * "内容反过来约束结构"的形状。缺席与写坏必须分开判，三种情形各钉一条。
     */
    @Test
    public void anAbsentManifestIsOnlyAnErrorWhenThereIsContent() {
        LoadResult<Manifest> emptyPack = Manifest.read(null, 0);
        assertTrue(emptyPack.ok(), "整包内容都没了时缺席是合法状态：" + emptyPack.errors());
        assertEquals(1.0, emptyPack.value().combat().blockReduction(), 1e-9,
                "这时用的就是出厂那个 1.0（= 与原版一致）");

        LoadResult<Manifest> cardsWithoutManifest = Manifest.read(null, 3);
        assertEquals(1, cardsWithoutManifest.errors().size(), "有卡而没这份总表 = 打包漏了，必须报错");
        assertTrue(cardsWithoutManifest.errors().get(0).contains("manifest.json"),
                "报错要点名是哪份文件：" + cardsWithoutManifest.errors());

        LoadResult<Manifest> broken = Manifest.read(JsonParser.parseString(
                "{\"combat\": {\"block_reduction\": 1.5}}"), 3);
        assertEquals(1, broken.errors().size(), "写坏了永远是错误，不许当成没写：" + broken.errors());

        LoadResult<Manifest> fine = Manifest.read(JsonParser.parseString(
                "{\"combat\": {\"block_reduction\": 0.2}}"), 3);
        assertTrue(fine.ok(), "合法内容不该报错：" + fine.errors());
        assertEquals(0.2, fine.value().combat().blockReduction(), 1e-9);
    }
}
