package com.milktea.order.order.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.order.entity.DailySeq;
import com.milktea.order.order.mapper.DailySeqMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 流水号服务：订单号与取餐码的唯一发放口（LLD 4.2，任务卡 T11）。
 *
 * <p>两者共用同一张 daily_seq 表，按 {@code (seq_date, seq_type)} 分维度纳管：</p>
 * <ul>
 *   <li>订单号 ORDER_NO：{@code yyMMdd + 5 位序列}，如 26091200123；</li>
 *   <li>取餐码 PICKUP_CODE：当日三位流水 {@code 001..999}，超过 999 自然进位为四位（1000），每自然日重置。</li>
 * </ul>
 *
 * <p>并发方案（LLD 2.4 行锁）：{@code UPDATE daily_seq SET current_seq = current_seq + 1 ...} 命中行即持写锁至事务提交，
 * 随后在同一事务内 SELECT 取值；影响行数为 0 时先补插当日新行再重试一次。三个渠道（小程序 / AI / 柜台）共用同一条流水。</p>
 *
 * <p>方法默认加入调用方事务（{@link Propagation#REQUIRED}）：下单失败整体回滚时不会吃掉号码。</p>
 */
@Slf4j
@Service
public class SequenceService {

    /**
     * 流水类型，取值与 daily_seq.seq_type 注释一致。
     */
    public enum SeqType {
        ORDER_NO,
        PICKUP_CODE
    }

    /** 订单号日期前缀：yyMMdd。 */
    private static final DateTimeFormatter ORDER_DATE_PREFIX = DateTimeFormatter.ofPattern("yyMMdd");
    /** 订单号序列位宽。 */
    private static final int ORDER_SEQ_WIDTH = 5;
    /** 取餐码位宽：不足三位补前导零，超过自然进位。 */
    private static final int PICKUP_CODE_WIDTH = 3;

    private final DailySeqMapper dailySeqMapper;

    /** 日期来源：按 {@code app.time-zone}（默认东八区）计自然日，测试用 {@link #useClock} 替换。 */
    private Clock clock;

    public SequenceService(DailySeqMapper dailySeqMapper,
                           @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.dailySeqMapper = dailySeqMapper;
        this.clock = Clock.system(ZoneId.of(timeZone));
    }

    /**
     * 取下一张订单号（yyMMdd + 5 位序列，如 26091200123）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextOrderNo() {
        LocalDate seqDate = LocalDate.now(clock);
        long seq = nextSequence(seqDate, SeqType.ORDER_NO);
        return seqDate.format(ORDER_DATE_PREFIX) + pad(seq, ORDER_SEQ_WIDTH);
    }

    /**
     * 取下一个取餐码（当日三位流水：001 → 999，超限自然进位四位；每日重置）。
     * <p>支付成功（进入 PAID）或柜台单创建时调用，超时关闭的单不吃号。</p>
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextPickupCode() {
        long seq = nextSequence(LocalDate.now(clock), SeqType.PICKUP_CODE);
        return pad(seq, PICKUP_CODE_WIDTH);
    }

    /**
     * 按日期与类型取下一号（行锁递增 + 同事务回读）。可被同一事务内的其他服务复用。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public long nextSequence(LocalDate seqDate, SeqType seqType) {
        String type = seqType.name();

        int updated = dailySeqMapper.incrementSeq(seqDate, type);
        if (updated == 0) {
            // 当日该类型尚未发号：补插新行后重试一次（并发插入时后到者会撞唯一键，忽略即可）
            insertRowIfAbsent(seqDate, type);
            updated = dailySeqMapper.incrementSeq(seqDate, type);
        }
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(), "流水号分配失败，请稍后重试");
        }

        Integer currentSeq = dailySeqMapper.selectCurrentSeq(seqDate, type);
        if (currentSeq == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(), "流水号读取失败，请稍后重试");
        }
        return currentSeq;
    }

    private void insertRowIfAbsent(LocalDate seqDate, String seqType) {
        try {
            dailySeqMapper.insert(new DailySeq(seqDate, seqType, 0));
        } catch (DataIntegrityViolationException e) {
            // uk_date_type 冲突：说明并发线程已插入同一行，直接参与后续递增即可
            log.debug("daily_seq row already exists, seqDate={}, seqType={}", seqDate, seqType);
        }
    }

    private static String pad(long seq, int width) {
        return String.format("%0" + width + "d", seq);
    }

    /**
     * 替换日期来源，仅用于单测模拟跨日。
     */
    void useClock(Clock clock) {
        this.clock = clock;
    }
}
