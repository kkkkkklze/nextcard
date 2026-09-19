package com.klze.nextcard.core.draw;

/**
 * 标签权重模型（v1.0 §5.3，已裁定：渐近曲线，第三批裁定 6）。
 *
 * <p>标签总份额 S = cap × ΣW / (ΣW + W0)。性质（门 G3 断言）：
 * {@code 0 ≤ S < cap}（渐近，永不到顶，「到 80% 后按数量权重再分配」由
 * 「标签之间始终按 W_t/ΣW 分配」天然涵盖）；空卡集 ⇒ S=0；S 随拥有标签卡数单调不减。</p>
 *
 * <p>参考手感（W0=8）：ΣW=5 → S≈31%；ΣW=10 → 44%；ΣW=20 → 57%；ΣW=40 → 67%。</p>
 */
public final class WeightModel {

    /** 标签份额上限（第一批裁定：上限 80%，20% 永远留给纯随机）。 */
    public static final double CAP = 0.8;
    /** 半饱和常数：ΣW = W0 时份额恰好 50%×cap。 */
    public static final double HALF_SATURATION = 8.0;

    private WeightModel() {
    }

    public static double tagShare(double accumulatedWeight) {
        if (accumulatedWeight <= 0) {
            return 0.0;
        }
        return CAP * accumulatedWeight / (accumulatedWeight + HALF_SATURATION);
    }
}
