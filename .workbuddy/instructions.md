# 工作空间导航（instructions.md）

本文件是多步定位的入口：先在此确认「设计文档在哪」「项目约定在哪」，再跳转对应文档。

## 1. 设计文档（LLD / SRS / HLD）在哪？
**不在 Git 仓库内，位于「项目资产」网盘（netdrive）**，根目录 ID：`CSRdfFHctQkR`。
取文档用 `mcp__netdrive__tdrive.file_download`（首次返回链接可能报 `InvalidAccessKeyId`，重试一次换 AK 即可正常下载，文档为 docx）。

| 文档 | 文件 ID | 用途 |
|---|---|---|
| 《奶茶在线点单系统详细设计说明书》 | `CQrBJdzzhpML` | **LLD**：3.1 统一规范（响应体）、3.2 错误码表（强制约束，开发不得变更） |
| 《奶茶在线点单系统需求规格说明书》 | `CohkoRgtyIli` | SRS：业务/数据需求 |
| 《奶茶在线点单系统概要设计说明书》 | `CPbUKdxeKwpn` | HLD：架构/鉴权矩阵 |
| 《奶茶在线点单系统排期与施工步骤》 | `CafwZLgcXcGI` | 任务排期（T 系列任务来源） |

## 2. 项目约定（技术栈 / 编码规则）
见 `.workbuddy/memory/MEMORY.md`（长期项目约定，包含 Spring Boot 版本、Java 版本、ORM、统一响应、错误码、硬性规则）。

## 3. 每日工作记录
见 `.workbuddy/memory/YYYY-MM-DD.md`（按日期，记录当日完成内容）。

## 4. 用户硬性规则（每次对话必须遵守）
- 结论不允许猜测，必须有理有据。
- 接到需求先复述需求逻辑，避免牛头不对马嘴。
- 信息不足时向用户提问、缩小检查范围。
- 日志须带修改版本号（如 `[T04]`）。
- 每次对话先读取本 instructions.md（成功读取后告知用户）。
