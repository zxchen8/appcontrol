# Android 自动化测试控制 App 详细设计 v0.1

## 1. 文档定位

本文档用于定义关键对象、任务 DSL、执行语义、变量与凭据模型、错误码和运行记录结构，作为核心实现的直接输入。

本文件同时作为 DSL 字段语义、执行冲突处理、连续运行规则、运行记录结构和本地存储模型的唯一主源。

文档边界如下：

- 系统整体架构见 [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md)
- 项目约束见 [DEVELOPMENT_RULES.md](../rules/DEVELOPMENT_RULES.md)

## 2. 任务定义模型

### 2.1 顶层结构

任务使用 JSON 定义，首版固定 `schemaVersion = 1.0`。

```json
{
  "schemaVersion": "1.0",
  "taskId": "daily-login-check",
  "name": "每日登录校验",
  "description": "验证目标 App 启动到登录完成的固定流程",
  "enabled": true,
  "targetApp": {
    "packageName": "com.example.target",
    "launchActivity": null
  },
  "trigger": {
    "type": "cron",
    "expression": "*/30 * * * *",
    "timezone": "Asia/Shanghai"
  },
  "accountRotation": null,
  "executionPolicy": {
    "taskTimeoutMs": 300000,
    "maxRetries": 1,
    "retryBackoffMs": 5000,
    "conflictPolicy": "skip",
    "onMissedSchedule": "skip"
  },
  "preconditions": [
    {
      "type": "root_ready"
    }
  ],
  "variables": {},
  "steps": [],
  "diagnostics": {
    "captureScreenshotOnFailure": true,
    "captureScreenshotOnStepFailure": true,
    "logLevel": "info"
  },
  "tags": ["login", "regression"]
}
```

### 2.2 顶层字段

- `schemaVersion`：任务格式版本，必填
- `taskId`：任务唯一标识，必填，建议只使用小写字母、数字和中划线
- `name`：任务名称，必填
- `description`：任务描述，选填
- `enabled`：是否启用，必填
- `targetApp`：目标 App 定义，必填
- `trigger`：触发方式，必填
- `accountRotation`：连续运行账号轮换配置，选填
- `executionPolicy`：任务级执行策略，必填
- `preconditions`：执行前置条件，选填
- `variables`：运行时变量映射，选填
- `steps`：步骤列表，必填
- `diagnostics`：诊断策略，选填
- `tags`：任务标签，选填

补充约束：

- `trigger.type` 首版支持 `cron` 和 `continuous`
- `trigger.type = cron` 时，v1 先使用分钟级 5 段表达式
- 秒级调度不纳入首版范围
- `trigger.type = continuous` 时，任务以连续运行会话方式执行多轮
- `accountRotation` 仅允许在 `trigger.type = continuous` 时启用
- `executionPolicy.maxRetries` 表示任务首次失败后的额外重试次数，不包含首次执行

### 2.2a `preconditions` 类型枚举

`preconditions` 数组中每个条目的 `type` 字段的首版合法值如下，与环境检查页的检查项一一对应：

| `type` 值 | 含义 | 对应环境检查项 |
|----------|------|-------------|
| `root_ready` | root shell 可执行 | root shell 是否可执行 |
| `accessibility_enabled` | `AccessibilityService` 已启用 | AccessibilityService 是否已启用 |
| `notification_permission` | 前台服务通知权限已授予 | 前台服务通知是否可正常展示 |
| `target_app_installed` | 目标 App 已安装且可拉起 | 目标 App 是否已安装且可拉起 |
| `android_version` | 设备系统版本为 Android 9（API 28）或 Android 10（API 29） | 当前设备系统版本检查 |

补充约束：

- 若某个前置条件检查失败，执行引擎必须记录 `PRECONDITION_FAILED` 错误码并阻断任务启动
- `preconditions` 为空或未配置时，跳过前置条件检查，直接执行任务
- 首版不支持自定义前置条件类型

### 2.3 触发方式

#### cron

```json
{
  "trigger": {
    "type": "cron",
    "expression": "*/30 * * * *",
    "timezone": "Asia/Shanghai"
  }
}
```

字段说明：

- `expression`：分钟级 5 段 cron 表达式
- `timezone`：调度时区

#### continuous

```json
{
  "trigger": {
    "type": "continuous",
    "cooldownMs": 5000,
    "maxCycles": null,
    "maxDurationMs": null
  }
}
```

字段说明：

- `cooldownMs`：本轮结束到下一轮开始之间的冷却时间，必填
- `maxCycles`：最多运行轮次，选填，未配置表示直到手动停止或任务禁用
- `maxDurationMs`：本次连续运行会话的最长持续时间，选填

补充约束：

- 连续运行模式只用于 dedicated 测试机
- 连续运行模式下一轮启动以“上一轮结束时间 + cooldownMs”为准
- 达到 `maxCycles` 或 `maxDurationMs` 后必须停止后续轮次

### 2.4 账号轮换配置

```json
{
  "accountRotation": {
    "credentialSetId": "smoke-set-a",
    "strategy": "round_robin",
    "persistCursor": true,
    "onCycleFailure": "continue_next"
  }
}
```

字段说明：

- `credentialSetId`：本地账号组标识，必填
- `strategy`：账号轮换策略，首版仅支持 `round_robin`
- `persistCursor`：是否持久化当前轮换游标，选填，默认 `true`
- `onCycleFailure`：单轮失败后的处理策略，首版支持 `continue_next` 和 `stop_session`，默认 `continue_next`

补充约束：

- 账号切换只允许发生在轮次边界，同一轮任务只允许使用一个账号
- 首版不支持随机轮换、加权轮换或按账号标签过滤
- 若启用账号轮换，任务步骤必须保证下一轮开始前能回到可重复执行的初始态
- `persistCursor = true` 时，系统应在每轮结束后持久化下一账号游标，并在连续运行恢复时继续使用
- `persistCursor = false` 时，每次新的连续运行会话都从账号组中的第一个账号开始
- 首版不支持因失败次数自动摘除、冻结或跳过某个账号；账号是否保留在轮换中仅由用户配置决定

### 2.5 `executionPolicy` 关键字段语义

- `taskTimeoutMs`：单次任务运行超时时间
- `maxRetries`：任务首次失败后的额外重试次数
- `retryBackoffMs`：任务级重试退避时间
- `conflictPolicy`：任务因目标 App 执行锁被占用而无法立即启动时的处理策略
- `onMissedSchedule`：任务因设备离线、服务未运行或恢复后错过计划窗口时的处理策略

首版 `conflictPolicy` 支持：

- `skip`：本次触发直接跳过，并记录冲突日志
- `run_after_current`：等待当前已运行的任务实例自然结束后立即执行一次，但不允许中断正在执行的实例，也不累计排队多次执行

补充约束：

- `conflictPolicy` 只处理“目标 App 执行锁被占用”的场景，不等同于 `onMissedSchedule`
- `run_after_current` 首版主要用于 cron 任务与当前 continuous 任务实例冲突时的补偿执行
- continuous 任务本身不建议使用 `run_after_current` 形成排队；连续任务默认在目标 App 空闲后再进入下一轮


## 3. 变量与凭据模型

### 3.1 设计原则

- 任务文件只保存变量引用，不保存账号密码明文
- 变量值在运行时由本地变量库或凭据配置解析
- 敏感字段必须标记并以受控方式读取

### 3.2 变量定义

以下示例展示连续运行账号轮换场景；单账号任务仍可继续使用 `credential_profile + profileId` 的固定引用方式。

```json
{
  "variables": {
    "ACCOUNT_USERNAME": {
      "source": "active_credential",
      "field": "username"
    },
    "ACCOUNT_PASSWORD": {
      "source": "active_credential",
      "field": "password",
      "sensitive": true
    }
  }
}
```

### 3.3 变量字段

- `source`：变量来源，首版支持 `credential_profile`、`active_credential` 和 `literal`
- `profileId`：本地凭据配置标识；仅 `source = credential_profile` 时必填
- `field`：引用字段名，例如 `username`、`password`
- `sensitive`：是否为敏感变量

补充约束：

- `literal` 仅用于非敏感常量
- 账号、密码等敏感变量必须通过 `credential_profile` 或 `active_credential` 提供
- `active_credential` 表示当前轮次选中的账号，仅在 `trigger.type = continuous` 且启用了 `accountRotation` 的任务中可用
- 若 `source = active_credential` 出现在 `trigger.type = cron` 的任务中，DSL 解析阶段必须报 `DSL_VALIDATION_ERROR`，任务不得启动

### 3.4 凭据存储实现约束

- `CredentialProfile` 元数据、标签和索引信息存储在 Room 中
- `CredentialSet` 元数据和账号顺序关系存储在 Room 中
- 敏感字段在写入本地存储前必须加密，加密密钥由 Android Keystore 保护
- DataStore 不用于账号、密码等敏感凭据存储
- 本地凭据只允许测试账号，不允许使用生产账号
- rooted 测试设备上的凭据应支持快速替换与轮换

安全边界说明：

- 在 rooted 设备上，Room + Keystore 只能降低误泄露风险，不能视为强安全边界
- 文档中的凭据保护目标是降低误用和误配风险，而不是抵御拥有设备控制权的攻击者

### 3.5 本地账号数据模型建议

`CredentialProfile` 建议至少包含：

- `profileId`
- `alias`
- `tags`
- `enabled`
- `createdAt`
- `updatedAt`

说明：

- `alias` 用于界面展示和运行记录回看，不直接暴露敏感字段
- 敏感字段值仍通过加密存储，不直接作为列表字段展示

`CredentialSet` 建议至少包含：

- `credentialSetId`
- `name`
- `description`
- `strategy`
- `enabled`
- `createdAt`
- `updatedAt`

`CredentialSetItem` 建议至少包含：

- `credentialSetId`
- `profileId`
- `orderNo`
- `enabled`

补充约束：

- 同一个 `CredentialSet` 内 `orderNo` 必须唯一且连续可排序
- 同一个 `CredentialSet` 内同一个 `profileId` 首版不允许重复出现
- 连续运行任务在启动前必须确保目标 `CredentialSet` 至少包含一个启用账号
- 账号组顺序变更必须显式持久化，不依赖临时内存顺序

### 3.6 Room 实体建议

首版建议至少定义以下本地实体：

- `TaskDefinitionEntity`：保存任务基础元数据和原始 JSON 配置
- `TaskScheduleStateEntity`：保存任务启停状态、下一次触发时间、当前待命状态和最近一次调度结果
- `CredentialProfileEntity`：保存账号元数据、别名、标签和启停状态
- `CredentialSecretEntity`：保存账号敏感字段的加密载荷、加密版本和更新时间
- `CredentialSetEntity`：保存账号组元数据和轮换策略
- `CredentialSetItemEntity`：保存账号组成员及其顺序
- `ContinuousSessionEntity`：保存连续运行会话状态、轮换游标和当前/下一账号信息
- `TaskRunEntity`：保存每一轮任务执行记录
- `StepRunEntity`：保存步骤级执行记录

首版字段建议：

- `TaskDefinitionEntity` 至少包含 `taskId`、`name`、`enabled`、`triggerType`、`rawJson`、`updatedAt`
- `TaskScheduleStateEntity` 至少包含 `taskId`、`nextTriggerAt`、`standbyEnabled`、`lastTriggerAt`、`lastScheduleStatus`
- `CredentialSecretEntity` 至少包含 `profileId`、`encryptedPayload`、`encryptionVersion`、`updatedAt`
- `ContinuousSessionEntity` 和 `TaskRunEntity` 字段应与 §8.1、§8.2 中定义的记录结构保持一致

补充约束：

- 任务原始 JSON 必须保留，不能只保留解析后字段，以支持原始编辑和回放排障
- `CredentialProfileEntity` 与 `CredentialSecretEntity` 应通过 `profileId` 一对一关联
- `CredentialSetEntity` 与 `CredentialSetItemEntity` 应通过外键或等效约束维持一致性
- 删除账号或账号组前必须先校验是否仍被任务或连续运行会话引用

### 3.7 DAO 与 Repository 接口建议

首版建议至少定义以下 DAO：

- `TaskDefinitionDao`：任务列表读取、任务原始配置保存、启停状态更新
- `TaskScheduleStateDao`：调度状态更新、下一次触发时间写回、待命状态维护
- `CredentialProfileDao`：账号元数据读写、别名查询、启用状态管理
- `CredentialSetDao`：账号组及其成员的事务化写入与顺序读取
- `ContinuousSessionDao`：连续运行会话创建、状态更新、游标推进和恢复
- `TaskRunDao`：任务运行记录写入、最近运行查询、账号维度聚合查询
- `StepRunDao`：步骤记录批量写入和按运行编号查询

关键事务边界建议：

- 更新一个 `CredentialSet` 时，必须在同一事务中更新 `CredentialSetEntity` 与全部 `CredentialSetItemEntity`
- 连续运行进入下一轮时，必须在同一事务中完成游标推进、`ContinuousSessionEntity` 更新和新 `TaskRunEntity` 创建
- 任务结束时，运行记录状态回写和会话统计字段更新应在同一事务内完成

首版建议至少定义以下 Repository 接口：

- `TaskRepository`：面向 UI 和调度层提供任务定义、启停和原始 JSON 读写能力
- `CredentialRepository`：提供账号、账号组、当前轮换账号解析和引用校验能力
- `SessionRepository`：提供连续运行会话创建、恢复、停止和游标推进能力
- `RunRecordRepository`：提供运行记录写入、运行中状态读取和账号维度聚合查询能力

边界约束：

- UI、调度器和执行引擎不得直接依赖 Room Entity 或 DAO
- Repository 对外返回领域模型或专用查询结果，不直接暴露数据库实现细节
- 账号敏感字段解密只允许在 `CredentialRepository` 或等效受控模块中发生

## 4. 步骤模型

### 4.1 通用结构

```json
{
  "id": "step-open-app",
  "type": "start_app",
  "name": "启动目标应用",
  "timeoutMs": 15000,
  "retry": {
    "maxAttempts": 1,
    "backoffMs": 1000
  },
  "onFailure": "stop_task",
  "params": {}
}
```

### 4.2 通用字段

- `id`：步骤唯一标识，必填
- `type`：步骤类型，必填
- `name`：步骤名称，选填
- `timeoutMs`：步骤超时，必填
- `retry`：步骤级重试配置，选填，未配置时使用系统默认值
- `onFailure`：失败策略，选填，未配置时使用系统默认值；合法值见 §4.3
- `params`：步骤参数，必填

默认值建议：

- `retry.maxAttempts = 1`
- `retry.backoffMs = 1000`
- `onFailure = stop_task`

补充语义：

- `retry.maxAttempts` 表示总尝试次数，包含首次执行
- 因此 `retry.maxAttempts = 1` 表示不做额外重试

### 4.3 失败策略

首版支持：

- `stop_task`：步骤失败后立即终止本次任务实例
- `continue`：步骤失败后记录错误并继续执行后续步骤
- `retry_task`：步骤失败后从头重新执行整个任务实例，受 `executionPolicy.maxRetries` 上限约束

实现建议：

- 首版任务编写默认只使用 `stop_task`
- `continue` 与 `retry_task` 作为高级策略保留，但不要求成为首批任务模板的默认写法

**任务级重试与步骤级失败策略优先级说明：**

- 步骤的 `onFailure` 先于任务级 `executionPolicy.maxRetries` 生效
- 若 `onFailure = stop_task`，任务实例终止；随后调度器依据 `maxRetries` 决定是否重新发起一次新的任务实例
- 若 `onFailure = retry_task`，执行引擎直接在当前实例内重启整个步骤序列，不消耗 `maxRetries` 计数；只有当步骤重试本身也失败并最终触发实例终止时，才进入 `maxRetries` 流程
- `onFailure = continue` 时任务实例不终止，`maxRetries` 不被触发
- 两者不可同时形成循环：`onFailure = retry_task` 的步骤必须保证在有限次数内终止

### 4.4 选择器与目标定位结构

`selector` 和 `target` 参数均使用以下统一 JSON 结构，通过 `type` 字段区分定位方式。

#### 元素定位（`selector` / `target`）

首版支持以下 `type` 值：

| `type` | 说明 | 必填字段 |
|--------|------|---------|
| `resource_id` | 按控件 resourceId 定位 | `value` |
| `text` | 按控件显示文本完全匹配定位 | `value` |
| `text_contains` | 按控件显示文本包含匹配定位 | `value` |
| `content_desc` | 按 contentDescription 定位 | `value` |
| `xpath` | 按无障碍节点 XPath 定位 | `value` |
| `coordinate` | 按绝对坐标定位（仅作为兜底方案） | `x`、`y` |

示例：

```json
{ "type": "resource_id", "value": "com.example.app:id/btn_login" }
{ "type": "text", "value": "立即登录" }
{ "type": "content_desc", "value": "关闭弹窗" }
{ "type": "coordinate", "x": 540, "y": 1200 }
```

#### 滑动端点结构（`from` / `to`）

`swipe` 步骤的 `from` 和 `to` 字段使用坐标结构：

```json
{ "x": 540, "y": 1600 }
```

#### 约束

- 首版必须优先使用元素定位（`resource_id`、`text`、`content_desc`）；只有在元素定位无可行路径时才允许降级为 `coordinate`
- `coordinate` 类型不允许用于敏感操作（如密码输入确认）
- 多个字段可叠加为数组，执行时按顺序尝试直到第一个成功（后续增强能力，首版不要求）

## 5. 步骤类型

- 首批实现步骤：`start_app`、`stop_app`、`tap`、`swipe`、`input_text`、`wait_element`
- 首批组合能力：`restart_app`
- 后续增强步骤：`image_find`、`ocr_find`、`capture_screenshot`

### 5.1 `start_app`

启动目标 App。

关键参数：

- `packageName`

### 5.2 `stop_app`

强制停止目标 App。

关键参数：

- `packageName`

### 5.3 `restart_app`

停止后重新启动目标 App。

该能力在首版不要求独立执行器，可由 `stop_app` 与 `start_app` 组合实现。

关键参数：

- `packageName`
- `waitAfterStopMs`

### 5.4 `tap`

点击元素、文本、图像或坐标目标。

关键参数：

- `target`：定位目标，结构见 §4.4

### 5.5 `swipe`

执行滑动动作。

关键参数：

- `from`：滑动起点坐标，结构见 §4.4
- `to`：滑动终点坐标，结构见 §4.4
- `durationMs`：滑动持续时间

### 5.6 `input_text`

向当前焦点或指定元素输入文本。

关键参数：

- `selector`：目标元素定位，结构见 §4.4；选填，若省略则向当前焦点输入
- `text`：字面量文本
- `textRef`：任务变量引用名
- `clearBeforeInput`：输入前是否先清空当前内容，默认 `false`

首版允许直接字面量输入，也允许通过 `textRef` 引用任务变量。

补充约束：

- `text` 与 `textRef` 必须二选一
- 账号、密码等敏感输入必须使用 `textRef`
- 当 `textRef` 被使用时，变量必须在任务运行前成功解析
- 首版优先保证测试账号、密码和常见 ASCII/符号输入；复杂 IME、中文整句输入作为后续增强处理

### 5.7 `wait_element`

等待元素出现或消失。

关键参数：

- `selector`：目标元素定位，结构见 §4.4
- `state`：等待的目标状态，合法值如下：

| `state` 值 | 说明 |
|-----------|------|
| `visible` | 等待元素出现在界面树中且可见 |
| `gone` | 等待元素从界面树中消失或不可见 |
| `enabled` | 等待元素出现且处于可交互（enabled）状态 |
| `exists` | 等待元素出现在界面树中（不要求可见） |

### 5.8 `image_find`

在当前屏幕中查找模板图像。

该步骤属于后续增强能力，不纳入第一批实现。

关键参数：

- `templateId`
- `minScore`

模板图像由开发或测试手工维护。

### 5.9 `ocr_find`

识别当前屏幕中的指定文本。

该步骤属于后续增强能力，默认不纳入第一批实现。

关键参数：

- `text`
- `minConfidence`

约束：

- 只允许绑定本地 OCR 能力
- 若运行环境未启用 OCR，必须返回 `STEP_CAPABILITY_UNAVAILABLE`

### 5.10 `capture_screenshot`

主动截取当前屏幕并保存为诊断产物。

该步骤属于后续增强能力，不纳入第一批实现。首版只要求失败自动截图。

## 6. 错误码

### 6.1 设计原则

- 所有错误码使用大写下划线格式，按模块前缀分组
- 错误码必须唯一，不允许两个不同情形映射到同一错误码
- 日志中必须包含错误码和结构化上下文，不允许只抛出字符串
- 错误码在 `StepRunEntity`、`TaskRunEntity` 和 `ContinuousSessionEntity` 的结果字段中使用

### 6.2 错误码表

#### DSL 与配置类（前缀 `DSL_`）

| 错误码 | 含义 |
|-------|------|
| `DSL_PARSE_ERROR` | JSON 解析失败，任务格式非法 |
| `DSL_VALIDATION_ERROR` | JSON 格式合法但字段语义不合法（如 `active_credential` 用于 cron 任务） |
| `DSL_SCHEMA_UNSUPPORTED` | `schemaVersion` 不被当前解析器支持 |
| `DSL_MISSING_REQUIRED_FIELD` | 必填字段缺失 |

#### 前置条件类（前缀 `PRECONDITION_`）

| 错误码 | 含义 |
|-------|------|
| `PRECONDITION_FAILED` | 前置条件检查失败（通配，具体原因在 message 中补充） |
| `PRECONDITION_ROOT_UNAVAILABLE` | root shell 不可用 |
| `PRECONDITION_ACCESSIBILITY_DISABLED` | AccessibilityService 未启用 |
| `PRECONDITION_NOTIFICATION_DENIED` | 前台服务通知权限未授予 |
| `PRECONDITION_TARGET_APP_NOT_INSTALLED` | 目标 App 未安装 |
| `PRECONDITION_UNSUPPORTED_ANDROID_VERSION` | 设备 Android 版本不在 API 28/29 范围内 |

#### 步骤执行类（前缀 `STEP_`）

| 错误码 | 含义 |
|-------|------|
| `STEP_TIMEOUT` | 步骤执行超时 |
| `STEP_ELEMENT_NOT_FOUND` | 目标元素在超时前未找到 |
| `STEP_ELEMENT_NOT_VISIBLE` | 目标元素存在但不可见 |
| `STEP_ACTION_FAILED` | 底层动作执行失败（如 root shell 返回非零） |
| `STEP_VARIABLE_RESOLVE_FAILED` | 步骤所需变量无法解析 |
| `STEP_CAPABILITY_UNAVAILABLE` | 所需能力（OCR、图像识别等）未启用或不可用 |
| `STEP_INVALID_PARAMS` | 步骤参数校验失败 |
| `STEP_CANCELLED` | 步骤因任务取消被中断 |

#### 任务执行类（前缀 `TASK_`）

| 错误码 | 含义 |
|-------|------|
| `TASK_TIMEOUT` | 任务整体运行超时（`taskTimeoutMs` 到期） |
| `TASK_CANCELLED` | 任务被用户或调度器主动取消 |
| `TASK_MAX_RETRIES_EXCEEDED` | 任务达到最大重试次数仍失败 |
| `TASK_CREDENTIAL_UNAVAILABLE` | 凭据集中无可用账号 |
| `TASK_LOCK_CONFLICT` | 目标 App 执行锁被占用，且 `conflictPolicy = skip` |

#### 调度类（前缀 `SCHED_`）

| 错误码 | 含义 |
|-------|------|
| `SCHED_MISSED_SCHEDULE` | 调度时间窗口错过，且 `onMissedSchedule = skip` |
| `SCHED_SESSION_LIMIT_REACHED` | 连续运行达到 `maxCycles` 或 `maxDurationMs` 限制 |
| `SCHED_SESSION_STOPPED` | 连续运行会话被手动停止或任务禁用 |

## 7. 执行引擎状态机

### 7.1 任务实例状态

任务实例（`TaskRunEntity`）生命周期状态如下：

```
PENDING
  │
  ▼
RUNNING ──────────────────────┐
  │                           │
  ├── 所有步骤成功 ──────────► SUCCEEDED
  │
  ├── 步骤失败且 onFailure=stop_task ──► FAILED
  │     └── maxRetries 未耗尽 ──► PENDING（新实例）
  │
  ├── taskTimeoutMs 到期 ──────► TIMEOUT
  │
  └── 用户/调度器取消 ────────► CANCELLED
```

合法状态值：`PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`TIMEOUT`、`CANCELLED`

### 7.2 步骤实例状态

步骤实例（`StepRunEntity`）生命周期状态如下：

```
PENDING
  │
  ▼
RUNNING
  │
  ├── 执行成功 ──────────────────► SUCCEEDED
  │
  ├── 执行失败，retry 未耗尽 ──── PENDING（重试）
  │
  ├── 执行失败，retry 耗尽 ──────► FAILED
  │
  ├── 步骤超时（timeoutMs） ─────► TIMEOUT
  │
  └── 任务取消 ─────────────────► CANCELLED
```

合法状态值：`PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`TIMEOUT`、`CANCELLED`

### 7.3 连续运行会话状态

连续运行会话（`ContinuousSessionEntity`）状态如下：

```
ACTIVE ──────────────────────────────────────────► STOPPED（手动停止/任务禁用）
  │
  ├── 达到 maxCycles 或 maxDurationMs ──────────► LIMIT_REACHED
  │
  └── 所有账号均失败且 onCycleFailure=stop_session ► FAILED
```

合法状态值：`ACTIVE`、`STOPPED`、`LIMIT_REACHED`、`FAILED`

### 7.4 执行引擎行为约束

- 执行引擎必须按状态机语义推进状态，不允许跳跃或回退状态
- 任务取消必须在当前步骤完成当前原子操作后生效，不得在 root 命令执行中途强制中断
- 同一目标 App 在同一时刻最多只有一个 `RUNNING` 状态的任务实例（通过执行锁保证）
- 状态变更必须同步写入持久化存储，不允许只保留内存状态

## 8. 运行记录结构

### 8.1 `TaskRunEntity` 完整字段

| 字段名 | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `runId` | String | 是 | 全局唯一运行编号，UUID 格式 |
| `taskId` | String | 是 | 对应任务的 taskId |
| `sessionId` | String | 否 | 所属连续运行会话 ID；cron 任务为 null |
| `cycleNo` | Int | 否 | 本次运行在会话中的轮次序号（从 1 开始）；cron 任务为 null |
| `credentialProfileId` | String | 否 | 本轮实际使用的账号 profileId；未配置账号轮换时为 null |
| `triggerType` | String | 是 | 触发方式：`manual`、`cron`、`continuous` |
| `scheduledAt` | Long | 否 | 计划触发时间（ms 时间戳）；手动执行为 null |
| `startedAt` | Long | 是 | 实际开始时间（ms 时间戳） |
| `endedAt` | Long | 否 | 结束时间（ms 时间戳）；运行中为 null |
| `status` | String | 是 | 任务实例状态，合法值见 §7.1 |
| `errorCode` | String | 否 | 失败时的错误码，合法值见 §6.2 |
| `errorMessage` | String | 否 | 失败时的可读错误描述 |
| `screenshotPath` | String | 否 | 失败截图的本地文件路径 |
| `stepCount` | Int | 是 | 步骤总数 |
| `completedStepCount` | Int | 是 | 成功完成的步骤数 |
| `createdAt` | Long | 是 | 记录创建时间（ms 时间戳） |

### 8.2 `ContinuousSessionEntity` 完整字段

| 字段名 | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `sessionId` | String | 是 | 全局唯一会话 ID，UUID 格式 |
| `taskId` | String | 是 | 对应任务的 taskId |
| `status` | String | 是 | 会话状态，合法值见 §7.3 |
| `currentCycleNo` | Int | 是 | 当前已完成的轮次数（从 0 开始，未开始时为 0） |
| `nextCredentialIndex` | Int | 是 | 账号组游标（下一轮使用的账号索引）；未配置账号轮换时为 0 |
| `currentCredentialProfileId` | String | 否 | 当前轮次使用的账号 profileId |
| `startedAt` | Long | 是 | 会话开始时间（ms 时间戳） |
| `lastCycleEndedAt` | Long | 否 | 最近一轮结束时间（ms 时间戳）；未开始时为 null |
| `nextCycleScheduledAt` | Long | 否 | 下一轮计划开始时间（ms 时间戳）；等待中时有效 |
| `endedAt` | Long | 否 | 会话结束时间（ms 时间戳）；进行中为 null |
| `stopReason` | String | 否 | 会话终止原因：`manual`、`limit_reached`、`task_disabled`、`all_failed` |
| `totalCycles` | Int | 是 | 已执行总轮次数 |
| `successCycles` | Int | 是 | 成功轮次数 |
| `failedCycles` | Int | 是 | 失败轮次数 |
| `createdAt` | Long | 是 | 记录创建时间（ms 时间戳） |

### 8.3 `StepRunEntity` 完整字段

| 字段名 | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `stepRunId` | String | 是 | 全局唯一步骤运行 ID，UUID 格式 |
| `runId` | String | 是 | 所属任务运行记录的 runId |
| `stepId` | String | 是 | 对应步骤的 id 字段 |
| `stepType` | String | 是 | 步骤类型，如 `tap`、`input_text` |
| `stepIndex` | Int | 是 | 步骤在任务中的顺序索引（从 0 开始） |
| `attemptNo` | Int | 是 | 当前执行为第几次尝试（从 1 开始） |
| `startedAt` | Long | 是 | 步骤开始时间（ms 时间戳） |
| `endedAt` | Long | 否 | 步骤结束时间（ms 时间戳）；执行中为 null |
| `status` | String | 是 | 步骤状态，合法值见 §7.2 |
| `errorCode` | String | 否 | 失败时的错误码，合法值见 §6.2 |
| `errorMessage` | String | 否 | 失败时的可读错误描述 |
| `paramsSummary` | String | 否 | 步骤参数摘要（脱敏后，用于日志可读性） |
| `screenshotPath` | String | 否 | 步骤失败截图的本地文件路径（若启用步骤级截图） |
| `createdAt` | Long | 是 | 记录创建时间（ms 时间戳） |

补充约束：

- `paramsSummary` 中不得包含敏感字段（如密码）；敏感字段用 `***` 替代
- 所有时间字段统一使用 UTC 毫秒时间戳，不存储带时区的字符串
- `runId` 在同一 `ContinuousSessionEntity` 内与 `cycleNo` 对应，不允许同一 `sessionId` 下出现相同的 `cycleNo`