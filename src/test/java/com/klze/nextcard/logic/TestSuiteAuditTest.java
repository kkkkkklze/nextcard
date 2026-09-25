package com.klze.nextcard.logic;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第九道门的本工程版本：测试清单 ↔ 磁盘 对账（G4 的自检）。
 *
 * <p>存在的理由是一条实测事实：{@link NextCardLogicTestRunner} 用的是手写 SUITES 清单（中文路径下
 * 唯一可靠的跑法，故不能改成 classpath 扫描），而<b>新写的测试类忘了登记时，门照样打印"全部通过"
 * 并退 0</b>——我们已在 2026-09-25 用一条"永不可能通过"的探针实测过这个静默通道：未登记时
 * 输出 26 passed 0 failed 且退出码 0，登记后立刻退出码 1。</p>
 *
 * <p>三种红法各自钉住：磁盘有而未登记（漏跑）、登记了而磁盘没有（清单腐烂）、登记了但类里没有
 * {@code @Test}（改了方法名丢掉注解也算静默丢失）。</p>
 */
public class TestSuiteAuditTest {

    @Test
    public void everyTestOnDiskIsRegisteredAndEverySuiteStillHasTests() throws Exception {
        Path root = testRoot();
        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("Test.java"))
                    .forEach(path -> onDisk.add(classNameOf(root, path)));
        }
        List<String> declared = declaredSuites();

        List<String> problems = new ArrayList<>();
        for (String fqn : onDisk) {
            if (!declared.contains(fqn)) {
                problems.add("磁盘上有但未登记进 SUITES（会被静默跳过）: " + fqn);
            }
        }
        for (String fqn : declared) {
            if (!onDisk.contains(fqn)) {
                problems.add("SUITES 登记了但磁盘上没有对应 *Test.java: " + fqn);
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** 登记了却一个 @Test 都没有的套件 = 0 通过 0 失败的假绿源。 */
    @Test
    public void everyDeclaredSuiteStillContainsTestMethods() throws Exception {
        List<String> empty = new ArrayList<>();
        for (String fqn : declaredSuites()) {
            Class<?> type = Class.forName(fqn);
            long count = 0;
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Test.class)) {
                    count++;
                }
            }
            if (count == 0) {
                empty.add(fqn);
            }
        }
        assertTrue(empty.isEmpty(), "这些套件登记了却没有一个 @Test 方法: " + empty);
    }

    private static List<String> declaredSuites() throws Exception {
        Field field = NextCardLogicTestRunner.class.getDeclaredField("SUITES");
        field.setAccessible(true);
        Class<?>[] suites = (Class<?>[]) field.get(null);
        assertNotNull(suites, "SUITES 必须存在（清单式跑法是中文路径下的唯一可靠方案）");
        List<String> names = new ArrayList<>();
        for (Class<?> suite : suites) {
            names.add(suite.getName());
        }
        names.sort(String::compareTo);
        return names;
    }

    private static Path testRoot() {
        String value = System.getProperty("nextcard.test.src");
        assertTrue(value != null, "nextcard.test.src must be provided by the logicTest or test task");
        Path root = Path.of(value);
        assertTrue(Files.isDirectory(root), "test source root missing: " + root);
        return root;
    }

    private static String classNameOf(Path root, Path file) {
        Path relative = root.relativize(file);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < relative.getNameCount(); i++) {
            String part = relative.getName(i).toString();
            if (i == relative.getNameCount() - 1) {
                name.append(part.substring(0, part.length() - ".java".length()));
            } else {
                name.append(part).append('.');
            }
        }
        return name.toString();
    }

    /** 反射读私有静态字段需要非 final 之外的权限，这里只是自证审计确实能看见清单（防将来改访问性后静默失效）。 */
    @Test
    public void suiteFieldIsReadableByTheAudit() throws Exception {
        Field field = NextCardLogicTestRunner.class.getDeclaredField("SUITES");
        assertTrue(Modifier.isStatic(field.getModifiers()), "SUITES 必须是静态字段，否则审计看不到内容");
        assertTrue(declaredSuites().size() >= 7,
                "套件清单不应低于当前规模（掉了说明有测试被摘掉而没人发现）: " + declaredSuites());
    }
}
