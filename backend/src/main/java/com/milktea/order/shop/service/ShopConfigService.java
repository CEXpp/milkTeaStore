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
 * <p>承载两个键：{@code paused}（暂停接单开关，T23）与{@code notice}（营业公告，T62）。
 * 读链路仍在 {@code MenuService}（顾客端菜单与 shop-status 共用同一份配置），
 * 本服务只负责写，避免读写语义混淆。</p>
 *
 * <p><b>{@code notice} 的语义在 T62 收口</b>：此前它只是「暂停时自动写入的提示语」，
 * 现在升级为<b>面向顾客的营业公告</b>（「今日售罄」「新品上市」「预计 X 点恢复」），
 * 暂停只是公告的一种用法。因此 {@link #updatePause} <b>不再自动写入提示语</b>——
 * 否则店长刚写好的公告会被切一次开关就覆盖掉。暂停的原因由店长自行写进公告。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopConfigService {

    /** 暂停接单开关的配置键 */
    public static final String KEY_PAUSED = "paused";
    /** 顾客端营业公告的配置键（T62：可独立发布与撤下） */
    public static final String KEY_NOTICE = "notice";

    private final ShopConfigMapper shopConfigMapper;

    /**
     * 设置暂停接单开关。
     *
     * <p>T62 起<b>不再自动写入 notice</b>：公告改为店长自主维护的独立字段，
     * 切开关不应覆盖它。仅当请求显式带了 notice 时才同步（保留既有接口的向后兼容，
     * 供将来「按公告说明暂停原因」的用法）。</p>
     *
     * @return 写入后的暂停状态
     */
    @Transactional
    public boolean updatePause(boolean paused, String notice) {
        upsert(KEY_PAUSED, Boolean.toString(paused));
        if (StringUtils.hasText(notice)) {
            upsert(KEY_NOTICE, notice.trim());
        }
        log.info("[T62] shop pause updated paused={} noticeOverwritten={}", paused, StringUtils.hasText(notice));
        return paused;
    }

    /**
     * 发布 / 撤下营业公告（T62，v1 底座 F06）。
     *
     * <p><b>撤下即写空串</b>而不是删键：{@code shop_config} 是 KV 表，删键会让
     * 「没配过」与「配了又撤下」两种状态都表现为缺行；写空串则读写语义都稳定，
     * 且读取侧统一把空白归一化为 {@code null}（见 MenuService.getShopStatus）。</p>
     *
     * @param rawNotice 公告正文；null / 空串 / 纯空白均视为撤下
     * @return 归一化后的公告（撤下时为 {@code null}）
     */
    @Transactional
    public String updateNotice(String rawNotice) {
        String normalized = StringUtils.hasText(rawNotice) ? rawNotice.trim() : "";
        upsert(KEY_NOTICE, normalized);
        log.info("[T62] shop notice updated length={} cleared={}", normalized.length(), normalized.isEmpty());
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 按键 upsert：存在则更新，不存在则插入（shop_config 为 KV 表，无 Flyway 预置键时也能自愈）。
     *
     * <p>public 供同域其他配置读写服务复用（如 T52 的 SLA 阈值），
     * 避免「KV 写入」这件事出现第二份实现。</p>
     */
    public void upsert(String key, String value) {
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
