package com.milktea.order.ai.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.ai.mapper.AiMessageMapper;
import com.milktea.order.ai.mapper.AiSessionMapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 会话与记忆持久化集成测试（T29）。
 *
 * <p>覆盖排期 T29 卡的完成标准：<b>记忆持久化（重启不丢）</b>与<b>会话过期清理</b>，
 * 外加会话归属校验与过期新建两条生命周期分支。</p>
 *
 * <p>「重启不丢」用「重建 store 实例后仍能读回」模拟：{@link ChatMemoryStoreImpl} 无任何内存态，
 * 全部状态在 {@code ai_message} 表，故新实例等价于重启后的新进程。</p>
 *
 * <p>数据卫生：测试统一使用 {@code customer_id} 为负数的专用顾客，并在 {@link #cleanUp()} 中按顾客
 * 清理本次产生的会话与消息行。</p>
 */
@SpringBootTest
class AiSessionMemoryTest {

    /** 测试专用顾客 id（负数，避免与真实顾客数据混淆） */
    private static final long TEST_CUSTOMER_ID = -1001L;
    /** 越权用例的第二个测试顾客 id */
    private static final long OTHER_CUSTOMER_ID = -1002L;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private ChatMemoryStoreImpl memoryStore;

    @Autowired
    private AiSessionMapper aiSessionMapper;

    @Autowired
    private AiMessageMapper aiMessageMapper;

    @AfterEach
    void cleanUp() {
        List<AiSessionEntity> sessions = aiSessionMapper.selectList(
                new LambdaQueryWrapper<AiSessionEntity>()
                        .in(AiSessionEntity::getCustomerId, TEST_CUSTOMER_ID, OTHER_CUSTOMER_ID));
        for (AiSessionEntity session : sessions) {
            aiMessageMapper.deleteBySessionId(session.getId());
            aiSessionMapper.deleteById(session.getId());
        }
    }

    @Test
    @DisplayName("首轮新建会话：session_uuid 非空、绑定顾客、过期时间为 30 分钟后")
    void createSessionOnFirstTurn() {
        AiSessionEntity session = sessionService.locateOrCreate(null, TEST_CUSTOMER_ID);

        assertNotNull(session.getId());
        assertNotNull(session.getSessionUuid());
        assertEquals(TEST_CUSTOMER_ID, session.getCustomerId());
        assertNull(session.getDraftItems(), "新会话草稿应为空");
        assertTrue(session.getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(29)),
                "过期时间应约为 now + 30 分钟");
    }

    @Test
    @DisplayName("记忆持久化：重建 store 实例仍能读回 USER/ASSISTANT/TOOL 三类消息")
    void memorySurvivesNewStoreInstance() {
        AiSessionEntity session = sessionService.locateOrCreate(null, TEST_CUSTOMER_ID);

        List<ChatMessage> window = List.of(
                UserMessage.from("你好"),
                AiMessage.from("你好呀，想喝点什么？"),
                ToolExecutionResultMessage.from("call_1", "searchMenu", "珍珠奶茶 17.00"));
        memoryStore.updateMessages(session.getSessionUuid(), window);

        // 模拟应用重启：新实例、无内存态，只能从 ai_message 表读回
        ChatMemoryStoreImpl restarted = new ChatMemoryStoreImpl(aiMessageMapper, aiSessionMapper);
        List<ChatMessage> loaded = restarted.getMessages(session.getSessionUuid());

        assertEquals(3, loaded.size(), "重启后应读回全部 3 条消息");
        assertEquals(ChatMessageType.USER, loaded.get(0).type());
        assertEquals("你好", ((UserMessage) loaded.get(0)).singleText());
        assertEquals(ChatMessageType.AI, loaded.get(1).type());
        assertEquals("你好呀，想喝点什么？", ((AiMessage) loaded.get(1)).text());
        assertEquals(ChatMessageType.TOOL_EXECUTION_RESULT, loaded.get(2).type());
        assertEquals("珍珠奶茶 17.00", ((ToolExecutionResultMessage) loaded.get(2)).text());
    }

    @Test
    @DisplayName("落库角色映射：USER / ASSISTANT / TOOL 与消息顺序一致")
    void persistRoleMappingInOrder() {
        AiSessionEntity session = sessionService.locateOrCreate(null, TEST_CUSTOMER_ID);
        memoryStore.updateMessages(session.getSessionUuid(), List.of(
                UserMessage.from("来杯珍珠奶茶"),
                AiMessage.from("好的，大杯还是中杯？"),
                ToolExecutionResultMessage.from("call_1", "searchMenu", "命中 1 项")));

        List<AiMessageEntity> rows = aiMessageMapper.selectList(
                new LambdaQueryWrapper<AiMessageEntity>()
                        .eq(AiMessageEntity::getSessionId, session.getId())
                        .orderByAsc(AiMessageEntity::getId));

        assertEquals(3, rows.size());
        assertEquals(AiMessageEntity.ROLE_USER, rows.get(0).getRole());
        assertEquals(AiMessageEntity.ROLE_ASSISTANT, rows.get(1).getRole());
        assertEquals(AiMessageEntity.ROLE_TOOL, rows.get(2).getRole());
        assertTrue(rows.get(0).getContent().contains("来杯珍珠奶茶"), "content 应为可读的 JSON 文本");
        assertNotNull(rows.get(0).getCreatedAt());
    }

    @Test
    @DisplayName("整窗覆盖语义：二次写回覆盖旧窗口而非追加")
    void updateMessagesOverwritesWindow() {
        AiSessionEntity session = sessionService.locateOrCreate(null, TEST_CUSTOMER_ID);
        memoryStore.updateMessages(session.getSessionUuid(), List.of(UserMessage.from("第一轮")));
        memoryStore.updateMessages(session.getSessionUuid(), List.of(
                UserMessage.from("第二轮"),
                AiMessage.from("第二轮回复")));

        List<ChatMessage> loaded = memoryStore.getMessages(session.getSessionUuid());
        assertEquals(2, loaded.size(), "整窗覆盖后应只剩最新窗口的 2 条");
        assertEquals("第二轮", ((UserMessage) loaded.get(0)).singleText());
    }

    @Test
    @DisplayName("过期清理：作废草稿单但保留消息历史（HLD 数据保留策略）")
    void clearExpiredDraftsKeepsMessages() {
        AiSessionEntity session = sessionService.locateOrCreate(null, TEST_CUSTOMER_ID);
        memoryStore.updateMessages(session.getSessionUuid(),
                List.of(UserMessage.from("我要一杯"), AiMessage.from("好的")));

        // 造出「有草稿 + 已过期」的状态
        AiSessionEntity expired = new AiSessionEntity();
        expired.setId(session.getId());
        expired.setDraftItems("[{\"productName\":\"珍珠奶茶\",\"optionNames\":[\"大杯\"],\"quantity\":1}]");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        expired.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        aiSessionMapper.updateById(expired);

        int cleared = aiSessionMapper.clearExpiredDrafts(LocalDateTime.now());
        assertTrue(cleared >= 1, "应至少清理到本次造的过期草稿");

        AiSessionEntity after = aiSessionMapper.selectById(session.getId());
        assertNull(after.getDraftItems(), "过期后草稿单应被置空");
        assertEquals(2, memoryStore.getMessages(session.getSessionUuid()).size(),
                "消息历史必须保留");
    }

    @Test
    @DisplayName("过期会话再对话：新建会话并返回新的 sessionId")
    void expiredSessionIsReplaced() {
        AiSessionEntity session = sessionService.locateOrCreate(null, TEST_CUSTOMER_ID);
        AiSessionEntity expired = new AiSessionEntity();
        expired.setId(session.getId());
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        expired.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        aiSessionMapper.updateById(expired);

        AiSessionEntity renewed = sessionService.locateOrCreate(session.getSessionUuid(), TEST_CUSTOMER_ID);

        assertNotEquals(session.getSessionUuid(), renewed.getSessionUuid(), "过期会话应被替换为新会话");
        assertTrue(renewed.getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(29)));
    }

    @Test
    @DisplayName("续期：对话后 expires_at 刷新为 now + 30 分钟")
    void refreshExpiryExtendsSession() {
        AiSessionEntity session = sessionService.locateOrCreate(null, TEST_CUSTOMER_ID);
        AiSessionEntity shortened = new AiSessionEntity();
        shortened.setId(session.getId());
        shortened.setExpiresAt(LocalDateTime.now().plusMinutes(1));
        aiSessionMapper.updateById(shortened);
        session.setExpiresAt(shortened.getExpiresAt());

        sessionService.refreshExpiry(session);

        AiSessionEntity after = aiSessionMapper.selectById(session.getId());
        assertTrue(after.getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(29)),
                "续期后过期时间应回到 now + 30 分钟");
    }

    @Test
    @DisplayName("归属校验：他人会话被拒绝（403），防止跨顾客读取草稿与记忆")
    void rejectSessionOwnedByAnotherCustomer() {
        AiSessionEntity session = sessionService.locateOrCreate(null, TEST_CUSTOMER_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sessionService.locateOrCreate(session.getSessionUuid(), OTHER_CUSTOMER_ID));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("未知会话 id：按新建处理，不抛异常")
    void unknownSessionIdCreatesNewSession() {
        AiSessionEntity session = sessionService.locateOrCreate("not-exist-uuid", TEST_CUSTOMER_ID);
        assertNotNull(session.getId());
        assertNotEquals("not-exist-uuid", session.getSessionUuid());
    }
}
