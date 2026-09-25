package com.klze.nextcard.common.load;

import com.klze.nextcard.core.card.CardIndex;
import com.klze.nextcard.core.tag.TagIndex;

/**
 * 一份可用的内容快照（标签 + 卡）。
 *
 * <p>它是 reload 监听器唯一对外暴露的东西：整份换、不换半份，所以运行期任何时刻读到的
 * 都是一套自洽的内容（跨文件引用不会因为"标签换了卡没换"而暂时指空）。</p>
 */
public record ContentBundle(TagIndex tags, CardIndex cards, int tagFiles, int cardFiles) {

    public static final ContentBundle EMPTY = new ContentBundle(TagIndex.EMPTY, CardIndex.empty(), 0, 0);

    public boolean isEmpty() {
        return cardFiles == 0;
    }
}
