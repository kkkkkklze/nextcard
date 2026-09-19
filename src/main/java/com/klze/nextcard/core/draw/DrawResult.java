package com.klze.nextcard.core.draw;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 一次抽卡的结果：状态、候选卡列表、决策日志（每步概率份额，服务调试与 UI「为什么抽出这几张」）。
 */
public record DrawResult(Status status, List<ResourceLocation> offers, List<String> log) {

    public enum Status { OK, NO_CARDS }

    /** 全部可抽卡为空时的提示（第三批裁定 4：直接显示卡池无卡）。 */
    public static final String NO_CARDS_MESSAGE = "卡池无卡";

    public static DrawResult noCards() {
        return new DrawResult(Status.NO_CARDS, List.of(), List.of(NO_CARDS_MESSAGE));
    }

    public boolean isEmpty() {
        return status == Status.NO_CARDS;
    }
}
