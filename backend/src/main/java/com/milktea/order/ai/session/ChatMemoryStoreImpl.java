package com.milktea.order.ai.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.ai.mapper.AiMessageMapper;
import com.milktea.order.ai.mapper.AiSessionMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 MySQL 的会话记忆存储（T29，LLD 6.5「MySQL ChatMemoryStore」）。
 *
 * <p>把 LangChain4j 的记忆窗口读写映射到 {@code ai_message} 表，使同一 {@code session_uuid}
 * 的历史在应用重启后依然存在（排期 T29 卡完成标准「记忆持久化（重启不丢）」）。</p>
 *
 * <p><b>接口契约（1.20.0 源码核验）</b>：抽象的只有三个方法——{@link #getMessages} /
 * {@link #updateMessages} / {@link #deleteMessages}；1.20.0 新增的三个 {@code @Experimental}
 * 异步默认方法不在本任务实现范围（同步链路不会调用，默认实现抛
 * {@code AsyncNotSupportedException}）。</p>
 *
 * <p><b>角色映射</b>：以 {@code ChatMessage.type()} 为准（{@code ChatMessageType} 的
 * 5 个枚举值与 {@code ai_message.role} 的对应关系见 {@link #roleOf}），
 * {@code content} 存该消息的 LangChain4j JSON 文本。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMemoryStoreImpl implements ChatMemoryStore {

    private final AiMessageMapper aiMessageMapper;
    private final AiSessionMapper aiSessionMapper;

    /**
     * 读取某会话的全部历史消息（按写入顺序）。
     *
     * <p>单行反序列化失败时跳过该行并告警，避免一条脏数据导致整轮对话不可用。</p>
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        Long sessionId = resolveSessionId(memoryId);
        if (sessionId == null) {
            return new ArrayList<>();
        }
        List<AiMessageEntity> rows = aiMessageMapper.selectList(
                new LambdaQueryWrapper<AiMessageEntity>()
                        .eq(AiMessageEntity::getSessionId, sessionId)
                        .orderByAsc(AiMessageEntity::getId));
        List<ChatMessage> messages = new ArrayList<>(rows.size());
        for (AiMessageEntity row : rows) {
            try {
                messages.add(ChatMessageDeserializer.messageFromJson(row.getContent()));
            } catch (Exception e) {
                log.warn("AI 消息反序列化失败，已跳过：sessionId={} messageId={} role={}",
                        sessionId, row.getId(), row.getRole(), e);
            }
        }
        return messages;
    }

    /**
     * 写回记忆窗口当前状态（整窗覆盖语义，与 {@code MessageWindowChatMemory} 的约定一致）。
     *
     * <p>整窗覆盖意味着「先删后插」，两步必须在同一事务内，否则中途失败会丢历史。</p>
     *
     * <p><b>并发保护（死锁修复）</b>：本方法每轮对话都会被调用一次，而 LangChain4j 在工具调用循环里
     * 还可能多次触发。若同一会话上有两个请求交叉执行，「Tx1 删除后插入」与「Tx2 已持有的间隙锁」会互相
     * 等待，MySQL 抛死锁并回滚其中一方，整轮对话以 1008 失败。因此这里先经
     * {@link AiSessionMapper#lockIdByUuid} 对会话行加排他锁，把同一会话的写回串行化；
     * 这是「按会话串行」而非全局串行，不同会话互不影响。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        Long sessionId = resolveSessionIdForUpdate(memoryId);
        if (sessionId == null) {
            log.warn("AI 记忆写回时未找到会话，本次写回跳过：memoryId={}", memoryId);
            return;
        }
        aiMessageMapper.deleteBySessionId(sessionId);
        LocalDateTime now = LocalDateTime.now();
        for (ChatMessage message : messages) {
            AiMessageEntity row = new AiMessageEntity();
            row.setSessionId(sessionId);
            row.setRole(roleOf(message));
            row.setContent(ChatMessageSerializer.messageToJson(message));
            row.setCreatedAt(now);
            aiMessageMapper.insert(row);
        }
    }

    /** 删除某会话的全部消息（{@code ChatMemory.clear()} 与会话清理调用）。 */
    @Override
    public void deleteMessages(Object memoryId) {
        Long sessionId = resolveSessionId(memoryId);
        if (sessionId == null) {
            return;
        }
        aiMessageMapper.deleteBySessionId(sessionId);
    }

    /**
     * 把 {@code @MemoryId}（即 {@code ai_session.session_uuid}）解析为 {@code ai_session.id}。
     *
     * @return 会话主键；UUID 非法或会话不存在时返回 {@code null}
     */
    private Long resolveSessionId(Object memoryId) {
        if (memoryId == null) {
            return null;
        }
        return aiSessionMapper.selectIdByUuid(String.valueOf(memoryId));
    }

    /**
     * 同 {@link #resolveSessionId}，但顺带对该会话行加排他锁（仅在事务内有效）。
     *
     * <p>用于整窗覆盖写回：先锁会话行，再「删 + 插」，使同一会话的并发写回串行化，避免死锁。</p>
     */
    private Long resolveSessionIdForUpdate(Object memoryId) {
        if (memoryId == null) {
            return null;
        }
        return aiSessionMapper.lockIdByUuid(String.valueOf(memoryId));
    }

    /**
     * ChatMessage 类型 → {@code ai_message.role}。
     *
     * <p>{@code ChatMessageType} 恰有 5 个枚举值（1.20.0 核验），故 switch 可穷尽、无需 default。</p>
     */
    private static String roleOf(ChatMessage message) {
        ChatMessageType type = message.type();
        return switch (type) {
            case USER -> AiMessageEntity.ROLE_USER;
            case AI -> AiMessageEntity.ROLE_ASSISTANT;
            case TOOL_EXECUTION_RESULT -> AiMessageEntity.ROLE_TOOL;
            case SYSTEM -> AiMessageEntity.ROLE_SYSTEM;
            case CUSTOM -> AiMessageEntity.ROLE_CUSTOM;
        };
    }
}
