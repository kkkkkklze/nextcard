package com.klze.nextcard.logic;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 纯逻辑测试 runner（JUnit 断言 + 本地反射驱动），退出码非零 = 失败。
 *
 * <p>为什么不用 Gradle 的 {@code test} 任务：本工作区路径含非 ASCII 字符
 * （…\Documents\开发\mod\…），Gradle 经平台默认编码回传 worker 命令行，测试类连自己都
 * 加载不到（ClassNotFoundException，类文件与 classpath 都在）。ModDevGradle 自己的 run 任务
 * 靠 UTF-8 参数文件绕开；这里用 JavaExec 把 classpath 直接交给 {@code java -cp}——
 * 同一套方案已在 spirepowers 工程验证（踩坑记录：中文路径 + Gradle test 必炸）。</p>
 */
public final class NextCardLogicTestRunner {

    /** 显式列出被测套件——新增套件是有意识的动作，不做 classpath 扫描。 */
    private static final Class<?>[] SUITES = {
            com.klze.nextcard.TickUtilsTest.class,
            NextCardLogicTest.class,
            ContentParityTest.class,
            ContentLiteralAuditTest.class,
    };

    private NextCardLogicTestRunner() {
    }

    public static void main(String[] args) {
        int passed = 0;
        List<String> failures = new ArrayList<>();

        for (Class<?> suite : SUITES) {
            List<Method> tests = new ArrayList<>(Arrays.stream(suite.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Test.class))
                    .sorted(Comparator.comparing(Method::getName))
                    .toList());
            Object instance;
            try {
                var constructor = suite.getDeclaredConstructor();
                constructor.setAccessible(true);
                instance = constructor.newInstance();
            } catch (ReflectiveOperationException e) {
                failures.add(suite.getSimpleName() + ": cannot instantiate — " + e);
                continue;
            }

            for (Method test : tests) {
                String label = suite.getSimpleName() + "." + test.getName();
                try {
                    test.setAccessible(true);
                    test.invoke(instance);
                    passed++;
                    System.out.println("LOGIC ok   : " + label);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    failures.add(label + " — " + cause);
                    System.out.println("LOGIC FAIL : " + label + " — " + cause);
                } catch (ReflectiveOperationException e) {
                    failures.add(label + " — could not invoke: " + e);
                    System.out.println("LOGIC FAIL : " + label + " — could not invoke: " + e);
                }
            }
            System.out.println("LOGIC suite: " + suite.getSimpleName() + " — " + tests.size() + " test(s)");
        }

        System.out.println("=== pure-logic tests: " + passed + " passed, " + failures.size() + " failed ===");
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("  FAIL " + f));
            System.exit(1);
        }
        System.out.println("LOGIC RESULT: PASS");
    }
}
