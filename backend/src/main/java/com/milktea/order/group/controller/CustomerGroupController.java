package com.milktea.order.group.controller;

import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.result.R;
import com.milktea.order.group.dto.GroupCreateRequest;
import com.milktea.order.group.dto.GroupItemSubmitRequest;
import com.milktea.order.group.service.GroupCartService;
import com.milktea.order.group.vo.GroupCartVo;
import com.milktea.order.order.vo.PayVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顾客端拼单接口（T63，A5 · W13；路径属 {@code /api/customer/**} 鉴权矩阵，取顾客 JWT 身份）。
 *
 * <ul>
 *   <li>{@code POST /api/customer/groups}：创建拼单池（body {minutes}，缺省 30）；</li>
 *   <li>{@code GET  /api/customer/groups/{uuid}}：查看池（含实时计价与失效提示）；</li>
 *   <li>{@code POST /api/customer/groups/{uuid}/items}：提交 / 更新自己的选品（空数组 = 退出）；</li>
 *   <li>{@code POST /api/customer/groups/{uuid}/freeze}：发起人冻结（冻结后他人不可再加入）；</li>
 *   <li>{@code POST /api/customer/groups/{uuid}/pay}：发起人支付 → 生成一张订单；</li>
 *   <li>{@code POST /api/customer/groups/{uuid}/abandon}：发起人放弃（不生成订单）。</li>
 * </ul>
 *
 * <p><b>身份一律取 JWT</b>，不接受请求体传 customerId——池里每个元素的归属
 * 由服务端按登录身份写入，客户端无法冒充他人往池里塞东西。</p>
 */
@RestController
@RequestMapping("/api/customer/groups")
@RequiredArgsConstructor
public class CustomerGroupController {

    private final GroupCartService groupCartService;

    /** 创建拼单：返回 groupUuid（分享口令的载体）。 */
    @PostMapping
    public R<String> create(@RequestBody(required = false) GroupCreateRequest request) {
        return R.ok(groupCartService.create(currentCustomerId(), request));
    }

    /** 查看拼单池：金额由后端现算，冻结 / 支付按钮由 canFreeze 决定是否显示。 */
    @GetMapping("/{uuid}")
    public R<GroupCartVo> view(@PathVariable("uuid") String uuid) {
        return R.ok(groupCartService.view(uuid, currentCustomerId()));
    }

    /** 提交 / 更新自己的选品；传空 items 即退出拼单。 */
    @PostMapping("/{uuid}/items")
    public R<Void> submitItems(@PathVariable("uuid") String uuid,
                               @Valid @RequestBody GroupItemSubmitRequest request) {
        groupCartService.submitItems(uuid, currentCustomerId(), request);
        return R.ok(null);
    }

    /** 发起人冻结：冻结后不可再加入或改单，等发起人支付。 */
    @PostMapping("/{uuid}/freeze")
    public R<Void> freeze(@PathVariable("uuid") String uuid) {
        groupCartService.freeze(uuid, currentCustomerId());
        return R.ok(null);
    }

    /** 发起人支付：生成一张订单（一个取餐码 + N 个订单项，金额后端重算）。 */
    @PostMapping("/{uuid}/pay")
    public R<PayVo> pay(@PathVariable("uuid") String uuid) {
        return R.ok(groupCartService.pay(uuid, currentCustomerId()));
    }

    /** 发起人放弃：池失效，不生成任何订单。 */
    @PostMapping("/{uuid}/abandon")
    public R<Void> abandon(@PathVariable("uuid") String uuid) {
        groupCartService.abandon(uuid, currentCustomerId());
        return R.ok(null);
    }

    /** 当前登录顾客；过滤器链保证此处必有身份。 */
    private Long currentCustomerId() {
        return AuthContext.get().getCustomerId();
    }
}