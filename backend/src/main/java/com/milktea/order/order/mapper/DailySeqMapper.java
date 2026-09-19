package com.milktea.order.order.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 每日流水（daily_seq）行锁递增，见 LLD 4.2。
 *
 * <p>取订单号：UPDATE 影响 0 行则 INSERT 新行（current_seq=0）后重试一次，
 * 全程在创建订单的事务内完成；seq_date 维度天然按自然日重开新行。</p>
 */
public interface DailySeqMapper {

    String TYPE_ORDER_NO = "ORDER_NO";

    /**
     * 行锁递增：命中则 current_seq+1，返回受影响行数（0 表示当天该类型尚无行）。
     */
    @Update("UPDATE daily_seq SET current_seq = current_seq + 1 "
            + "WHERE seq_date = #{date} AND seq_type = #{type}")
    int increment(@Param("date") LocalDate date, @Param("type") String type);

    /**
     * 首行插入（current_seq=0，供后续 increment 推成 1）。
     */
    @Insert("INSERT INTO daily_seq (seq_date, seq_type, current_seq) "
            + "VALUES (#{date}, #{type}, 0)")
    int insertRow(@Param("date") LocalDate date, @Param("type") String type);

    /**
     * 读取当前序列值。
     */
    @Select("SELECT current_seq FROM daily_seq WHERE seq_date = #{date} AND seq_type = #{type}")
    Integer selectCurrent(@Param("date") LocalDate date, @Param("type") String type);
}
