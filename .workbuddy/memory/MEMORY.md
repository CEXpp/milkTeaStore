# 项目长期约定（MEMORY.md）

## 技术栈（已核实）
- **Spring Boot 4.1.1**：官方系统要求明确「最低 Java 17，最高兼容 Java 26」（docs.spring.io/system-requirements）。
- **Java 25**（T04 起由用户指定；本机沙箱仅 JDK 21，用户自有机器为 JDK 25）。
- **构建**：Maven 3.6.3+（沙箱无可用 Maven，需用户侧构建验证）。
- **ORM：MyBatis-Plus**，`mybatis-plus-spring-boot4-starter 3.5.17`（Boot-4 专用，已适配）。
  - **不混用 JPA**：T04 已移除 `spring-boot-starter-data-jpa`（原 `spring.jpa.*` 配置一并清除）。
- **Web**：`spring-boot-starter-webmvc`（Boot 4 的 web starter）。
- **Flyway** 管表结构（`db/migration`）；**MinIO** 管对象存储。
- **Actuator**：`spring-boot-starter-actuator`，暴露 `/actuator/health`。
- **校验**：`spring-boot-starter-validation`（提供 jakarta.validation，使 @Valid 校验异常可被全局处理器捕获）。

## 统一响应与错误码（强制对齐 LLD）
- 响应体：`com.milktea.order.common.result.R<T>`（LLD 3.1）：字段 `{code, message, data}`，`code=0` 成功、`message="ok"`。
- 错误码枚举：`com.milktea.order.common.exception.ErrorCode`（LLD 3.2）：`0/401/403/1001~1008/500`。
- 全局异常：`com.milktea.order.common.exception.GlobalExceptionHandler`（`@RestControllerAdvice`），HTTP 状态恒为 200，错误以 body 内 `code` 表达；覆盖 业务 / 校验 / 未知 三类。
  - 401/403 已在 ErrorCode 定义，但处理器归属鉴权任务，未在此实现。
- 业务异常：`BusinessException(int code, String message)` / `BusinessException(ErrorCode)`。

## 前端技术栈（已核实，2026-09-13 确立）
- **顾客端微信小程序：uni-app（Vue 3 + TypeScript）**，编译产出微信小程序；目录 `miniprogram/`（与 `backend/`、`docker/` 同级），用官方 `uni-preset-vue#vite-ts` 脚手架。
  - 依据 SRS 表 10-1 选型清单（content.md:531）：用户拍板，与商家端 Vue 3 统一语法栈；HLD:204 选型对比确认（放弃原生小程序 / Taro）。
  - 启动脚本：`npm run dev:mp-weixin`；构建：`npm run build:mp-weixin`。
  - manifest.json 已写入用户原 appid `wxcd3beb616feadd06`、name「奶茶在线点单系统」。
- **商家端 Web：Vue 3 + TypeScript + Element Plus + Vite**（HLD:203）。

## 设计文档来源
见 `.workbuddy/instructions.md` → 项目资产网盘（netdrive 根 `CSRdfFHctQkR`）。

## 用户硬性规则
- 结论不许猜测，须有理有据；信息不足则提问缩窄范围。
- 改动前先复述需求逻辑。
- 日志须带修改版本号（如 `[T04]`）。

## 共享计划面板约定（2026-09-13 确立）
### 标签维度（4 个正交维度，已批量打到 T01–T38）
- **端/技术栈**：`后端-Java` / `前端-小程序` / `前端-商家Web` / `基础设施`
- **业务域**：`账号域` / `商品域` / `订单域` / `支付域` / `AI域` / `统计域`
- **阶段类型**：`基建` / `开发` / `联调` / `验收` / `发布`
- **风险标记（按需）**：`阻塞` / `需评审` / `依赖外部` / `待验证`
- 固定取值、不自由打字，避免标签碎片化。

### 通知分工（用户明确要求）
- **动态（project_message）**：主通知渠道，广播+有提示+所有人可见。用于「需主动周知的重要决策/变更」（如技术基线升级、版本对齐）。
- **面板标签**：长期结构化分类，用于筛选分组（端/域/阶段/风险），一眼分组不用追问。
- **任务评论（todo 评论）**：仅放细节补充，**不可作为主通知**——评论无提示、需点开详情才看得到（用户原话吐槽）。
- 结论：版本升级这类全局决策 → 发动态；任务分类 → 打标签；评论只补细节。
