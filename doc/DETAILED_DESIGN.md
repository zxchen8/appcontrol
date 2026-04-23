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
- `ContinuousSessionEntity` 和 `TaskRunEntity` 字段应与 9.1、9.2 中定义的记录结构保持一致

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
- `onFailure`：失败策略，选填，未配置时使用系统默认值
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

- `stop_task`
- `continue`
- `retry_task`

实现建议：

- 首版任务编写默认只使用 `stop_task`
- `continue` 与 `retry_task` 作为高级策略保留，但不要求成为首批任务模板的默认写法

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