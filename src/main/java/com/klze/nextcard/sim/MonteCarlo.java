package com.klze.nextcard.sim;

import com.klze.nextcard.core.card.CardDefinition;
import com.klze.nextcard.core.card.CardIndex;
import com.klze.nextcard.core.draw.DrawEngine;
import com.klze.nextcard.core.draw.DrawProfile;
import com.klze.nextcard.core.draw.DrawResult;
import com.klze.nextcard.core.draw.DrawSchedule;
import com.klze.nextcard.core.pool.PoolIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 蒙特卡洛模拟（门 G3 的被测物）：N 个玩家各完整走 15 抽，输出各等级出现分布与标签支占比，
 * 并断言性质——T5 不早于第 9 抽（第一批裁定 1）、标签支占比 &lt; 80%（渐近上限）、
 * 任意 15 抽路径不撞「卡池无卡」死局（个人池耗尽时归一化仍能给出结果）。
 *
 * <p>概率手感全部在这里调参，不进游戏调概率（v1.0 §4.3 铁律 3）。</p>
 */
public final class MonteCarlo {

    private static final int DRAWS = 15;
    private static final int T5_ENTRY_DRAW = 9;

    public record Report(int runs, int t5BeforeEntryViolations, int deadEndRuns,
                         long tagBranchSlots, long totalSlots, Map<Integer, Map<Integer, Long>> tierHistogram,
                         List<String> violationDetails) {
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260920L;
        int runs = args.length > 1 ? Integer.parseInt(args[1]) : 10000;
        Report report = run(seed, runs);
        print(report);

        List<String> violations = new java.util.ArrayList<>();
        if (report.t5BeforeEntryViolations() > 0) {
            violations.add("T5 appeared before draw " + T5_ENTRY_DRAW);
        }
        if (report.deadEndRuns() > 0) {
            violations.add("player hit 卡池无卡 mid-run: " + report.deadEndRuns() + " runs");
        }
        double tagShare = report.totalSlots() == 0 ? 0 : (double) report.tagBranchSlots() / report.totalSlots();
        if (tagShare >= WeightCap.CAP) {
            violations.add("tag branch share " + tagShare + " >= cap");
        }
        if (!violations.isEmpty()) {
            System.out.println("SIM RESULT: FAIL");
            violations.forEach(v -> System.out.println("SIM violation: " + v));
            System.exit(1);
        }
        System.out.println("SIM RESULT: PASS");
    }

    public static Report run(long seed, int runs) {
        Scenario scenario = Scenario.load();
        CardIndex index = scenario.cards();
        PoolIndex pools = scenario.pools();
        DrawSchedule schedule = scenario.schedule();

        Map<Integer, Map<Integer, Long>> tierHistogram = new HashMap<>();
        long tagBranchSlots = 0;
        long totalSlots = 0;
        int t5Violations = 0;
        int deadEnds = 0;
        List<String> violationDetails = new java.util.ArrayList<>();

        for (int run = 0; run < runs; run++) {
            Random random = new Random(seed + run);
            Set<ResourceLocation> owned = new HashSet<>();
            for (int drawNumber = 1; drawNumber <= DRAWS; drawNumber++) {
                DrawSchedule.Row row = schedule.forDraw(drawNumber).orElse(null);
                if (row == null) {
                    deadEnds++;
                    break;
                }
                DrawProfile profile = scenario.profiles().get(row.profile());
                DrawResult result = DrawEngine.draw(index, pools, profile, row.tierWeights(), owned, random);
                if (result.isEmpty()) {
                    deadEnds++;
                    break;
                }
                // 模拟玩家在候选里随机挑一张（PICK_ONE 语义的最朴素玩家模型）。
                ResourceLocation picked = result.offers().get(random.nextInt(result.offers().size()));
                owned.add(picked);
                CardDefinition card = index.byId().get(picked);
                tierHistogram.computeIfAbsent(drawNumber, k -> new HashMap<>())
                        .merge(card.tier(), 1L, Long::sum);
                for (String line : result.log()) {
                    if (line.contains("branch=tag")) {
                        tagBranchSlots++;
                    }
                    if (line.contains("branch=")) {
                        totalSlots++;
                    }
                }
                if (card.tier() == 5 && drawNumber < T5_ENTRY_DRAW) {
                    t5Violations++;
                    if (violationDetails.size() < 3) {
                        violationDetails.add("run=" + run + " draw=" + drawNumber + " card=" + picked
                                + " log=" + result.log());
                    }
                }
            }
        }
        return new Report(runs, t5Violations, deadEnds, tagBranchSlots, totalSlots, tierHistogram, violationDetails);
    }

    private static void print(Report report) {
        System.out.println("=== MonteCarlo: " + report.runs() + " runs x " + DRAWS + " draws ===");
        report.tierHistogram().keySet().stream().sorted().forEach(drawNumber -> {
            Map<Integer, Long> tiers = report.tierHistogram().get(drawNumber);
            StringBuilder line = new StringBuilder("draw " + String.format("%2d", drawNumber) + ": ");
            tiers.keySet().stream().sorted().forEach(tier ->
                    line.append("T").append(tier).append("=").append(tiers.get(tier)).append(" "));
            System.out.println(line);
        });
        long slotTotal = report.totalSlots();
        double share = slotTotal == 0 ? 0 : (double) report.tagBranchSlots() / slotTotal;
        System.out.println(String.format("tag-branch slots: %d / %d = %.4f (cap %.2f)",
                report.tagBranchSlots(), slotTotal, share, WeightCap.CAP));
        System.out.println("T5-before-entry violations: " + report.t5BeforeEntryViolations()
                + "  dead-end runs: " + report.deadEndRuns());
        report.violationDetails().forEach(v -> System.out.println("  violation: " + v));
    }

    /** 让 CAP 只有一份权威定义（避免复制 WeightModel.CAP 的字面量）。 */
    private static final class WeightCap {
        static final double CAP = com.klze.nextcard.core.draw.WeightModel.CAP;
    }
}
