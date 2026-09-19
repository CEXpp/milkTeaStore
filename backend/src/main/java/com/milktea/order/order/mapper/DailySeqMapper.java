package com.milktea.order.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milktea.order.order.entity.DailySeq;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 每日流水访问（LLD 2.4 / 4.2 行锁方案）：UPDATE 行锁递增 → 同事务内 SELECT 取值。
 */
@Mapper
public interface DailySeqMapper extends BaseMapper<DailySeq> {

    /**
     * 行锁递增：命中一行即持有该行写锁直到事务提交——同 (seq_date, seq_type) 的取号串行化，天然防重号。
     *
     * @return 影响行数；0 表示当日该类型尚无流水行
     */
    @Update("UPDATE daily_seq SET current_seq = current_seq + 1 WHERE seq_date = #{seqDate} AND seq_type = #{seqType}")
    int incrementSeq(@Param("seqDate") LocalDate seqDate, @Param("seqType") String seqType);

    /**
     * 读取递增后的当前序号（须与 {@link #incrementSeq} 同一事务内执行）。
     */
    @Select("SELECT current_seq FROM daily_seq WHERE seq_date = #{seqDate} AND seq_type = #{seqType}")
    Integer selectCurrentSeq(@Param("seqDate") LocalDate seqDate, @Param("seqType") String seqType);
}
