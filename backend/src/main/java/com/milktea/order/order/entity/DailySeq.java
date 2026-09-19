package com.milktea.order.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 每日流水行（daily_seq）：订单号 / 取餐码按 (seq_date, seq_type) 维度行锁递增，每自然日自动新开一行。
 *
 * <p>DDL 见 V1__init_schema.sql，字段与 {@code uk_date_type} 唯一键保持一致。</p>
 */
@Data
@TableName("daily_seq")
public class DailySeq {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 流水归属自然日。
     */
    private LocalDate seqDate;

    /**
     * 流水类型：ORDER_NO / PICKUP_CODE。
     */
    private String seqType;

    /**
     * 已发放到的最大序号，初始 0。
     */
    private Integer currentSeq;

    public DailySeq() {
    }

    public DailySeq(LocalDate seqDate, String seqType, int currentSeq) {
        this.seqDate = seqDate;
        this.seqType = seqType;
        this.currentSeq = currentSeq;
    }
}
