package com.klze.nextcard.core.load;

import java.util.List;

/** 内容加载结果：值 + 错误清单。错误非空 = 内容写坏，调用方必须 fail-fast（§4.5），绝不静默降级。 */
public record LoadResult<T>(T value, List<String> errors) {

    public LoadResult {
        errors = List.copyOf(errors);
    }

    public boolean ok() {
        return errors.isEmpty();
    }
}
