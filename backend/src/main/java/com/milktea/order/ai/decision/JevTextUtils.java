package com.milktea.order.ai.decision;

/**
 * 决策层的文本度量工具：token 估算与 CJK 判定。
 *
 * <p><b>为什么要自己估算 token</b>：官方硬限是「单请求 ≤64k tokens，且 state + 最长单问 ≤32k」，
 * 但后端不可能为此引一个 tokenizer（本项目不新增依赖）。用「CJK 每字 ≈ 1 token、
 * 其余每 4 字符 ≈ 1 token」的保守估算即可——估算的用途是<b>触发截断保护</b>，
 * 而不是精确计费（计费以响应里的 {@code usage.input_tokens} 为准）。保守偏高正是我们要的方向。</p>
 */
final class JevTextUtils {

    private JevTextUtils() {
    }

    /** 估算文本 token 数（保守偏高）。 */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    /**
     * 判定字符是否属于 CJK（含中日韩统一表意文字、假名、谚文、CJK 标点与全角形式）。
     *
     * <p>覆盖范围刻意比「汉字」宽：全角标点与假名在英文模型里同样是低置信区域，
     * 我们的语言纪律应当把它们一并挡住。</p>
     */
    static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 中日韩统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF)  // 兼容表意文字
                || (c >= 0x3040 && c <= 0x30FF)  // 平假名 / 片假名
                || (c >= 0xAC00 && c <= 0xD7AF)  // 谚文音节
                || (c >= 0x3000 && c <= 0x303F)  // CJK 标点
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角字符
    }

    /** 文本是否含任何 CJK 字符（语言纪律断言用）。 */
    static boolean containsCjk(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
