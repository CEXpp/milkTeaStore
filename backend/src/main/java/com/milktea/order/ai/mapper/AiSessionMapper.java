package com.milktea.order.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milktea.order.ai.session.AiSessionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * AI 会话 Mapper（{@code ai_session} 表）。
 *
 * <p>包位置遵循项目约定：{@code @MapperScan("com.milktea.order.**.mapper")} 统一扫描各域
 * {@code mapper} 子包（见 {@code MybatisPlusConfig}），故本接口与实体分属不同包。</p>
 */
public interface AiSessionMapper extends BaseMapper<AiSessionEntity> {

    /**
     * 按会话 UUID 取主键。ChatMemoryStore 以 {@code session_uuid} 作为 {@code @MemoryId}，
     * 而消息表用 {@code session_id} 关联，故每次读写需一次命中唯一索引 {@code uk_uuid} 的轻量查询。
     *
     * @param sessionUuid 会话 UUID
     * @return 会话主键；不存在返回 {@code null}
     */
    @Select("SELECT id FROM ai_session WHERE session_uuid = #{sessionUuid}")
    Long selectIdByUuid(@Param("sessionUuid") String sessionUuid);

    /**
     * 惰性清理过期会话的草稿单（LLD 4.3 条件更新防并发模式 + HLD 数据保留策略）。
     *
     * <p>只作废草稿、不动消息历史（HLD：「AI 会话数据 30 分钟过期后惰性清理草稿，消息历史保留」）；
     * {@code draft_items IS NOT NULL} 条件保证重复执行不会反复命中同一批行。</p>
     *
     * @param now 当前时间
     * @return 受影响行数（0 表示本轮无过期草稿）
     */
    @Update("UPDATE ai_session SET draft_items = NULL, updated_at = #{now} "
            + "WHERE expires_at < #{now} AND draft_items IS NOT NULL")
    int clearExpiredDrafts(@Param("now") LocalDateTime now);
}
