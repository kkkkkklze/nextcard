package com.klze.nextcard.core.load;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容清单的收口（纯逻辑，可无头测试）。
 *
 * <p>它只解决一件事：<b>两个文件算出同一个卡 id</b>。这件事在原版的加载路径里是静默的——
 * {@code SimpleJsonResourceReloadListener.scanDirectory} 用 {@code Map.put}，后读到的那份
 * 直接盖掉前一份；而 {@code ResourceLocation} 的 id 里不含目录，所以
 * {@code cards/a/乱击.json} 与 {@code cards/b/乱击.json} 是同一张卡，谁赢取决于文件遍历顺序。</p>
 *
 * <p>内容侧已经真实撞到过一次：卡名「乱击」同时出现在多段 / 狂热 / 连环 / 血勇四个池里。
 * 那到底是四张卡还是一张卡写重了，必须由加载期报错逼出来，不能靠"看起来只有一张生效"。</p>
 */
public final class ContentCatalog {

    private ContentCatalog() {
    }

    /** 一个待加载的内容文件：{@code key} 是 {@code 命名空间:目录/文件名.json} 的完整路径。 */
    public record Entry(String key, JsonObject json) {
    }

    /**
     * 拼出送进 {@link ContentCatalog} 的键。
     *
     * <p>之所以要有这个函数而不是让调用方自己拼字符串：原版给监听者的键已经被剥掉目录与
     * {@code .json}，而 {@link ContentReader} 要求完整路径（它据此同时校验命名空间与文件名）。
     * 拼法收在一处，才不会两边各剥各的。</p>
     */
    public static String keyOf(String namespace, String dir, String fileName) {
        return namespace + ":" + dir + "/" + fileName + ".json";
    }

    /**
     * 把 {@code ResourceManager} 给的路径规范化成 {@link #keyOf} 的形状。
     *
     * <p>为什么要单独一步：不同 {@code ResourceManager} 实现返回的键有的带目录前缀、有的已经剥掉
     * （vanilla 的 {@code FileToIdConverter.fileToId} 会剥），而且剥完仍然保留嵌套目录
     * （{@code multistrike/wild_swing}）。这里两种都吃，只认最后一个文件名段——因为引擎的 id
     * 就是文件名段，目录只是内容的摆放方式。</p>
     */
    public static String keyFromPath(String namespace, String dir, String resourcePath) {
        String path = resourcePath.startsWith(dir + "/") ? resourcePath.substring(dir.length() + 1) : resourcePath;
        int slash = path.lastIndexOf('/');
        String file = (slash < 0 ? path : path.substring(slash + 1));
        if (!file.endsWith(".json") || file.length() <= ".json".length()) {
            return namespace + ":" + dir + "/" + file;      // 交给 collect 报 malformed，不在这里静默丢
        }
        return namespace + ":" + dir + "/" + file.substring(0, file.length() - ".json".length()) + ".json";
    }

    /** 收成 {@link ContentReader} 能吃的 Map，并拒绝任何 id 撞车。 */
    public static LoadResult<Map<String, JsonObject>> collect(List<Entry> entries) {
        List<String> errors = new ArrayList<>();
        Map<String, JsonObject> files = new LinkedHashMap<>();
        Map<ResourceLocation, String> seenBy = new LinkedHashMap<>();
        for (Entry entry : entries) {
            List<String> local = new ArrayList<>();
            ResourceLocation id;
            try {
                id = ContentReader.idOfPath(entry.key(), local);
            } catch (RuntimeException invalidId) {
                // 1.20.1 的 ResourceLocation 只收 [a-z0-9._-]，且它抛的是 ResourceLocationException
                // （不继承 IllegalArgumentException），中文文件名会当场炸。捕获范围就限定在这一行构造。
                // 它必须变成一条加载错误，而不是把整个 reload 炸掉——内容作者看到的应该是
                // 「这张卡的 id 不合法，请改用 ASCII 文件名、中文名放进 name」。
                errors.add(entry.key() + ": invalid content id: " + invalidId.getMessage());
                continue;
            }
            if (!local.isEmpty()) {
                errors.add(entry.key() + ": " + local.get(0));
                continue;
            }
            String previous = seenBy.putIfAbsent(id, entry.key());
            if (previous != null) {
                errors.add("duplicate content id " + id + " from both " + previous + " and " + entry.key());
                continue;
            }
            files.put(entry.key(), entry.json());
        }
        // 有错就不交半成品：调用方（reload 监听器）据此保留上一版，而不是让一张卡悄悄少掉
        return new LoadResult<>(errors.isEmpty() ? Map.copyOf(files) : Map.of(), List.copyOf(errors));
    }
}
