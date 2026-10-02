package com.milktea.order.group.dto;

import com.milktea.order.order.dto.OrderItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * 参与者提交自己的选品（T63）。
 *
 * <p>语义是<b>整份替换</b>而非增量追加：「我这一份」就是请求里给的 items。
 * 增量追加需要客户端维护自己的偏移量，一旦中途失败就会重复累加——
 * 而「多算一杯」比「少算一杯」更难被用户发现。</p>
 *
 * <p>冻结后本请求一律拒绝（验收项「冻结后不可加入」）。</p>
 *
 * @param tag    成员标识（选填，如「003 王工」；T64 出餐时喊「003 王工」）
 * @param items  本人的选品；空列表等价于退出拼单（把自己从池里移除）
 */
public record GroupItemSubmitRequest(
        @Size(max = 64, message = "成员标识过长（最多 64 字）")
        String tag,

        @NotNull(message = "items 不能为 null（空数组表示退出拼单）")
        @Valid
        List<OrderItemRequest> items) {

    /** 清理后的成员标识：空白归一为 null。 */
    public String normalizedTag() {
        return tag == null || tag.isBlank() ? null : tag.trim();
    }

    /** 空 items 的便捷判断（= 退出拼单）。 */
    public boolean isLeaving() {
        return items == null || items.isEmpty();
    }

    /** 防篡改：不给前端「传 null 就当空」以外的解释空间。 */
    public List<OrderItemRequest> safeItems() {
        return items == null ? new ArrayList<>() : items;
    }
}