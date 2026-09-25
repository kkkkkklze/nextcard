package com.klze.nextcard.logic;

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
    }
}
