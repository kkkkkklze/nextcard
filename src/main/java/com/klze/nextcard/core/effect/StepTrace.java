package com.klze.nextcard.core.effect;

import java.util.Locale;

/**
 * 一步的留痕：这一步之前 / 之后 / 为什么。两条管线共用。
 *
 * <p>为什么要留：内容作者问"这张卡为什么只打出这点伤害 / 这口伤害为什么没掉血"，
 * 回答必须能指到具体某一步，而不是让人反推公式。</p>
 */
public record StepTrace(PipelineStep step, double before, double after, String note) {

    public boolean changed() {
        return before != after;
    }

    @Override
    public String toString() {
        return step.display() + " " + formatted(before) + " → " + formatted(after)
                + (note.isEmpty() ? "" : "（" + note + "）");
    }

    public static String formatted(double value) {
        return String.format(Locale.ROOT, "%.4g", value);
    }
}
