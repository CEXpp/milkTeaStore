package com.milktea.order.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.shop.entity.ShopConfig;
import com.milktea.order.shop.mapper.ShopConfigMapper;
import com.milktea.order.shop.vo.SlaSettingsVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 看板 SLA 预警阈值读写（T52，v1 底座 F05）。
 *
 * <p><b>为什么阈值放 {@code shop_config} 而不是配置文件</b>：任务卡指定了键名
 * {@code sla_warn_seconds} / {@code sla_danger_seconds}，并要求「阈值改动后预警即时生效」——
 * 运行期可改的 KV 才做得到，配置文件需要重启。</p>
 *
 * <p><b>「即时生效」怎么实现的</b>：不做缓存，每次读都打 DB（单店量级、看板刷新频率低），
 * 阈值随 board 响应一起下发。所以商家一改，下一次看板刷新立刻生效——中间没有任何缓存层。</p>
 *
 * <p><b>与 6.1 状态流转的关系</b>：本服务只提供两个数字，既不判定也不触发任何状态迁移；
 * 颜色与置顶都由前端/排序完成，因此「与 6.1 状态流转不冲突」（验收项）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaSettingsService {

    /** shop_config 键：转黄阈值（秒）。键名由任务卡指定，不可改。 */
    public static final String KEY_WARN_SECONDS = "sla_warn_seconds";

    /** shop_config 键：转红阈值（秒）。键名由任务卡指定，不可改。 */
    public static final String KEY_DANGER_SECONDS = "sla_danger_seconds";

    /** 默认转黄阈值（秒）：5 分钟。 */
    public static final int DEFAULT_WARN_SECONDS = 300;

    /** 默认转红阈值（秒）：10 分钟。 */
    public static final int DEFAULT_DANGER_SECONDS = 600;

    private static final int MIN_SECONDS = 10;
    private static final int MAX_SECONDS = 24 * 3600;

    private final ShopConfigMapper shopConfigMapper;
    private final ShopConfigService shopConfigService;

    /**
     * 当前生效阈值（缺省 / 非法值时回落默认）。
     *
     * <p>同时保证 {@code danger > warn}：若配置反了则按 {@code warn + 60} 归一——
     * 否则「先转红后转黄」的区间为空，前端永远显示不出黄色档。</p>
     */
    public SlaSettingsVo current() {
        Map<String, String> values = load();
        int warn = fallbackIfInvalid(values.get(KEY_WARN_SECONDS), DEFAULT_WARN_SECONDS, KEY_WARN_SECONDS);
        int danger = fallbackIfInvalid(values.get(KEY_DANGER_SECONDS), DEFAULT_DANGER_SECONDS, KEY_DANGER_SECONDS);
        if (danger <= warn) {
            log.warn("[T52] SLA 阈值 danger({}) <= warn({})，按 warn+60 归一", danger, warn);
            danger = warn + 60;
        }
        return new SlaSettingsVo(warn, danger);
    }

    /**
     * 更新阈值。
     *
     * @throws BusinessException 1001 为空 / 越界 / 转红不大于转黄
     */
    @Transactional
    public SlaSettingsVo update(Integer warnSeconds, Integer dangerSeconds) {
        if (warnSeconds == null || dangerSeconds == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "转黄与转红阈值均不可为空");
        }
        int warn = requireInRange(warnSeconds, KEY_WARN_SECONDS);
        int danger = requireInRange(dangerSeconds, KEY_DANGER_SECONDS);
        if (danger <= warn) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "转红阈值必须大于转黄阈值");
        }
        shopConfigService.upsert(KEY_WARN_SECONDS, Integer.toString(warn));
        shopConfigService.upsert(KEY_DANGER_SECONDS, Integer.toString(danger));
        log.info("[T52] SLA 阈值已更新 warn={}s danger={}s", warn, danger);
        return new SlaSettingsVo(warn, danger);
    }

    /** 一次 IN 查询取两个键（同 T46 的 shop_config 覆盖写法）。 */
    private Map<String, String> load() {
        List<ShopConfig> rows = shopConfigMapper.selectList(new LambdaQueryWrapper<ShopConfig>()
                .in(ShopConfig::getConfigKey, List.of(KEY_WARN_SECONDS, KEY_DANGER_SECONDS)));
        Map<String, String> values = new HashMap<>(rows.size());
        for (ShopConfig row : rows) {
            values.put(row.getConfigKey(), row.getConfigValue());
        }
        return values;
    }

    private int fallbackIfInvalid(String raw, int fallback, String key) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < MIN_SECONDS || parsed > MAX_SECONDS) {
                log.warn("[T52] shop_config {} 越界（{}），回落默认 {}", key, raw, fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.warn("[T52] shop_config {} 非整数（{}），回落默认 {}", key, raw, fallback);
            return fallback;
        }
    }

    private int requireInRange(int value, String key) {
        if (value < MIN_SECONDS || value > MAX_SECONDS) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(),
                    key + " 需在 " + MIN_SECONDS + " ~ " + MAX_SECONDS + " 秒之间");
        }
        return value;
    }
}
