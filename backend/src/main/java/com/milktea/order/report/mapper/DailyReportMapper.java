package com.milktea.order.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milktea.order.report.entity.DailyReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * 每日经营日报数据访问（T68）。
 *
 * <p>只有基础 CRUD：日报的取数全部走既有的统计链路（{@code StatsService}），
 * 本表只负责「把打烊那一刻算出的快照存下来」。</p>
 */
@Mapper
public interface DailyReportMapper extends BaseMapper<DailyReport> {
}