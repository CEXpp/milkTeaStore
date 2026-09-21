package com.milktea.order.ai.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.milktea.order.ai.draft.DraftItem;
import com.milktea.order.ai.mapper.AiSessionMapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * AI 会话生命周期服务（T29，LLD 6.5「AiSessionService 会话生命周期与草稿单管理」）。
 *
 * <p>职责：会话定位/新建、归属校验、活动续期、过期草稿惰性清理、草稿单存取。
 * 消息历史的读写由 {@link ChatMemoryStoreImpl} 承担；草稿单的<b>内容语义</b>（商品/规格校验、
 * 计价、覆盖或追加）由 AI 工具集（T30）与 {@code AiChatService}（T31）承担，
 * 本服务负责 {@code draft_items} 列与 {@link DraftItem} 列表之间的编解码。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final AiSessionMapper aiSessionMapper;
    private final ObjectMapper objectMapper;

    /** 会话无活动过期时长（分钟），LLD 6.5 口径为 30 分钟 */
    @Value("${ai.session-timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    /**
     * 定位或新建会话（LLD 6.6 步骤 1）。
     *
     * <p>分支规则：</p>
     * <ul>
     *   <li>{@code sessionId} 为空 → 新建（首轮对话，后端返回新 {@code sessionId}）；</li>
     *   <li>会话存在且未过期 → 复用；</li>
     *   <li>会话已过期 → 新建（LLD 6.6「会话过期则新建并提示重新点单」）；</li>
     *   <li>会话存在但不属于当前顾客 → 抛 403，防止跨顾客读取他人会话与草稿（SRS 5.4「会话绑定身份」）。</li>
     * </ul>
     *
     * @param sessionUuid 前端携带的会话标识，首轮可为空
     * @param customerId  当前登录顾客（来自 {@code AuthContext}）
     * @return 可用的会话实体（含主键）
     */
    @Transactional(rollbackFor = Exception.class)
    public AiSessionEntity locateOrCreate(String sessionUuid, Long customerId) {
        if (sessionUuid != null && !sessionUuid.isBlank()) {
            AiSessionEntity existing = aiSessionMapper.selectOne(
                    new LambdaQueryWrapper<AiSessionEntity>()
                            .eq(AiSessionEntity::getSessionUuid, sessionUuid));
            if (existing != null) {
                if (!Objects.equals(existing.getCustomerId(), customerId)) {
                    log.warn("AI 会话归属校验失败：sessionUuid={} ownerId={} currentId={}",
                            sessionUuid, existing.getCustomerId(), customerId);
                    throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "会话不属于当前顾客");
                }
                if (existing.getExpiresAt() != null && existing.getExpiresAt().isAfter(LocalDateTime.now())) {
                    return existing;
                }
                log.info("AI 会话已过期，按新建处理：sessionUuid={}", sessionUuid);
            } else {
                log.info("AI 会话不存在，按新建处理：sessionUuid={}", sessionUuid);
            }
        }
        return create(customerId);
    }

    /**
     * 读会话草稿单（{@code ai_session.draft_items}）。
     *
     * <p>JSON 损坏或会话不存在时按空草稿处理并告警——草稿单坏了不该让整轮对话不可用
     * （过期清理会把 {@code draft_items} 置 NULL，这里是同一种「无草稿」语义）。</p>
     *
     * <p><b>返回值恒为可变的 {@link ArrayList}</b>：调用方（工具集）需要在读到的条目上做
     * 追加/覆盖，使用 {@code List.of()} 这类不可变实现会在首次加购时抛
     * {@code UnsupportedOperationException}。</p>
     *
     * @param sessionUuid 会话标识（即 {@code @MemoryId}）
     * @return 草稿条目（可变）；无草稿、会话不存在或 JSON 损坏时返回空列表
     */
    public List<DraftItem> loadDraft(String sessionUuid) {
        List<DraftItem> items = new ArrayList<>();
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return items;
        }
        AiSessionEntity session = aiSessionMapper.selectOne(
                new LambdaQueryWrapper<AiSessionEntity>()
                        .eq(AiSessionEntity::getSessionUuid, sessionUuid));
        String json = session == null ? null : session.getDraftItems();
        if (!StringUtils.hasText(json)) {
            return items;
        }
        try {
            DraftItem[] parsed = objectMapper.readValue(json, DraftItem[].class);
            if (parsed != null) {
                for (DraftItem item : parsed) {
                    // 容错：[null] 之类的脏数据跳过，不让一条坏条目拖垮整轮对话
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
        } catch (JacksonException e) {
            log.warn("[AI] 草稿单 JSON 解析失败，按空草稿处理：sessionUuid={}", sessionUuid, e);
        }
        return items;
    }

    /**
     * 覆盖写会话草稿单（整份 JSON 覆盖语义，与消息记忆窗口的整窗覆盖一致）。
     *
     * @param sessionUuid 会话标识
     * @param items       草稿条目
     * @return 是否命中会话（{@code false} 表示会话不存在或已被清理）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean saveDraft(String sessionUuid, List<DraftItem> items) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return false;
        }
        return updateDraftColumn(sessionUuid, objectMapper.writeValueAsString(items));
    }

    /**
     * 清空会话草稿单（置 {@code draft_items = NULL}）。
     *
     * <p>必须用 {@code UpdateWrapper.set(...)} 显式置空：MyBatis-Plus 默认更新策略会忽略 null 字段，
     * 走实体更新的写法无法把列写回 NULL。</p>
     *
     * @param sessionUuid 会话标识
     * @return 是否命中会话
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean clearDraft(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return false;
        }
        return updateDraftColumn(sessionUuid, null);
    }

    /** 按 {@code session_uuid} 条件更新草稿列，避免「先查后改」的并发窗口。 */
    private boolean updateDraftColumn(String sessionUuid, String draftJson) {
        return aiSessionMapper.update(null, new LambdaUpdateWrapper<AiSessionEntity>()
                .set(AiSessionEntity::getDraftItems, draftJson)
                .set(AiSessionEntity::getUpdatedAt, LocalDateTime.now())
                .eq(AiSessionEntity::getSessionUuid, sessionUuid)) > 0;
    }

    /**
     * 活动续期：每次对话后刷新过期时间（LLD 6.5「每次对话刷新 expires_at = now + 30 分钟」）。
     *
     * @param session 本轮使用的会话
     */
    @Transactional(rollbackFor = Exception.class)
    public void refreshExpiry(AiSessionEntity session) {
        LocalDateTime now = LocalDateTime.now();
        AiSessionEntity update = new AiSessionEntity();
        update.setId(session.getId());
        update.setExpiresAt(now.plusMinutes(sessionTimeoutMinutes));
        update.setUpdatedAt(now);
        aiSessionMapper.updateById(update);
        session.setExpiresAt(update.getExpiresAt());
        session.setUpdatedAt(now);
    }

    /**
     * 过期草稿惰性清理（LLD 6.5，复用 LLD 4.3 调度模式）。
     *
     * <p>用条件 UPDATE 而非先查后改，天然防并发重复清理；只作废草稿、保留消息历史
     * （HLD 数据保留策略）。调度开关由 {@code @EnableScheduling} 提供（见启动类）。</p>
     */
    @Scheduled(fixedDelay = 60_000)
    public void clearExpiredDrafts() {
        int cleared = aiSessionMapper.clearExpiredDrafts(LocalDateTime.now());
        if (cleared > 0) {
            log.info("[T29] AI 会话过期，已作废草稿 {} 份（消息历史保留）", cleared);
        }
    }

    private AiSessionEntity create(Long customerId) {
        LocalDateTime now = LocalDateTime.now();
        AiSessionEntity session = new AiSessionEntity();
        session.setSessionUuid(UUID.randomUUID().toString());
        session.setCustomerId(customerId);
        session.setExpiresAt(now.plusMinutes(sessionTimeoutMinutes));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        aiSessionMapper.insert(session);
        log.info("[T29] 新建 AI 会话：sessionUuid={} customerId={}", session.getSessionUuid(), customerId);
        return session;
    }
}
