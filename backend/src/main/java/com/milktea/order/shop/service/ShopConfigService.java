package com.milktea.order.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.shop.entity.ShopConfig;
import com.milktea.order.shop.mapper.ShopConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 门店配置写入服务（T23）：{@code shop_config} KV 表的 upsert 入口。
 *
 * <p>当前仅承载「暂停接单」开关（LLD 3.5.5）：写 paused / notice 两个键。
 * 读链路仍在 {@code MenuService}（顾客端菜单与 shop-status 共用同一份配置），
 * 本服务只负责写，避免读写语义混淆。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopConfigService {

    /** 暂停接单开关的配置键 */
    public static final String KEY_PAUSED = "paused";
    /** 顾客端暂停提示语的配置键 */
    public static final String KEY_NOTICE = "notice";

    private final ShopConfigMapper shopConfigMapper;

    /**
     * 设置暂停接单开关；notice 非空时同步更新提示语（空则保留原提示语）。
     *
     * @return 写入后的暂停状态
     */
    @Transactional
    public boolean updatePause(boolean paused, String notice) {
        upsert(KEY_PAUSED, Boolean.toString(paused));
        if (StringUtils.hasText(notice)) {
            upsert(KEY_NOTICE, notice.trim());
        }
        log.info("[T23] shop pause updated paused={} notice={}", paused, notice);
        return paused;
    }

    /** 按键 upsert：存在则更新，不存在则插入（shop_config 为 KV 表，无 Flyway 预置键时也能自愈）。 */
    private void upsert(String key, String value) {
        ShopConfig existing = shopConfigMapper.selectOne(new LambdaQueryWrapper<ShopConfig>()
                .eq(ShopConfig::getConfigKey, key)
                .last("LIMIT 1"));
        if (existing == null) {
            ShopConfig config = new ShopConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            shopConfigMapper.insert(config);
            return;
        }
        existing.setConfigValue(value);
        shopConfigMapper.updateById(existing);
    }
}
