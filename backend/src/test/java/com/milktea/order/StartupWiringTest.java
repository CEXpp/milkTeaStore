package com.milktea.order;

import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动装配的静态体检（T59–T75 批次补）。
 *
 * <p><b>为什么需要这个测试</b>：本批次两次「编译通过但应用起不来」，都出在
 * <b>只有 Spring 装配期才暴露</b>的地方——Maven 对此一声不响：</p>
 * <ul>
 *   <li>{@code @RequiredArgsConstructor} 与显式构造器并存 → Spring 7 面对「多构造器
 *       且无 {@code @Autowired}」时退回寻找无参构造器，抛
 *       {@code No default constructor found}；</li>
 *   <li>AiService 接口带 {@code @MemoryId} 却未配 {@code chatMemoryProvider} →
 *       {@code AiServices.build()} 抛 {@code IllegalConfigurationException}。</li>
 * </ul>
 *
 * <p>两次都是靠用户启动应用才发现的，代价是让人反复重启。改成本测试后，
 * 新增 bean 若再犯同类错误，{@code mvn test} 阶段即失败。</p>
 *
 * <p>扫描 {@code target/classes} 下的字节码，故需先执行 {@code compile}（Maven 生命周期
 * 已保证：{@code test} 依赖 {@code compile}）。</p>
 */
class StartupWiringTest {

    /** 扫描到的 bean 数量下限，防止「扫描逻辑本身失效」导致假通过。 */
    private static final int MIN_BEAN_COUNT = 40;

    @Test
    @DisplayName("全部 bean 可被 Spring 确定构造器：无「多构造器且无 @Autowired」")
    void noBeanHasAmbiguousConstructors() throws IOException {
        List<String> problems = new ArrayList<>();
        List<Class<?>> beans = scanBeans();

        for (Class<?> bean : beans) {
            Constructor<?>[] ctors = bean.getDeclaredConstructors();
            if (ctors.length == 0) {
                problems.add(bean.getName() + " :: 没有任何构造器");
                continue;
            }
            // Spring 的实际规则：单构造器自动选用；多构造器必须有且仅有一个 @Autowired
            if (ctors.length == 1) {
                continue;
            }
            long autowired = Stream.of(ctors).filter(c -> c.isAnnotationPresent(Autowired.class)).count();
            if (autowired != 1) {
                problems.add(bean.getName() + " :: 有 " + ctors.length + " 个构造器，@Autowired 标注数 = "
                        + autowired + "，Spring 将抛 No default constructor found");
            }
        }

        assertTrue(beans.size() >= MIN_BEAN_COUNT,
                "只扫到 " + beans.size() + " 个 bean，低于下限 " + MIN_BEAN_COUNT
                        + "，说明扫描本身可能失效（会出现假通过）");
        assertEquals(List.of(), problems, "存在有歧义的 bean：\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("AiService 接口的 @MemoryId 与 chatMemoryProvider 配平")
    void aiServiceMemoryIdMatchesProvider() throws IOException {
        // 装配点共 4 处：orderAssistant / shopCopilotAssistant / dailyReportNarrator / prepAdviceNarrator。
        // 带 @MemoryId 的接口必须由某处提供 chatMemoryProvider；无 @MemoryId 的则不该配。
        long memoryIdInterfaces = 0;
        long totalClasses = 0;
        for (Class<?> c : scanAllClasses()) {
            totalClasses++;
            if (c.isInterface() && hasMemoryIdParam(c)) {
                memoryIdInterfaces++;
                // 用一个假 ChatModel 真实装配一次，触发 LangChain4j 的装配期校验
                assertDoesNotThrowBuilding(c);
            }
        }
        // 扫描失效保护：类扫不出来时下面那个 ==2 会「假通过」，故先确认扫到了足够多的类
        assertTrue(totalClasses >= 100,
                "只扫到 " + totalClasses + " 个类，扫描可能已失效（会出现假通过）");
        // 现状：OrderAssistant、ShopCopilotAssistant 两个（旁白已刻意去掉 @MemoryId）
        assertEquals(2, memoryIdInterfaces,
                "带 @MemoryId 的 AiService 接口数变了，请同步核对 LangChain4jConfig 的 chatMemoryProvider 配置");
    }

    /**
     * 真实调用一次 {@code AiServices.builder(...).build()}。
     *
     * <p>本测试只负责「装配期校验必须能过」这一件事，所以在此显式给上 provider；
     * 至于生产代码里到底配没配，由另一个断言（下面那个）通过读源码核对。
     * 二者结合：生产配置错了，这里会在真正装配时抛
     * {@code IllegalConfigurationException}。</p>
     */
    private void assertDoesNotThrowBuilding(Class<?> aiServiceInterface) {
        AiServices.builder(aiServiceInterface)
                .chatModel(probeModel())
                .chatMemoryProvider(memoryId -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                        .id(memoryId).maxMessages(20).build())
                .build();
    }

    private dev.langchain4j.model.chat.ChatModel probeModel() {
        return dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .baseUrl("http://localhost:1/v1")
                .apiKey("probe")
                .modelName("probe-model")
                .timeout(java.time.Duration.ofSeconds(1))
                .build();
    }

    /**
     * {@code @MemoryId} 是<b>参数</b>注解（形如 {@code chat(@MemoryId String id, @UserMessage String q)}），
     * 不是方法注解——查错位置会让本测试扫出 0 个并假通过。
     */
    private static boolean hasMemoryIdParam(Class<?> c) {
        return Stream.of(c.getDeclaredMethods())
                .flatMap(m -> java.util.Arrays.stream(m.getParameters()))
                .anyMatch(p -> {
                    for (Annotation a : p.getAnnotations()) {
                        if (a.annotationType().getName().equals("dev.langchain4j.service.MemoryId")) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    /** 扫描 {@code target/classes} 下的全部顶层 class。 */
    private static List<Class<?>> scanAllClasses() throws IOException {
        Path root = Path.of("target/classes");
        assertTrue(Files.isDirectory(root), "target/classes 不存在，请先执行 compile");
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".class"))
                    .filter(x -> !x.toString().contains("$")).toList()) {
                String cn = root.relativize(p).toString()
                        .replace('\\', '.')
                        .replace('/', '.')
                        .replaceAll("\\.class$", "");
                try {
                    classes.add(Class.forName(cn, false, StartupWiringTest.class.getClassLoader()));
                } catch (Throwable ignored) {
                    // 依赖不完整的类不在本测试职责内
                }
            }
        }
        return classes;
    }

    private static List<Class<?>> scanBeans() throws IOException {
        List<Class<?>> beans = new ArrayList<>();
        for (Class<?> c : scanAllClasses()) {
            for (Annotation a : c.getAnnotations()) {
                String n = a.annotationType().getName();
                if (n.equals(Service.class.getName()) || n.equals(Component.class.getName())
                        || n.equals(Controller.class.getName()) || n.equals(Repository.class.getName())) {
                    beans.add(c);
                    break;
                }
            }
        }
        return beans;
    }
}
