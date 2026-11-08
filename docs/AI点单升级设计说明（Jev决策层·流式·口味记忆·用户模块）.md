# AI 点单升级设计说明（Jev 决策层 · 并行流式 · 口味记忆 · 用户模块 · 视觉重构）

> 本轮增量文档。SRS / HLD / LLD / 排期 与《AC清单V2》的既有内容继续有效，
> 本文件负责记录**本轮新增与变更**：设计取舍、接口契约、配置项、测试与验收。

## 1. 本轮范围

| 方向 | 交付内容 |
| --- | --- |
| AI 决策 | 新增 **Jev 决策层**（TypeSafe AI 的 System One 决策模型），对「选哪款/杯型/温度/甜度/加料/几杯」给出带校准置信度的裁决 |
| AI 上下文 | **不做 RAG**，改为「全量菜单上下文注入」 |
| AI 体验 | **并行流式**：承接话术先回显，决策与草稿卡随后追加 |
| AI 记忆 | **长期口味偏好**（仅从已成交订单隐式聚合） |
| 用户模块 | 头像 + 昵称 + 个人中心页 + tabBar 第四项（沿用微信静默登录） |
| 视觉 | 全站奶油暖调极简 + design token 化，无障碍高对比模式无损 |

**明确不做**：知识检索型问答（RAG）、手机号快捷登录、会员/积分/优惠券、地址管理、
在对话中自动写入偏好、新增任何中间件（Redis / 向量库 / embedding）。

## 2. 分层架构（不替换，只新增）

```
POST /api/customer/ai/chat/stream
        │
        ├─ 前置（请求线程，同步）：鉴权 → 限流 → 会话定位 → 返回 SseEmitter
        │
        └─ 编排（aiStreamExecutor 线程池，两条分支并发）
             ├─ 分支 A｜表述层：ChatNarrator（GLM 流式，无工具、无记忆）→ delta 事件
             └─ 分支 B｜决策层：DecisionOrchestrator
                    ├─ MenuStateAssembler：全量菜单 + 偏好 + 原话 → state
                    ├─ JevClient → POST /v1/systemone（choice/score/noul）
                    ├─ 置信度路由：命中 → DraftOrderTool 落草稿（不经过模型）
                    └─ 未命中/降级 → 既有 OrderAssistant（GLM + @Tool 工具集）
```

**为什么必须是分层而不是「全面换成 Jev」**：Jev 是**类型化决策模型**——它不产出自然语言、
也不能调用工具。语言生成与工具编排只能由 GLM 承担；Jev 只负责在候选里做裁决。
「决策」与「表述」分开之后，两边的失败面也分开了：
表述层挂了是「没话说」，决策层挂了是「照旧用 GLM 点单」，都不会让 AI 点单不可用。

## 3. Jev 决策层

### 3.1 端点与契约（照官方实现）

```
POST https://api.typesafe.ai/v1/systemone
Authorization: Bearer <TYPESAFE_API_KEY>
{ "model": "jev-1.13.0", "state": "...", "questions": { "<name>": { "type", "instructions", "criteria" } } }
```

| 题型 | criteria 形态 | 响应字段 |
| --- | --- | --- |
| `choice` | `{key: 说明}`（≤255 项） | `choice`、`probabilities`、`confidence` |
| `score` | 有序等级数组（2–10 级） | `score`、`legend`、`probabilities`、`confidence` |
| `noul` | 可选 `{true, false}` | `noul`（**无 confidence**） |

**请求题单**（一次请求并行评测，`state` 只摄入一次）：

| 问题名 | 题型 | 作用 |
| --- | --- | --- |
| `intent` | choice(`ORDER`/`OTHER`) | 区分「下新单」与改单/退单/闲聊 |
| `ready_to_order` | noul | 信息是否足以直接落一杯，否则转澄清 |
| `product` | choice(商品编码) | 选品 |
| `quantity` | choice(1..20) | 杯数 |
| `grp_<组code>` | choice(组内下标) | 单选规格（杯型/温度/甜度…） |
| `opt_<组code>_<下标>` | score(不选/选) | 多选规格（加料），逐项判定 |

### 3.2 为什么不做 RAG：全量上下文注入

RAG 解决「语料太大、捞不全」；而本店菜单是**少量商品 + 四组规格**的强结构化小数据。
上向量检索只会引入**召回误差**（漏召 → 模型看不到某商品 → 推荐错），收益为负。
Jev 单请求上下文远大于全量菜单所需，因此直接把完整菜单喂进 `state`。

`state` 受 `jev.max-state-tokens` 保护（默认 6000，估算口径），超出时按菜单序截断尾部商品
**并至少保留一个候选**（全截空比超预算更糟），同时打 WARN。

`state` 三段式，且**语言纪律可被断言**：

```
MENU (all on-sale products, in menu order):
P01 | Pearl milk tea | base_price=12.00 | spec_groups=CUP_SIZE,TEMPERATURE,SWEETNESS,TOPPING
...
SPEC GROUPS (answer with the option index; single = exactly one, multi = any number):
SWEETNESS (single): index 0 = Full sugar; index 1 = 70% sugar; ...
CURRENT DRAFT (already chosen, not yet paid): P01{CUP_SIZE:1,SWEETNESS:2}x2

LEARNED PREFERENCE of this customer (from 7 past paid orders; use as default only, the request always wins):
favorite_product=P03; SWEETNESS=2

CUSTOMER REQUEST (verbatim, may be Chinese):
<顾客原话>
```

### 3.3 语言纪律：编码化英文化 + 显式降级

Jev 官方声明「英文为主要训练语言；CJK 可处理但精度不均」，而本店商品名与规格名全是中文。
处理方式：

- **决策键一律 ASCII**：商品用 `P01`、规格组用英文 code、规格项用**组内下标**；
  模型返回的永远是 ASCII 编码，中文只作为「描述位」出现 → CJK 风险被限制在「理解输入」，
  不会扩散到「输出解析」。
- **描述性文字优先英文标签**：`MenuLabelDictionary` 覆盖种子菜单的商品与规格；
  未命中时**回落真实中文名**并置 `degraded=true` + WARN——不编造 `option_7` 这类占位符
  （占位符会让模型在几个看不懂的选项之间瞎选）。
- **升级路径**：真实门店扩容后可在 `product` / `spec_option` 加 `label_en` 列由店主填写，
  字典改读该列即可，调用方零改动。

`MenuStateAssembler` 的单元测试直接断言 **menuBlock 与 preferenceBlock 零 CJK 字符**
（正则判定），把这条纪律变成会失败的测试而不是口头约定。

### 3.4 置信度聚合与路由

- 聚合取**所依赖各项 confidence 的最小值**，不取平均：点一杯奶茶是「与」的关系，
  商品/规格/数量任何一项拿不准，整杯就是错的。
- `noul` 无 confidence，故 `ready_to_order` 用其概率做门限（≥0.5），**不参与聚合**。
- 路由：

```
intent != ORDER            → 转 GLM 链路（改单/退单/闲聊，行为与升级前一致）
ready_to_order < 0.5       → 转 GLM 链路（澄清反问）
商品编码不在在售集合        → 转 GLM 链路（防模型幻觉编码）
必选规格缺失 / 下标越界     → 转 GLM 链路（不替顾客选杯型）
min(confidence) < 阈值     → 转 GLM 链路（宁可多问一句，不可猜错下单）
以上全过                    → 程序化落草稿（hit=true）
```

阈值 `jev.confidence-threshold` 默认 **0.72**。

### 3.5 草稿落库：复用约束层，且不信返回值文本

命中后**不自己拼草稿**，而是复用既有 `DraftOrderTool.updateDraftOrder`——它里面装着
「AI 约束三原则」的全部落地（商品只在在售集合里找、规格逐个匹配启用组、单选组漏选拒绝、
金额一律走 `PricingService`）。自己再写一遍就等于把安全边界复制成两份，迟早漂移。

并且**回读验证**：`DraftOrderTool` 用中文文案表达失败（「该商品需要选择杯型。」），
按文案判成败脆弱且不可测；因此 `DecisionOrchestrator` 落库后重新读取会话草稿，
目标条目确实存在才算命中。校验不过则视为未命中并转 GLM 链路——**以约束层为准**。

`DecisionOrchestratorTest` 用反射断言该类**不持有任何 chat model**（AI 只做表述层、不做计算层）。

### 3.6 错误处理与熔断（照官方错误表）

| 状态 | 处理 |
| --- | --- |
| `401` 认证失败 | **不重试**，立即失败并降级 |
| `422` 校验失败 | **不重试** |
| `429` 限流 | 指数退避 `1s→2s→4s` + jitter |
| `529` 过载 | 同上；连续失败达阈值即熔断 |
| 网络超时 | 视为瞬态，可重试 |

熔断状态是进程内两个原子量（连续失败数、打开截止时刻）。**冷却期内本地短路，连一次 TCP 都不发**
——对方已经过载时继续打只会把限流窗口越推越远。冷却期满进入半开探测，探测失败立即重新熔断。

退避与冷却都通过可替换的 `JevRuntime`（时钟 + 休眠原语）实现，因此这两条策略是
**确定性、毫秒级的单测**，而不是「跑得慢还偶发超时」的集成测试。

## 4. 长期口味偏好（不进对话热路径的「记忆」）

### 4.1 数据来源与隐私口径

**只从已成交订单的规格快照聚合，不解析对话内容、不接受显式写入。**
计入状态仅 `PAID / PREPARING / COMPLETED`（都代表已付款）；
`PENDING_PAYMENT`（未付）/ `CLOSED`（超时关单）/ `VOIDED`（已作废退款）一律不计
——否则「点错又退掉的那杯」会污染偏好，下次还会照着推荐一遍。

### 4.2 认定口径

| 规则 | 理由 |
| --- | --- |
| 分母按**订单数**，不按杯数；一单在每组只计一次 | 一杯点三次与一次点三杯是两回事，只有前者代表「反复选择」 |
| 分母**按组单独统计** | 不是所有商品都挂了全部规格组，用总订单数当分母会把加料偏好稀释掉 |
| 组内分母 < `min-sample-orders`（默认 3）→ 整组放弃 | 防「一单定终身」：只下过一单就当成口味，比没有偏好更糟 |
| 选项占比 ≥ `mode-ratio`（默认 0.6）才认定 | 单选组占比天然互斥，故最多命中一项；多选组可命中多项 |
| 常点商品同样要求总样本达标 | 否则「只下过两单、恰好同一款」会被当成常点商品 |

### 4.3 刷新时机

1. **订单事件驱动**：`ORDER_STATUS_CHANGED` 且状态为 `PAID` / `COMPLETED` → 异步重算
   （单线程执行器，失败吞掉并记日志——旁路能力不得反噬订单主链路）；
2. **惰性补算**：读取偏好时若 `built_at` 超过 `stale-hours`（默认 24h）则先重算一次
   （挂在 GET 上，避免再加一个遍历所有顾客的定时任务）；
3. 样本不足时**照实落一行**（`sample_orders` 记真值、选项为空），
   用于把「顾客没有历史」与「还没算过」区分开，避免每次对话都扫两遍表。

存储用一条 `INSERT ... ON DUPLICATE KEY UPDATE`（依赖唯一键 `uk_customer`）：
事件回调与页面读取可能同时到达同一顾客，交给数据库唯一键串行化比在应用层加锁更可靠。
本服务刻意**不加 `@Transactional`**：它是「纯读 + 一条原子 upsert」，
而且不能写成「自己调自己」的事务方法（自调用不过代理，事务会静默失效）。

### 4.4 存储形态

```json
[{"groupCode":"SWEETNESS","optionNames":["半糖"]},{"groupCode":"TOPPING","optionNames":["珍珠"]}]
```

用**数组 + 具体记录类型**（与项目既有 `DraftItem[]` / `OptionSnapshot[]` 同一种读写方式），
不为「组 code → 选项名」的映射去碰泛型反序列化。
组内偏好存**中文选项名**而非下标：个人中心的「口味档案」要直接展示给顾客，
存下标的话菜单改版后会错位到别的选项上——那是静默显示错口味。

## 5. 流式契约（SSE）

### 5.1 事件表（前后端唯一真相）

| event | data 载荷 | 前端动作 |
| --- | --- | --- |
| `stage` | `{"stage":"UNDERSTAND\|PICK\|PRICE"}` | 点亮阶段文案 |
| `delta` | `{"text":"..."}` | 增量追加到助手气泡 |
| `decision` | `{"source":"JEV\|GLM_FALLBACK","confidence":0.93,"productCode":"P01"}` | 记录来源（可观测） |
| `card` | `{"items":[...],"totalAmount":"15.00"}` | 挂草稿卡并触发语音播报 |
| `done` | `{"sessionId":"...","replyType":"TEXT\|CARD"}` | 收尾，恢复输入 |
| `error` | `{"code":1008,"message":"...","fallbackText":"..."}` | 复用既有错误处理 |

推送顺序：`stage → delta* → stage? → decision → delta? → card? → done`，失败以 `error` 收尾。

### 5.2 三个必须说清的设计点

1. **首字闸门**：两条分支谁先到达不确定，若草稿卡抢在承接话术之前会出现
   「先冒卡片、再补一句收到」的错乱顺序。故推 `decision`/`card` 前先等分支 A 的**首个增量**
   最多 `first-delta-gate-millis`（默认 2s）；旁白结束或失败也立即放行，超时同样放行
   ——旁白不该成为主链路的阻塞点。
2. **不伪造流式**：走 GLM 链路时其回复是一次性拿到的，就**整段**作为一条 `delta` 下发，
   不切成小块假装逐字生成（伪流式会让人误以为「模型在逐字生成」，排查延迟时走错方向）。
3. **整体超时看门狗**：决策分支的最坏耗时 = Jev 重试 + GLM 超时，远超 20 秒预算。
   只依赖各自的超时不够——用户等的是「有结论的响应」。看门狗保证无论中间哪一跳卡住，
   都推 `error` 并主动 `complete()`，不留悬挂连接；`finished` 标记由 CAS 收口
   （正常收尾、看门狗超时、客户端断开三方可能同时到达）。

### 5.3 传输细节

- 端点为 **POST + SSE**（不是原生 `EventSource`）：小程序用
  `uni.request({ enableChunked: true, responseType: 'arraybuffer' })` + `onChunkReceived`，
  它能发 POST、能带 `Authorization` 头，因此**令牌不必放进 URL**（URL 会进访问日志）。
- `produces` **必须显式带 `charset=UTF-8`**：流式内容是中文，只声明 `text/event-stream`
  会让字符串转换器退回 ISO-8859-1 而乱码。
- 前端自实现递增式 UTF-8 解码（不依赖 `TextDecoder` 在小程序侧的可用性），
  并保留「跨块不完整字节」到下一块——一个汉字 3 字节，分块边界可能正好切在中间。
- 分块能力按基础库版本判断（`SDKVersion >= 2.20.0`）；不支持时直接走非流式接口。
- **降级出口**：`POST /api/customer/ai/chat` 保持不变。传输层故障且**一个事件都没收到**时，
  前端自动回退一次非流式调用；已经收到半截回答则只提示不重试
  （重发会让模型把同一句话看两遍）。

## 6. 用户模块

| 接口 | 说明 |
| --- | --- |
| `GET /api/customer/profile` | 读昵称与头像 |
| `PUT /api/customer/profile` | 改昵称（去首尾空白、纯空白与超长拒绝） |
| `POST /api/customer/profile/avatar` | 上传头像（multipart，字段名 `file`）→ 转存 MinIO → 落对象 key |
| `GET /api/customer/preference` | 口味档案（读时可能惰性补算一次） |

- **登录沿用微信静默登录**（`code2session` + JWT），不新增授权弹窗；昵称头像为可选补充，
  不阻塞浏览与点单。登录响应新增 `avatarUrl`，启动即可展示头像（少一次往返）。
- **头像必须走上传**：`chooseAvatar` 返回的是本机临时路径（`http://tmp/…` / `wxfile://…`），
  只当次运行有效。`CustomerService.updateAvatarKey` 用形状白名单
  `[A-Za-z0-9_][A-Za-z0-9/_.\-]*` 拒绝任何含 scheme 或路径穿越的 key——
  这是从结构上堵住「临时路径入库」，而不是靠调用方自觉。
- 对象 key 前缀 `avatar/`（与菜单图片的 `menu/` 分开目录），便于按用途排查与清理。
- **上传失败必须保留旧头像**：顺序固定为「先转存成功 → 再改库」。
- 身份一律取自 `AuthContext`（JWT），**不从请求参数取 customerId**（否则可改他人资料）。

## 7. 视觉体系（奶油暖调极简）

- **为什么是 CSS 自定义属性而不是 SCSS 变量**：项目未安装 `sass`，页面样式全是原生
  `<style>`（无 `lang="scss"`），`uni.scss` 里的变量从未被编译过。改用自定义属性后：
  - 页面写 `var(--c-primary)`，不再出现硬编码 hex；
  - **无障碍高对比模式只需在 `.a11y-mode` 上重定义同一批变量名**，整棵子树自动切换配色
    ——旧的逐条 `!important` 覆盖被彻底取代，且不会再出现「漏覆盖一个色值」。
- 取值来源：`src/styles/tokens.ts`（TS 常量，供 tabBar 等程序化场景使用）；
  CSS 落地点：`App.vue` 的 `page { --c-*: … }`。改配色先改前者再同步后者。
- 色板：主色焦糖暖棕 `#C8845A`（亮 `#E8A87C` / 暗 `#A9653F`）、米白奶油底 `#FFFBF5`、
  暖色分区 `#FBF3E8`、主文字 `#3D3229`、辅助 `#8C8177`；
  语义色：成功 `#7BA05B`、警示 `#D98E4A`、危险 `#C25B4E`。
- 半径与间距：卡片 `32rpx`、标签/输入 `20rpx`、胶囊 `999rpx`；页面边距 `32rpx`、卡片间距 `24rpx`。
- tabBar 由三项改为四项（菜单 / AI 点单 / 订单 / 我的），配色取 token，
  仍保持**纯文字**（仓库内没有正式图标资源，塞一个不匹配的占位图不如让文字承担导航）。
- 交互：可点元素统一 `120ms` 缓动 + 轻微下移 2rpx；无障碍模式下动效退化为静态。
- 旧色值清零：`#409eff / #f5f6f8 / #303133 / #909399 / #e6a23c` 等 15 个旧值在
  `src` 下已无残留（12 个文件、122 处替换为 token）。

## 8. 配置项与环境变量（增量）

### 8.1 `application-dev.yml` 新增段

```yaml
ai:
  stream:
    timeout-seconds: 20                 # 一轮对话的整体超时（看门狗）
    first-delta-gate-millis: 2000       # 首字闸门：等承接话术首增量最多多久
    pool-size: 8                        # 编排线程池（一轮对话占 2 个线程）
    queue-capacity: 32                  # 有界等待队列；满了直接拒绝并发 error，不让请求排到超时

jev:
  enabled: true                         # 总开关；真正发请求还要求 api-key 非空
  base-url: https://api.typesafe.ai
  api-key: "${TYPESAFE_API_KEY:}"       # 密钥只走环境变量，不入库不入前端
  path: /v1/systemone
  model: jev-1.13.0                     # 钉版本号，不用 jev-latest 别名
  connect-timeout-seconds: 3
  timeout-seconds: 8
  max-state-tokens: 6000
  confidence-threshold: 0.72
  retry-max-attempts: 3                 # 429/529/超时的最大尝试次数
  retry-base-millis: 1000
  circuit-failure-threshold: 5
  circuit-open-seconds: 60

preference:
  min-sample-orders: 3                  # 认定所需最少订单数（分母按订单不按杯）
  mode-ratio: 0.6                       # 认定所需频次占比
  window-orders: 50                     # 参与聚合的最近订单数上限
  stale-hours: 24                       # 惰性补算的过期时长
```

### 8.2 环境变量（`backend/.env`，模板见 `.env.example`）

| 变量 | 说明 |
| --- | --- |
| `TYPESAFE_API_KEY` | Jev 决策模型密钥。留空 → 决策层自动停用并退回既有 GLM 对话链路 |
| `JEV_ENABLED` | 决策层开关（默认 `true`；置 `false` 等价于未启用，可随时灰度回滚） |
| `JEV_MODEL` | 可选：钉住模型版本（默认 `jev-1.13.0`） |
| `AI_STREAM_TIMEOUT_SECONDS` | 可选：流式整体超时（默认 20） |
| `AI_STREAM_FIRST_DELTA_GATE_MS` | 可选：首字闸门（默认 2000） |
| `AI_STREAM_POOL_SIZE` | 可选：编排线程池大小（默认 8） |

**密钥不入库、不进日志**：决策日志只记 confidence、耗时、token 用量与商品编码，
不记顾客原话与昵称。

### 8.3 数据库迁移

`V9__customer_profile_preference.sql`（配套回滚 `V9_customer_profile_preference_rollback.sql`）：

- `customer` 增列 `avatar_key VARCHAR(255)`；
- 新建 `customer_preference`（唯一键 `uk_customer`，含 `option_names_json` / `favorite_product_name`
  / `sample_orders` / `source` / `built_at`）。

## 9. 测试与验收

### 9.1 自动化测试（`mvnw.cmd test`，共 92 个用例全绿）

| 测试类 | 守什么 |
| --- | --- |
| `JevClientTest`（8） | 请求形状、401/422 不重试、429 退避、529 熔断且**不发请求**、半开恢复、超时 |
| `MenuStateAssemblerTest`（8） | 全量注入、**menuBlock/preferenceBlock 零 CJK**、下标与菜单顺序一致、偏好注入、草稿编码、超预算截断、字典未命中显式降级 |
| `JevDecisionProviderTest`（10） | 命中还原中文名、state 与 ASCII 题名、**幻觉编码拒绝**、最小置信度聚合、意图/澄清/规格缺失/越界全部拒绝、加料多选、空菜单短路 |
| `DecisionOrchestratorTest`（9） | 未启用短路、命中落草稿并**回读验证**、落库失败转 GLM、链路不可用降级、**不持有 chat model** |
| `PreferenceServiceTest`（13） | 只认已成交三态、按订单不按杯、组内分母、阈值边界、幂等、新鲜不重算、视图空态、商品改名取当前名 |
| `PreferenceRefreshListenerTest`（7） | PAID/COMPLETED 触发、未付/关单/作废不触发、PICKUP_READY 不重复、柜台单跳过、失败吞掉 |
| `AiStreamOrchestratorTest`（7） | 事件序列与**首字闸门**、降级不伪造流式、**看门狗超时收尾**、1008 兜底话术、1009/403 收尾、偏好失败不影响整轮 |
| `CustomerProfileServiceTest`（9） | 昵称归一与拒绝、**临时路径/绝对路径拒绝**、`avatar/` 前缀、上传失败保留旧头像、登录带 avatarUrl |

既有 4 个测试类（含 `StartupWiringTest`）继续通过——新增的 `ChatNarrator` **不带 `@MemoryId`**，
因此「带 `@MemoryId` 的 AiService 恰好 2 个」这条装配断言仍然成立。

### 9.2 端到端验收口令

```bash
# 1) 后端单测
cd backend && mvnw.cmd test                       # 期望 BUILD SUCCESS（92 tests）

# 2) 起服务（后端 + 可选本地基础设施）
cd docker && docker compose up -d --build
curl http://127.0.0.1:8080/actuator/health

# 3) 回归既有主链路（零破坏）
python scripts/smoke.py                           # 期望 M1_GATE_PASS

# 4) 本轮增量验收
python scripts/ai-smoke.py                        # 期望 AI_GATE_PASS

# 5) 小程序端
cd miniprogram && npm run type-check && npm run build:mp-weixin
```

`ai-smoke.py` 覆盖：新客无偏好 → 非流式对话 → 草稿转订单 → 支付 → **偏好由订单事件隐式聚合**
→ 流式事件（stage/delta/decision/card/done）与空行分帧 → error 事件带业务码。
需要 `AI_API_KEY`（GLM）才可能全绿；`AI_SMOKE_SKIP_AI=1` 可只验接口连通与降级口径。

### 9.3 降级链专项（逐格实测）

| 场景 | 构造方式 | 期望 |
| --- | --- | --- |
| Jev 正常 | 真实 key | 裁决 → 落草稿 → `decision(JEV)` + `card` |
| 网络不可达 | `JEV_BASE_URL=http://127.0.0.1:1` | 退避后降级 GLM，**不报 1008**，用户无感 |
| 认证/校验失败 | 错误 key（401）/ 非法题面（422） | 不重试，立即降级 |
| 限流 | 桩返回 429 | 退避重试；始终失败则降级 |
| 过载 | 桩持续 529 | 熔断打开，冷却期内不发请求；半开探测 |
| 未配密钥 | `TYPESAFE_API_KEY` 留空 | 决策层停用（`DISABLED`，与「降级」分开记录），走 GLM 链路 |
| 表述层失败 | 关闭 GLM 配置 | 流式推 `error(1008)` + `fallbackText`，前端展示降级横幅 |
| 双通道全挂 | Jev 与 GLM 均不可用 | 手动点单链路（菜单/购物车/支付）完全可用 |
| SSE 不可用 | 基础库 < 2.20.0 | 前端自动回退非流式接口，功能不缺失 |
| 编排超时 | 决策分支 sleep 25s | 20s 推 `error` 并 `complete`，无悬挂连接 |

### 9.4 小程序端人工走查清单

1. AI 页首字延迟 ≤1s 出现承接话术；阶段三段逐段点亮；草稿卡到达后语音播报，
   改量/删项/纠正规格均生效；
2. 模糊需求（如「随便来一杯」）走澄清反问，而不是猜错直接下单；
3. 复杂需求（如「上一杯换成少冰」「退掉那杯」）行为与线上一致；
4. 我的页：头像选择 → 上传 → 回显，杀进程重进仍在；昵称保存生效；口味档案有值态与空态均正常；
5. tabBar 四项切换无白屏，底部输入与安全区不被遮挡；
6. 无障碍模式：字号放大与高对比生效，且放大后 AI 流式气泡与草稿卡仍可读；
7. 语音播报开关关闭后，`card` 不再播报；
8. 视觉回归：`grep -rn "#409eff\|#f5f6f8\|#303133" miniprogram/src` 零命中。

## 10. AC 增量清单

| 编号 | 验收项 | 判定 |
| --- | --- | --- |
| AC-16 | 决策层接入且可回滚 | `JEV_ENABLED=false` 或密钥留空时，AI 点单行为与升级前一致 |
| AC-17 | 决策结果不经过模型手 | 草稿金额与 `PricingService` 直接计算结果逐项一致（单测断言） |
| AC-18 | 幻觉编码被拒绝 | 模型返回菜单外商品编码时**不落草稿**（单测断言） |
| AC-19 | 语言纪律 | `menuBlock` / `preferenceBlock` 零 CJK 字符（单测断言） |
| AC-20 | 流式事件契约 | 收到 `stage/delta/decision/card/done`，且以空行正确分帧 |
| AC-21 | 首字优先 | `delta` 事件先于 `decision` / `card` 到达 |
| AC-22 | 超时必收尾 | 整体超时后推 `error` 并关闭连接，无悬挂连接 |
| AC-23 | 不伪造流式 | GLM 链路回复整段下发（单测断言只有一条 `delta`） |
| AC-24 | 偏好只认已成交 | 未付/超时/作废订单不计入（单测断言查询条件与分母口径） |
| AC-25 | 隐私口径 | 偏好不解析对话内容；`source` 恒为 `INFERRED` |
| AC-26 | 头像必须转存 | 拒绝 `wxfile://` / `http(s)://` / 路径穿越形式的 key（单测断言） |
| AC-27 | 登录不阻塞 | 不填昵称头像亦可完整下单（沿用静默登录，无强制授权） |
| AC-28 | 无障碍无损 | 高对比模式仍生效，且放大后流式气泡与草稿卡可读 |
| AC-29 | 视觉 token 化 | `src` 下旧 Element 色值零残留（grep 断言） |
| AC-30 | 既有主链路零破坏 | `scripts/smoke.py` 仍输出 `M1_GATE_PASS` |

## 11. 独立评审的处理记录

对本轮的决策层与流式编排做了一次独立评审（`code-reviewer`），以下是**已修**与**已知未修**。

### 11.1 已修

| 问题 | 处理 |
| --- | --- |
| 线程池用 `Executors.newFixedThreadPool`（无界队列），「队列满即拒绝」是死代码 | 改为 `ThreadPoolExecutor` + `ArrayBlockingQueue` + `AbortPolicy`，容量可配（`ai.stream.queue-capacity`） |
| 端点在会话定位抛非业务异常时会以 JSON 500 逃出，与「响应类型固定是 SSE」矛盾 | `catch (Exception)` 一并转 `error` 事件 |
| `finished` 只挡事件、不挡副作用：超时后决策分支仍会调模型并改草稿 | 决策前与回复前检查 `isFinished()`；并注册 `onCompletion/onTimeout/onError` 以尽早感知客户端断开 |
| `done` 与收尾分两步，看门狗可能插在中间导致先 `done` 后 `error` | 合并为 `finishWith()` 内的同一次 CAS |
| 看门狗线程里 `completeWithError` 抛异常会逃出调度任务体 | `emit` 的收尾统一走 `markFinished()` + 包 try |
| state 预算只累计商品行，规格段膨胀时整体突破预算 | 规格段先计入预算，再按剩余额度截断商品 |
| 「启用但选项全被停用」的规格组会生成空 criteria 的单选题（可能 422 → 对全体顾客降级） | 该组直接从决策上下文剔除并 WARN |
| `MAX_GROUPS` 触顶静默丢组，会造成商品引用悬空 code | 同样打 WARN |
| 头像 key 白名单会放过 `..` 与以点开头的段 | 收紧为「点只能出现在扩展名前」 |
| 回读校验不比数量 | 加上数量比对 |
| 命中但卡片退化为 TEXT 时，话术仍让顾客去点「立即支付」 | 该情形改用不含卡片指代的话术 |
| 只有 `ResourceAccessException` 计入不可用，其余传输异常穿透（不计熔断、不重试） | 补 `catch (RestClientException)` |
| 监听器在 `afterCommit` 业务线程里查库 | 查库移入异步任务体 |

### 11.2 已知未修（需单独立项）

1. **同会话并发写草稿会丢更新**（评审判定为严重，但属**既有设计**）：`SessionService` 的草稿是
   「按 `session_uuid` 整份覆盖写」，`DraftOrderTool` 走「读—改—写」，没有版本号或行锁。
   流式端点降低了「上一轮没结束就发下一句」的门槛，把这个窗口暴露得更明显。
   **建议的修复**：给 `ai_session` 加 `draft_version` 列做 CAS（`UPDATE … WHERE session_uuid=? AND draft_version=?`），
   或把草稿变更改成按条目的原子 SQL。本轮未做，因为它会同时改动 **GLM 工具链**的写路径，
   在缺少并发集成测试的环境下贸然修改，风险高于收益。
   **当前缓解**：前端在 `sending` 期间禁用发送按钮；单顾客单设备的正常使用不会命中该窗口。
2. **偏好 `snapshot()` 在决策分支内同步执行**：行过期/不存在时会同步触发一次 `rebuild`（两次扫描 + upsert），
   新客首轮必然吃到这份开销。后续可改为「读路径只读 + 事件/定时负责重算」，或对 `rebuild` 做同顾客单飞 + 异步。
3. **隐私**：`state` 里包含顾客原话原文并发送给第三方跨境端点，无脱敏（这是「让模型听懂人话」的前提）。
   若合规上不接受，可改为「只发送结构化意图、不发送原话」，代价是复杂需求的理解会变差。
4. **偏好刷新无同顾客节流**：用餐高峰的连续订单会排出多次冗余重算（每次都是幂等的）。

## 12. 已知限制与后续路径

1. **Jev 是境外闭源服务**：跨境调用存在网络抖动风险，已用「超时 + 退避 + 熔断 + 降级」
   四层兜底；若长期不稳，`DecisionProvider` 接口已把实现隔离，可平滑换成其它决策后端。
2. **CJK 精度**：`MenuLabelDictionary` 只覆盖种子菜单；店主新增商品/规格后会走中文回落
   （`degraded=true` + WARN，不静默出错）。后续按第 3.3 节的升级路径补 `label_en` 列。
3. **偏好冷启动**：新客没有任何默认偏好（这是刻意的口径，不是缺陷）。
4. **口径变更需先改测试**：偏好阈值（`min-sample-orders` / `mode-ratio`）与置信度阈值
   都有对应单测；调参时应同步更新用例中的期望值，避免「配置改了但验收口径没跟上」。
5. **未做图标资源**：tabBar 仍为纯文字；补图标时只需改 `pages.json` 四处 `iconPath`。
