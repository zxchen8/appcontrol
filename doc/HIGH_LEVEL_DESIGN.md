# Android 自动化测试控制 App 概要设计 v0.1

## 1. 文档定位

本文档用于说明系统整体怎么搭建，重点覆盖架构边界、模块拆分、核心数据流、工程结构和首版落地范围。

本文件同时作为首版范围边界与冻结技术决策的唯一主源；涉及 v1 是否实现、首版不做、默认调度策略、运行交互主方案等问题，均以本文件为准。

文档职责划分如下：

- [PRD](PRD.md) 负责定义做什么
- [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md) 负责定义系统整体怎么组织
- [DETAILED_DESIGN.md](DETAILED_DESIGN.md) 负责定义关键对象和执行细节
- [DEVELOPMENT_RULES.md](../rules/DEVELOPMENT_RULES.md) 负责定义开发约束和协作规则

## 2. 已确认决策

- 运行环境只考虑 Android 9 或 Android 10 rooted 设备
- 本机模式为手动真实执行，不做纯逻辑演练
- 目标场景为单设备、单目标 App、固定流程自动化
- 任务采用声明式 JSON DSL
- 账号、密码等测试数据通过本地变量和凭据配置管理
- OCR 只允许纯本地实现，且首版可暂不启用
- 图像模板由开发或测试手工维护
- 首版只保留本地运行记录和截图，不做远程上传和报告导出

## 3. 设计目标

- 稳定性优先于功能数量
- 执行引擎与 UI 解耦
- 新增任务优先通过配置完成，而不是改代码发版
- 失败必须可定位，成功必须可追踪
- 后续新增步骤类型时，不应重写主执行流程

## 4. 总体架构

```text
App UI
  |
Application Service
  |
Task Repository ---- Scheduler ---- Boot/Alarm Receiver
  |
Runner Engine
  |
Capability Facade
  |
+----------------+----------------+----------------+----------------+
| Device Control | UI Inspector   | Vision Core    | Diagnostics    |
+----------------+----------------+----------------+----------------+
  |
Runtime Store / Artifact Store
```

## 5. 模块划分

### 5.1 app-ui

职责：

- 展示任务列表、任务配置导入与原始编辑、运行记录、环境检查和账号配置
- 触发手动真实执行
- 展示运行状态、失败截图和错误信息
- 提供运行监控页，用于查看当前任务、当前步骤和最近失败上下文

不负责：

- 直接调用 root 命令
- 直接执行任务步骤
- 拼接任务逻辑

### 5.2 app-service

职责：

- 作为 UI 与核心执行系统之间的应用服务层
- 组织任务保存、运行、停止和重试
- 管理任务、运行记录、连续运行会话和凭据的用例调用

### 5.3 task-dsl

职责：

- 解析和校验任务 JSON
- 做 schema 版本兼容和迁移
- 产出执行引擎可消费的标准任务对象

### 5.4 scheduler

职责：

- 解析 cron 与 continuous 两类触发方式
- 计算下一次触发时间或下一轮启动时间
- 处理任务启停、连续运行轮转、冲突策略和设备重启恢复

实现建议：

- 使用 Room 保存任务与下次触发时间
- v1 cron 表达式先限制为分钟级，后续再评估秒级支持
- 使用 `cron-utils` 计算调度时间
- 当存在已启用的定时任务时，系统进入“调度待命”状态，并启动单一前台调度服务
- 前台调度服务按分钟边界检查到期任务，到期后再交由 `runner-engine` 执行
- `trigger.type = continuous` 时，由前台调度服务在本轮结束并达到冷却时间后调度下一轮
- 同一目标 App 通过统一执行锁串行化任务实例；调度层不得绕过该锁直接启动执行
- 调度优先级首版固定为：到期 cron 任务高于下一轮 continuous 任务
- 若 cron 到期时 continuous 当前任务实例已在执行，则允许该实例自然结束；结束后先处理 cron，再决定是否启动下一个 continuous 实例
- 同一目标 App 可同时启用多个 continuous 任务，但 scheduler 每次只选择一个 continuous 任务实例执行
- 多个 continuous 任务并存时，实例结束后按稳定轮转顺序选择下一个候选任务；在真正启动前必须再次检查是否已有到期 cron
- 多个 cron 任务同时到期时，按计划触发时间升序处理；同时间点使用稳定顺序避免随机性
- 连续运行任务如配置账号轮换，调度层负责维护账号组游标，并在轮次开始前选出本轮账号
- `persistCursor = true` 时，调度层必须在每轮结束后持久化下一账号游标，并在服务恢复或设备重启后继续使用
- 单轮失败后的连续运行处理由账号轮换策略决定；首版默认继续下一个账号，也支持直接停止整个会话
- `onCycleFailure = continue_next` 只表示切换到下一个账号继续运行，不自动将失败账号移出后续轮换
- `AlarmManager` 只用于开机恢复和前台调度服务意外退出后的恢复，不作为首版调度主链路
- 首版调度和运行时行为只按 Android 9/10 的系统约束设计，不额外覆盖 Android 11+ 的 exact alarm、前台服务类型和后台启动差异
- 使用 `BootReceiver` 在开机后恢复计划

### 5.5 runner-engine

职责：

- 创建任务运行实例
- 串行执行步骤
- 管理超时、取消、步骤级重试和任务级重试
- 统一输出运行状态、步骤结果和轮次结果

这是系统核心，必须按状态机思维实现，而不是用页面回调串逻辑。

### 5.6 capability-facade

职责：

- 对执行引擎暴露统一能力入口
- 屏蔽 root、无障碍、OCR、图像识别等底层实现差异
- 聚合动作执行、元素查询、文本识别和图像匹配能力

### 5.7 platform/device-control

职责：

- 封装 root shell 命令
- 封装启动、停止、重启 App
- 封装 `input tap`、`input swipe`、`input text`
- 做运行环境检查

### 5.8 platform/accessibility

职责：

- 读取无障碍节点树
- 支持 `resourceId`、`text`、`contentDescription` 定位
- 支持等待元素出现、消失和辅助点击

实现约束：

- 该能力必须通过独立的 `AccessibilityService` 组件实现，而不是普通页面组件或工具类直接替代
- 其生命周期独立于 Activity，需要在环境检查页中明确检查启用状态

### 5.9 platform/vision

职责：

- 提供本地 OCR 能力接入点
- 提供模板图像匹配
- 处理图像预处理和坐标转换

约束：

- OCR 必须可插拔
- 首版可以不启用 OCR，但接口要预留
- 图像模板由人工维护，不做模板管理界面

### 5.10 diagnostics 与 runtime-store

职责：

- 结构化日志
- 截图与诊断产物管理
- 任务定义、连续运行会话记录、运行记录、步骤记录、错误码和调度信息存储
- 账号组游标和连续运行恢复状态存储
- 账号维度聚合结果和最近失败上下文存储

## 6. 核心数据流

### 6.1 手动真实执行

1. 用户在 UI 选择任务并点击运行。
2. app-service 读取任务和本地变量配置。
3. task-dsl 解析并校验任务定义。
4. runner-engine 创建运行实例并获取执行锁。
5. capability-facade 按步骤调用底层能力模块。
6. diagnostics 和 runtime-store 持久化步骤结果、日志和截图。
7. 前台服务通知展示运行中状态，并提供停止入口。
8. UI 监控页展示当前步骤、最终结果与失败上下文。

### 6.2 连续循环执行

1. scheduler 在多个 enabled continuous 任务中选择一个任务实例进入执行。
2. scheduler 为被选中的任务创建或恢复连续运行会话。
3. 调度层按账号组游标选出当前轮次使用的账号。
4. runner-engine 用该账号执行这一轮任务步骤。
5. diagnostics 和 runtime-store 记录 `sessionId`、`cycleNo`、`credentialProfileId` 和轮次结果。
6. 当前实例结束后，scheduler 先检查是否有到期 cron；若无，再按稳定轮转顺序选择下一个 continuous 任务实例。

### 6.3 首版运行交互方案

- 主入口为 App 内任务列表和手动运行按钮
- 主控制通道为前台服务通知，至少提供“运行中状态”和“停止任务”动作
- 主观察方式为目标 App 前台真实运行 + App 内监控页
- 首版不以悬浮窗作为默认交互方式，避免额外权限、ROM 差异和对截图/识别的干扰
- 悬浮边缘胶囊只作为后续手动调试增强候选方案

### 6.4 定时运行

1. scheduler 读取启用任务。
2. scheduler 计算下一次触发时间并持久化。
3. 到时后启动执行服务。
4. runner-engine 执行任务。
5. 任务结束后写回结果并计算下一次计划。

冲突处理补充：

- 若目标 App 当前正被 continuous 任务占用，cron 任务不能中断当前任务实例，只能等待当前实例结束后再按冲突策略处理
- 若目标 App 当前正被 cron 任务占用，任何 continuous 任务实例都不得启动，直到所有已到期 cron 任务处理完毕

### 6.5 失败处理

1. 步骤失败或超时。
2. runner-engine 根据步骤策略决定重试、继续或停止任务。
3. diagnostics 保留错误码、截图和上下文信息。
4. runtime-store 写入最终失败结果。

## 7. 工程结构

### 7.1 仓库结构

```text
appcontrol/
  app/
    src/
      main/
        AndroidManifest.xml
        java/com/plearn/appcontrol/
        res/
      androidTest/
      test/
    build.gradle.kts
  doc/
  rules/
  gradle/
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  gradlew
  gradlew.bat
```

### 7.2 包结构建议

```text
com.plearn.appcontrol/
  App.kt
  bootstrap/
  feature/
    tasklist/
    taskedit/
    runhistory/
    settings/
    credentials/
  appservice/
  runtime/
    engine/
    scheduler/
    diagnostics/
    state/
  platform/
    device/
    accessibility/
    vision/
  data/
    local/
    repository/
    model/
  dsl/
  common/
```

data 层首版落地建议：

- `data/local/entity/`：Room Entity 定义
- `data/local/dao/`：Room DAO 与事务入口
- `data/local/relation/`：Room 关联查询结果或聚合投影
- `data/repository/`：Repository 接口与实现
- `data/model/`：Repository 对外暴露的领域模型和查询结果

### 7.3 工程策略

- 首版只保留一个 Android 应用模块 `app`
- 概念模块先通过包结构表达，不急于拆成多个 Gradle 子模块
- 任务 DSL、执行引擎和能力适配层稳定后，再考虑拆分纯 Kotlin 模块

## 8. 技术选型

### 8.1 Android 基线

- `minSdk = 28`
- 运行验证范围仅覆盖 Android 9（API 28）和 Android 10（API 29）
- `targetSdk` 可按构建工具链要求设置，但首版不以 Android 11+ 的运行兼容为目标
- `compileSdk` 可跟随当前稳定构建工具链
- Kotlin 作为唯一主语言
- Gradle Kotlin DSL

### 8.2 UI

- 单 Activity + Jetpack Compose + Navigation
- ViewModel 管理页面状态
- 首版只做必要页面：环境检查、任务列表、任务配置导入/原始编辑、手动运行、运行监控、运行记录、账号配置

首版页面职责建议：

- 环境检查页：展示 Android 版本、root、无障碍、通知、目标 App 安装状态，以及定时调度相关运行前条件
- 任务列表页：展示任务、启停任务、手动运行入口
- 任务配置导入/原始编辑页：导入 JSON、编辑原始配置、执行基础校验
- 运行监控页：查看当前任务、当前轮次、当前账号、最近日志、最近失败截图
- 运行记录页：查看历史运行结果、轮次详情和账号维度结果
- 账号配置页：管理测试账号、账号组与变量引用

账号配置页首版职责建议：

- 管理单个测试账号的新增、启停和别名
- 管理账号组的顺序、启停和轮换范围
- 展示账号是否被某个连续运行任务引用，避免误删

环境检查页首版检查项：

- 当前设备系统版本是否为 Android 9（API 28）或 Android 10（API 29）
- root shell 是否可执行
- `AccessibilityService` 是否已启用
- 前台服务通知是否可正常展示
- 目标 App 是否已安装且可拉起
- 系统时间与时区是否满足任务计划要求
- 如 ROM 提供对应管理项，是否已放开自启动和电池优化限制

### 8.3 依赖建议

优先引入：

- Kotlin Coroutines
- AndroidX Lifecycle
- Jetpack Compose
- Room
- kotlinx.serialization
- Hilt
- cron-utils

首版暂缓：

- OCR 相关依赖
- OpenCV 等重型图像库

按当前方案，Room 用于任务、运行记录和凭据元数据；如后续需要轻量级非敏感设置，再单独引入 DataStore。

## 9. 首版范围与里程碑

### 9.1 v1 必做

- Android 工程骨架
- 任务 DSL 解析与校验
- 本机手动真实执行
- cron 调度基础能力
- continuous 连续循环执行能力
- 启动、停止、重启 App
- 点击、滑动、文本输入
- 元素等待与元素定位
- 本地变量与账号配置
- 账号组顺序轮换
- 运行日志与失败截图
- rooted 环境检查

首版 UI 范围说明：

- 不做结构化可视化任务编辑器
- 任务维护以 JSON 导入、原始文本编辑和基础启停操作为主
- 不做悬浮窗主交互，运行控制以通知栏和监控页为主
- 运行中的主控制面为前台服务通知，监控页作为详情与回看页面

首版执行策略说明：

- 步骤失败策略和重试策略提供系统默认值，任务配置可只在需要时覆盖
- 调度依赖 dedicated 测试设备与前台执行服务，不承诺在不同 ROM 默认后台策略下都具备完全一致的精确定时表现，但只要求在 Android 9/10 测试设备上完成验证
- 连续循环任务中的账号切换只允许发生在轮次边界，同一轮内不得切换账号
- 连续循环任务必须保证每轮开始前能回到可重复执行的初始态，通常通过任务步骤中的退出登录或重启 App 实现

### 9.2 后续增强

- 图像找点
- 本地 OCR
- 任务模板复用
- 结构化任务编辑器
- 悬浮边缘胶囊调试模式