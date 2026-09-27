package com.klze.nextcard.core.effect;

/**
 * 管线步骤的最小契约：两条管线（{@link AttackPipeline.Step 攻方} / {@link DefencePipeline.Step 守方}）
 * 各自的枚举都实现它，于是留痕 {@link StepTrace} 只需要一份。
 *
 * <p>顺序一律由 {@code ordinal()} 说了算——内容没有"优先级"这个旋钮（照 StS 先例，
 * 260 个 Power 实现里 priority 零命中）。</p>
 */
public interface PipelineStep {

    /** 中文名（留痕与日志用；判据按 {@link Enum#ordinal()}，不按它）。 */
    String display();
}
