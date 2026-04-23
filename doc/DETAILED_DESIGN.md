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
- `manual` 不作为任务 DSL 的 `trigger.type`；本机手动真实执行由 UI/app-service 直接发起一次运行实例，复用同一执行引擎，但不进入 scheduler
- `executionPolicy.maxRetries` 表示任务首次失败后的额外重试次数，不包含首次执行；例如 `maxRetries = 1` 表示最多执行 2 次（首次执行 + 1 次任务级重试）
- `preconditions.type` 的首版支持枚举见 7.3
- `diagnostics` 字段交互语义见 2.6
- `definitionStatus` 不是任务 JSON DSL 字段，而是系统根据原始配置解析与校验结果派生出的内部状态，取值与转移语义见 8.1

### 2.5 `executionPolicy` 关键字段语义

- `taskTimeoutMs`：单次任务运行超时时间
- `maxRetries`：任务首次失败后的额外重试次数
- `retryBackoffMs`：任务级重试退避时间
- `conflictPolicy`：任务因目标 App 执行锁被占用而无法立即启动时的处理策略
- `onMissedSchedule`：任务因设备离线、服务未运行或恢复后错过计划窗口时的处理策略，首版仅支持 `skip`

计数口径说明：

- 任务级 `maxRetries` 统计“额外重试次数”，不包含首次执行
- 因此 `maxRetries = 0` 表示任务只执行 1 次，`maxRetries = 1` 表示任务最多执行 2 次
- 步骤级 `retry.maxRetries` 使用同一口径，同样统计“额外重试次数”，不包含首次执行
- UI、监控页和测试用例必须对任务级与步骤级重试使用同一展示口径，避免出现 off-by-one 歧义

首版 `conflictPolicy` 支持：

- `skip`：本次触发直接跳过，并记录冲突日志
- `run_after_current`：等待当前已运行的任务实例自然结束后立即执行一次，但不允许中断正在执行的实例，也不累计排队多次执行

首版 `onMissedSchedule` 支持：

- `skip`：本次错过的计划窗口直接跳过，不补跑，并记录 `SCHED_MISSED_SKIPPED`

补充约束：

- `conflictPolicy` 只处理“目标 App 执行锁被占用”的场景，不等同于 `onMissedSchedule`
- `onMissedSchedule` 只处理错过计划窗口的场景，不处理目标 App 执行锁被占用的场景；后者仍由 `conflictPolicy` 决定
- `run_after_current` 首版主要用于 cron 任务与当前 continuous 任务实例冲突时的补偿执行
- continuous 任务本身不建议使用 `run_after_current` 形成排队；连续任务默认在目标 App 空闲后再进入下一轮

### 2.6 `diagnostics` 字段语义

- `captureScreenshotOnFailure`：当任务运行进入 `failed`、`timed_out` 或 `blocked` 等终态失败时，尝试保留最终失败截图；若命中 9.6 中定义的抑制或存储退化条件，则改为记录明确原因
- `captureScreenshotOnStepFailure`：当单个步骤尝试失败时，允许在步骤级记录失败截图；若该步骤随后重试成功，截图仍保留在步骤记录中但不重复生成任务级失败截图
- 若某次失败同时满足步骤级失败与任务终态失败，且两个字段都为 `true`，首版只生成一份截图或一条抑制原因记录，并在步骤记录与任务汇总中共享引用
- 若两个字段都为 `false`，系统仍必须保留结构化错误码和失败上下文，不允许因为关闭截图而丢失可定位性

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

连续运行会话生命周期约束：

- 同一 `taskId` 在任一时刻最多只允许一个 `status = running` 的连续运行会话
- 当任务从空闲态首次进入 continuous 执行，或上一会话已进入终态后再次被选中时，必须创建新的 `sessionId`
- 只有在前台调度服务重建、应用进程恢复或设备重启后，且上一会话仍处于 `status = running`、任务仍 `enabled` 且 `definitionStatus = ready` 时，才允许恢复已有会话
- 已进入终态的会话只保留用于历史回看，不得再次恢复或复用

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
- 连续运行会话执行中对账号组启停或顺序的修改只从下一轮开始生效，不得影响当前轮正在使用的账号
- 若下一轮开始前目标 `CredentialSet` 已无启用账号，则当前会话必须以 `blocked` 终止，并记录最后错误码

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
- `active_credential` 表示当前轮次选中的账号，仅在连续运行账号轮换场景下可用

### 3.4 凭据存储实现约束

- `CredentialProfile` 元数据、标签和索引信息存储在 Room 中
- `CredentialSet` 元数据和账号顺序关系存储在 Room 中
- 敏感字段在写入本地存储前必须加密，加密密钥由 Android Keystore 保护
- DataStore 不用于账号、密码等敏感凭据存储
- 本地凭据只允许测试账号，不允许使用生产账号
- rooted 测试设备上的凭据应支持快速替换与轮换
- `encryptionVersion` 必须是单调递增的算法/密钥版本号；首版 `1` 表示当前默认的 Keystore 加密方案
- 当引入新加密版本时，新写入凭据必须使用最新 `encryptionVersion`；旧版本载荷保持只读兼容，并在下一次成功读取或保存后惰性迁移到最新版本
- 若本地存在无法识别的 `encryptionVersion` 或所需密钥不可用，必须阻断该凭据使用并返回统一错误码

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

- `TaskDefinitionEntity` 至少包含 `taskId`、`name`、`enabled`、`triggerType`、`definitionStatus`、`rawJson`、`updatedAt`
- `TaskScheduleStateEntity` 至少包含 `taskId`、`nextTriggerAt`、`standbyEnabled`、`lastTriggerAt`、`lastScheduleStatus`
- `CredentialSecretEntity` 至少包含 `profileId`、`encryptedPayload`、`encryptionVersion`、`updatedAt`
- `ContinuousSessionEntity` 和 `TaskRunEntity` 字段应与 9.1、9.2 中定义的记录结构保持一致

补充约束：

- 任务原始 JSON 必须保留，不能只保留解析后字段，以支持原始编辑和回放排障
- `definitionStatus` 必须与 8.1 中定义的任务定义状态一致，用于持久化原始编辑后尚未修复的非法配置
- `CredentialProfileEntity` 与 `CredentialSecretEntity` 应通过 `profileId` 一对一关联
- `CredentialSetEntity` 与 `CredentialSetItemEntity` 应通过外键或等效约束维持一致性
- 删除账号或账号组前必须先校验是否仍被任务或连续运行会话引用

### 3.7 DAO 与 Repository 接口建议

首版建议至少定义以下 DAO：

- `TaskDefinitionDao`：任务列表读取、任务原始配置保存、定义状态更新与启停状态更新
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
    "maxRetries": 0,
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
- `onFailure`：失败策略，选填，未配置时使用系统默认值
- `clearsSensitiveContext`：步骤成功后是否明确清除敏感输入上下文，选填，默认 `false`
- `params`：步骤参数，必填

默认值建议：

- `retry.maxRetries = 0`
- `retry.backoffMs = 1000`
- `onFailure = stop_task`

补充语义：

- `retry.maxRetries` 表示额外重试次数，不包含首次执行
- 因此 `retry.maxRetries = 0` 表示不做额外重试
- 例如 `retry.maxRetries = 1` 表示该步骤最多尝试 2 次（首次执行 + 1 次步骤级重试）
- 任务级 `maxRetries` 与步骤级 `retry.maxRetries` 使用同一计数口径
- `clearsSensitiveContext = true` 只用于在敏感输入后的后续步骤成功时，显式确认已经离开敏感输入页面并恢复默认截图策略

### 4.3 失败策略

首版支持：

- `stop_task`
- `continue`
- `retry_task`

实现建议：

- 首版任务编写默认只使用 `stop_task`
- `continue` 与 `retry_task` 作为高级策略保留，但不要求成为首批任务模板的默认写法
- `retry_task` 表示立即终止当前步骤尝试，并从任务第一步重新开始整个任务；该行为消耗任务级 `maxRetries` 配额，而不是额外增加步骤级重试次数

## 5. 步骤类型

实现范围划分如下：

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

- `target`

### 5.5 `swipe`

执行滑动动作。

关键参数：

- `from`
- `to`
- `durationMs`

### 5.6 `input_text`

向当前焦点或指定元素输入文本。

关键参数：

- `selector`
- `text`
- `textRef`
- `clearBeforeInput`

首版允许直接字面量输入，也允许通过 `textRef` 引用任务变量。

补充约束：

- `text` 与 `textRef` 必须二选一
- 账号、密码等敏感输入必须使用 `textRef`
- 当 `textRef` 被使用时，变量必须在任务运行前成功解析
- 首版优先保证测试账号、密码和常见 ASCII/符号输入；复杂 IME、中文整句输入作为后续增强处理
- `input_text` 的步骤日志只允许记录选择器摘要、输入来源类型和是否脱敏，不得记录解析后的原始文本值
- 若 `textRef` 指向 `sensitive = true` 的变量，执行日志中只允许记录变量名、字段名和 `masked = true`，不得记录变量解析值、长度推断信息或密文载荷
- 若 `textRef` 指向 `sensitive = true` 的变量，则该步骤失败时的截图行为必须遵循 9.6 中定义的强制抑制条件，不能因为 `captureScreenshotOnStepFailure = true` 而绕过抑制规则
- 若 `textRef` 指向 `sensitive = true` 的变量，执行引擎必须在运行时把 `sensitiveContextActive` 置为 `true`；该标记仅属于运行时上下文，不单独持久化为状态模型字段

### 5.7 `wait_element`

等待元素出现或消失。

关键参数：

- `selector`
- `state`

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

关键参数：

- `name`
- `overwrite`

## 6. 选择器与目标模型

### 6.1 selector

```json
{
  "by": "resourceId",
  "value": "com.example.target:id/login_button"
}
```

首版允许的 `by`：

- `resourceId`
- `text`
- `contentDescription`
- `className`

首版不将 XPath 作为核心定位方式。

### 6.2 target

```json
{
  "kind": "element",
  "selector": {
    "by": "text",
    "value": "登录"
  }
}
```

允许的 `kind`：

- `element`
- `ocr_text`
- `image`
- `coordinate`

定位优先级：元素 > OCR > 图像 > 坐标。

## 7. 执行语义

### 7.1 任务级执行规则

- 步骤默认串行执行
- 默认同一任务不并发执行
- 默认同一目标 App 同一时刻只允许一个任务实例执行
- 任务默认在第一个致命错误处停止
- 任务级重试发生在整个任务失败之后，不替代步骤级重试
- 首版建议大多数任务使用统一默认重试与失败策略，避免大量任务级特化配置
- `trigger.type = continuous` 时，系统必须先创建连续运行会话，再逐轮创建独立运行记录
- 连续运行会话创建后保持 `running`，直到手动停止、任务被禁用、达到 `maxCycles` / `maxDurationMs`、`onCycleFailure = stop_session`、账号组已无可用账号或出现不可恢复阻断错误
- 同一 `taskId` 在任一时刻最多只允许一个 `status = running` 的连续运行会话
- 服务恢复指前台调度服务因进程重启被重建，或设备重启后重新拉起调度服务；只有未进入终态的会话才允许被恢复
- 已进入终态的连续运行会话不得恢复，下一次执行必须创建新的 `sessionId`
- 连续运行模式下，账号选择发生在轮次开始前，并在整轮执行期间保持不变
- 连续运行模式下，轮次失败默认只影响当前轮记录，不自动阻断后续轮次
- 步骤级重试和任务级重试都发生在当前轮次和当前账号上下文内，不得在重试过程中切换账号
- `onCycleFailure = continue_next` 时，当前轮失败后记录失败结果，并在冷却时间后切换到下一个账号继续后续轮次
- `onCycleFailure = stop_session` 时，当前轮失败后结束整个连续运行会话，不再启动后续轮次
- `onCycleFailure = continue_next` 不会改变账号组内容；失败账号在后续轮次中仍按既有顺序继续参与轮换
- 连续运行会话执行中如果账号组被修改，当前轮继续使用已选中的账号；下一轮开始前必须重新校验账号组可用性
- 调度层必须基于 `targetApp.packageName` 持有统一执行锁，任何任务实例启动前都必须先获取该锁
- 同一目标 App 下 cron 任务优先于下一轮 continuous 任务
- 若 cron 触发时 current continuous 任务实例已在运行，当前实例只允许自然结束，不允许中途抢占；结束后若该 cron 仍处于锁冲突延迟状态，则按 `conflictPolicy` 决定是否立即补跑
- 若因设备离线、服务未运行或恢复后错过 cron 计划窗口，则按 `onMissedSchedule` 处理；首版仅支持 `skip`，不补跑
- 同一目标 App 可同时启用多个 continuous 任务，但 scheduler 每次只允许一个 continuous 任务实例持有执行锁
- 多个 continuous 任务并存时，当前实例结束后必须先重新检查是否有到期 cron；若无，再按稳定轮转顺序选择下一个 continuous 任务实例
- 同一目标 App 下多个 continuous 任务的稳定轮转顺序首版固定为 `taskId` 升序；当前任务结束后，应从当前 `taskId` 的后继任务开始查找下一候选，若不存在后继则回到最小 `taskId`
- 多个 cron 任务同时到期时，按计划触发时间升序处理；同时间点可按 `taskId` 升序作为稳定排序

### 7.2 步骤级执行规则

- 所有步骤都必须支持超时
- 所有步骤都必须支持取消
- 元素、OCR、图像类步骤必须支持轮询直到超时
- 当依赖能力未启用时，必须返回 `STEP_CAPABILITY_UNAVAILABLE`

### 7.3 前置条件

首版建议支持：

- `root_ready`
- `screen_on`
- `screen_unlocked`
- `target_app_installed`

## 8. 状态模型

### 8.1 任务定义状态

- `draft`
- `ready`
- `invalid`

补充约束：

- `definitionStatus` 是系统内部派生状态，不属于任务 JSON DSL 原始字段
- `draft` 表示原始配置已存在，但尚未完成一次可用于执行的成功校验
- `ready` 表示最近一次解析、校验与依赖检查均通过，任务可以被手动执行，且在 `enabled = true` 时进入调度待命
- `invalid` 表示最近一次解析、校验或关键依赖检查失败，任务只能保留用于编辑和回看，不能进入调度或执行路径
- 任务定义状态只描述配置本身是否可执行，不承载运行结果
- 任务是否启用继续由顶层字段 `enabled` 表达，不复用运行状态枚举
- 调度待命与下一次触发时间通过 `TaskScheduleStateEntity` 表达，不与任务定义状态合并
- `definitionStatus = invalid` 的任务不得进入 scheduler 或 runner 的正常执行路径；只有修复配置并回到 `ready` 后，才允许重新进入调度待命
- 手动真实执行使用与调度相同的 `definitionStatus` 准入门禁；当 `definitionStatus != ready` 时，app-service 必须在创建运行实例前直接阻断，并向 UI 返回最近一次校验错误，runner-engine 不得启动
- 状态转移首版固定为：首次导入或原始编辑后的未校验配置进入 `draft`；校验成功后转为 `ready`；任一后续校验失败则转为 `invalid`；修复并重新校验成功后从 `invalid` 回到 `ready`

### 8.2 调度状态

首版不定义单一的“任务总状态”枚举来混合配置态、待命态和运行态；调度状态通过 `TaskScheduleStateEntity` 的字段组合表达，至少包含：

- `standbyEnabled`
- `nextTriggerAt`
- `lastTriggerAt`
- `lastScheduleStatus`

`lastScheduleStatus` 建议至少支持：

- `idle`
- `scheduled`
- `conflict_skipped`
- `conflict_delayed`
- `missed_skipped`
- `blocked`

### 8.3 任务运行状态

- `pending`
- `running`
- `success`
- `failed`
- `timed_out`
- `cancelled`
- `blocked`

状态区分约束：

- `blocked`：任务因为配置、环境、凭据或安全门禁不满足而无法安全继续，执行可以在未进入第一条动作步骤前就终止；例如 `definitionStatus != ready`、前置条件失败、账号组无可用账号、凭据密钥不可用
- `failed`：任务已经通过执行门禁并开始执行动作步骤，但最终因不可恢复的步骤/动作失败而终止

### 8.4 步骤状态

- `pending`
- `running`
- `success`
- `failed`
- `timed_out`
- `skipped`
- `cancelled`

## 9. 运行记录与诊断产物

### 9.1 连续运行会话记录

建议至少包含：

- `sessionId`
- `taskId`
- `credentialSetId`
- `status`
- `startedAt`
- `finishedAt`
- `totalCycles`
- `successCycles`
- `failedCycles`
- `currentCredentialProfileId`
- `currentCredentialAlias`
- `nextCredentialProfileId`
- `nextCredentialAlias`
- `cursorIndex`
- `lastErrorCode`

`status` 建议至少支持：

- `running`
- `success`
- `failed`
- `cancelled`
- `timed_out`
- `blocked`

会话记录约束：

- 同一 `taskId` 在任一时刻最多只允许一个 `status = running` 的会话记录
- 只有 `status = running` 的会话允许在服务恢复或设备重启后被恢复
- `success`、`failed`、`cancelled`、`timed_out`、`blocked` 都属于终态；终态会话只用于回看，不得再次恢复
- 当前会话进入终态后，下一次 continuous 执行必须创建新的 `sessionId`
- 状态转移首版固定为：新建会话直接进入 `running`；达到配置完成条件后进入 `success`；手动停止或任务被禁用时进入 `cancelled`；达到 `maxDurationMs` 时进入 `timed_out`；账号组无可用账号、环境阻断或配置阻断时进入 `blocked`；出现不可恢复执行错误且会话终止时进入 `failed`

### 9.2 任务运行记录

建议至少包含：

- `runId`
- `sessionId`
- `cycleNo`
- `taskId`
- `credentialSetId`
- `credentialProfileId`
- `credentialAlias`
- `status`
- `startedAt`
- `finishedAt`
- `durationMs`
- `triggerType`
- `errorCode`
- `message`

其中 `triggerType` 记录本次运行来源，首版支持 `manual`、`cron`、`continuous`。

### 9.3 账号维度汇总视图

运行记录页建议支持按账号维度聚合查看，至少包含：

- `credentialProfileId`
- `credentialAlias`
- `successCycles`
- `failedCycles`
- `lastStatus`
- `lastRunAt`
- `lastErrorCode`

首版约束：

- 账号维度汇总视图可由本地运行记录聚合生成，不要求单独远程上报
- 账号维度汇总仅用于排障和回看，不改变轮换调度逻辑

### 9.4 步骤运行记录

建议至少包含：

- `stepId`
- `status`
- `startedAt`
- `finishedAt`
- `durationMs`
- `errorCode`
- `message`
- `artifacts`

### 9.5 诊断产物

建议支持：

- `screenshot`
- `ocr-debug`
- `image-debug`

首版至少落地 `screenshot`。

### 9.6 日志脱敏与产物保留

日志脱敏规则：

- 关键参数摘要必须是脱敏摘要，不得直接写入敏感变量解析值、账号密码明文、加密载荷、OCR 原始全文或可能复原秘密的信息
- `input_text`、凭据解析和变量替换相关日志默认只记录字段名、来源类型、是否成功和 `masked` 标记
- 结构化日志中若出现 `selector.value`、`target`、`text` 等可能包含敏感信息的字段，必须先经过白名单字段筛选或脱敏转换后再落库

截图与诊断产物保留规则：

- 失败截图默认开启；只有命中强制抑制条件或存储退化条件时才允许不写入截图
- 当某个 `input_text` 步骤使用 `sensitive = true` 的变量成功开始输入时，执行引擎必须把运行时标记 `sensitiveContextActive` 置为 `true`
- 首版强制抑制条件固定为：终端失败发生时 `sensitiveContextActive = true`
- `sensitiveContextActive` 仅可通过以下方式清除：后续步骤成功且 `clearsSensitiveContext = true`，或 `start_app` / `restart_app` / `stop_app` 成功并明确切离当前敏感输入页面
- 一旦 `sensitiveContextActive` 被清除，失败截图恢复为默认开启，不允许继续沿用上一页面的抑制状态
- 本地截图、日志和其他诊断产物必须配置保留上限；首版默认保留最近 14 天、每个 `taskId` 最多 500 个诊断产物、全局诊断存储预算 512 MB，以先达到者为准触发清理
- 清理必须至少在应用启动时、每次写入新诊断产物前，以及每次任务运行结束后执行一次
- 写入新产物前必须检查存储容量水位；达到高水位时，应优先清理超出保留策略的旧产物，再决定是否继续写入
- 清理策略首版固定为优先删除最旧的非终态无关截图与诊断产物；若清理后仍不足以写入新截图，则跳过该次截图写入并记录 `DIAG_ARTIFACT_STORAGE_LIMIT_REACHED`
- 若当前界面命中强制抑制条件且无法在截图前完成脱敏，则执行器必须放弃保存截图，仅记录错误码和 `DIAG_SCREENSHOT_SUPPRESSED_FOR_SENSITIVE_CONTENT` 一类的结构化原因
- 若任务以 `blocked` 终态结束，且阻断发生在第一条动作步骤开始前，则默认不生成截图，只保留阻断错误码与上下文；若阻断发生在目标页面已进入前台之后，则仍按 `captureScreenshotOnFailure` 与 9.6 抑制规则决定是否保留截图
- 因磁盘不足或保留策略触发清理时，必须记录结构化清理日志，避免把“截图缺失”误判为执行链路丢失

## 10. 运行控制与观察交互

### 10.1 首版交互原则

- 任务启动入口在 App 内部
- 运行中控制入口以前台服务通知为主
- 运行状态观察以目标 App 前台真实运行和 App 内监控页为主
- 首版不以悬浮窗作为默认交互方式

补充说明：

- 当目标 App 位于前台时，用户通常无法同时停留在监控页，因此通知栏是运行中的主控制面
- 监控页用于启动前确认、运行中回看详情，以及运行后排查问题

### 10.2 前台服务通知

首版建议至少提供：

- 当前任务运行中状态
- 停止任务动作
- 返回监控页入口

通知展示建议：

- 标题显示任务名称
- 正文显示当前步骤名称或当前状态
- 运行失败时切换为失败状态文案，并保留跳转监控页入口

首版不建议在通知中提供：

- 暂停/恢复动作
- 跳过当前步骤动作
- 复杂调试菜单

### 10.3 运行监控页

首版建议至少展示：

- 当前任务名称
- 当前步骤
- 运行状态
- 最近错误信息
- 最近失败截图，或截图被抑制时的明确原因
- 连续运行会话状态
- 当前轮次编号
- 当前账号标识或账号别名
- 已完成轮次数、成功轮次数、失败轮次数

建议补充字段：

- 任务运行编号
- 当前账号组名称
- 已运行时长
- 最近一条步骤日志摘要
- 停止任务按钮

针对连续运行账号轮换，建议补充展示：

- 下一个轮次预计使用的账号
- 当前账号在本会话内的成功次数与失败次数
- 最近一次账号切换时间

首版交互建议：

- 监控页可以手动刷新，也可以自动刷新
- 自动刷新优先使用应用内状态流或轮询仓储中的最新运行记录
- 当用户从通知栏返回监控页时，应优先打开当前正在运行或最近一次失败的任务实例

### 10.4 运行记录页

首版建议至少支持以下维度查看：

- 按任务查看历史运行记录
- 按连续运行会话查看轮次结果
- 按账号查看成功次数、失败次数和最近失败原因

建议补充交互：

- 支持按 `credentialSetId`、`credentialProfileId` 或账号别名筛选
- 支持从账号维度汇总项跳转到最近一次失败轮次详情

### 10.5 页面与导航流

首版建议页面流如下：

1. 用户在任务列表页点击“运行”
2. 系统进入运行监控页并展示“准备执行”状态
3. 前台服务启动，通知栏出现运行中通知
4. 系统将目标 App 切到前台执行
5. 用户通过真实前台画面观察目标 App 行为
6. 用户如需详情，通过通知栏返回监控页
7. 任务结束后，监控页与运行记录页可查看结果

### 10.6 状态刷新机制

首版建议采用简单可靠方案：

- 前台服务负责把当前运行状态写入统一运行状态源
- 运行监控页从统一运行状态源读取当前任务、当前步骤和最近错误
- 运行记录页从持久化仓储读取历史记录

实现优先级建议：

- 优先保证通知文案与监控页状态一致
- 优先保证任务结束和失败状态能及时回写
- 不要求首版实现高频细粒度日志流式渲染

### 10.7 悬浮边缘胶囊

悬浮边缘胶囊不纳入首版默认交互方案，只作为后续调试增强候选能力。

如果后续引入，必须满足以下约束：

- 默认不抢焦点
- 只展示最小状态信息和停止动作
- 在截图、OCR、图像识别前自动隐藏
- 仅在手动调试模式下启用，不作为定时运行模式的主交互

## 11. 错误码规范

建议统一前缀：

- `ENV_`：环境问题
- `TASK_`：任务配置问题
- `STEP_`：步骤定义或能力问题
- `CTRL_`：控制动作失败
- `UI_`：元素识别失败
- `OCR_`：OCR 失败
- `IMG_`：图像匹配失败
- `SCHED_`：调度失败
- `DIAG_`：诊断产物生成、保留或抑制相关问题

示例：

- `ENV_ROOT_NOT_READY`
- `ENV_CREDENTIAL_KEY_UNAVAILABLE`
- `TASK_SCHEMA_INVALID`
- `STEP_TIMEOUT`
- `STEP_CAPABILITY_UNAVAILABLE`
- `CTRL_START_APP_FAILED`
- `UI_ELEMENT_NOT_FOUND`
- `OCR_TEXT_NOT_FOUND`
- `IMG_TEMPLATE_NOT_FOUND`
- `SCHED_CONFLICT_SKIPPED`
- `SCHED_CONFLICT_DELAYED`
- `SCHED_MISSED_SKIPPED`
- `DIAG_ARTIFACT_STORAGE_LIMIT_REACHED`
- `DIAG_SCREENSHOT_SUPPRESSED_FOR_SENSITIVE_CONTENT`

## 12. 校验规则

首版至少校验以下内容：

- 顶层字段是否齐全
- `taskId` 是否唯一且格式合法
- `schemaVersion` 是否受支持
- `trigger.type` 是否受支持（首版仅 `cron`、`continuous`；`manual` 仅作为运行来源，不作为任务 DSL 的 `trigger.type`）
- `trigger.type = cron` 时 cron 表达式是否合法
- `trigger.type = continuous` 时 `cooldownMs`、`maxCycles`、`maxDurationMs` 是否满足范围要求
- `accountRotation` 是否只在 `trigger.type = continuous` 时使用
- `credentialSetId` 是否存在且至少包含一个可用账号
- `onCycleFailure` 是否为支持的枚举值
- `conflictPolicy` 是否为支持的枚举值
- `onMissedSchedule` 是否为支持的枚举值（首版仅 `skip`）
- 每个步骤 `id` 是否唯一
- 每个步骤类型是否受支持
- 每个步骤参数是否满足类型要求
- 超时、重试等数值是否在合理范围内
- 变量引用是否能正确解析
- `active_credential` 是否只在启用账号轮换的连续运行任务中使用
- `input_text` 中 `text` 与 `textRef` 是否满足互斥约束
- 步骤级 `retry.maxRetries` 是否为非负整数，并与任务级 `maxRetries` 使用一致的计数口径
- `clearsSensitiveContext` 是否为布尔值，且只用于需要恢复默认截图策略的后续步骤
- 敏感变量是否错误地使用了 `literal`
- `selector` 和 `target` 是否符合对应规则

## 13. 示例任务

以下示例表示“重启 App，等待首页出现，再输入账号并点击登录按钮”：

```json
{
  "schemaVersion": "1.0",
  "taskId": "restart-and-login",
  "name": "重启并登录",
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
  "executionPolicy": {
    "taskTimeoutMs": 120000,
    "maxRetries": 1,
    "retryBackoffMs": 3000,
    "conflictPolicy": "skip",
    "onMissedSchedule": "skip"
  },
  "preconditions": [
    {
      "type": "root_ready"
    }
  ],
  "variables": {
    "ACCOUNT_USERNAME": {
      "source": "credential_profile",
      "profileId": "smoke-account-01",
      "field": "username"
    }
  },
  "steps": [
    {
      "id": "step-restart-app",
      "type": "restart_app",
      "timeoutMs": 20000,
      "retry": { "maxRetries": 0, "backoffMs": 1000 },
      "onFailure": "stop_task",
      "params": {
        "packageName": "com.example.target",
        "waitAfterStopMs": 1000
      }
    },
    {
      "id": "step-wait-home",
      "type": "wait_element",
      "timeoutMs": 15000,
      "retry": { "maxRetries": 0, "backoffMs": 1000 },
      "onFailure": "stop_task",
      "params": {
        "selector": {
          "by": "text",
          "value": "首页"
        },
        "state": "visible"
      }
    },
    {
      "id": "step-input-user",
      "type": "input_text",
      "timeoutMs": 10000,
      "retry": { "maxRetries": 0, "backoffMs": 1000 },
      "onFailure": "stop_task",
      "params": {
        "selector": {
          "by": "resourceId",
          "value": "com.example.target:id/phone"
        },
        "textRef": "ACCOUNT_USERNAME",
        "clearBeforeInput": true
      }
    },
    {
      "id": "step-tap-login",
      "type": "tap",
      "timeoutMs": 10000,
      "retry": { "maxRetries": 1, "backoffMs": 1000 },
      "onFailure": "stop_task",
      "params": {
        "target": {
          "kind": "element",
          "selector": {
            "by": "resourceId",
            "value": "com.example.target:id/login_button"
          }
        }
      }
    }
  ],
  "diagnostics": {
    "captureScreenshotOnFailure": true,
    "captureScreenshotOnStepFailure": true,
    "logLevel": "info"
  },
  "tags": ["smoke", "login"]
}
```