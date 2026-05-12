# Phase 5 验证与验收记录

## 1. 目的

本文件用于承接 Phase 5 的三类输出：

- 真机或模拟器可执行的 androidTest 冒烟入口
- 设备验证与手动回归记录
- PRD MVP 验收与 72 小时稳定性记录

当前仓库已提供的直接入口：

- instrumentation 冒烟测试：[app/src/androidTest/java/com/plearn/appcontrol/ui/AppControlAppSmokeTest.kt](../app/src/androidTest/java/com/plearn/appcontrol/ui/AppControlAppSmokeTest.kt)
- 设备验证 UI 护栏测试：[app/src/androidTest/java/com/plearn/appcontrol/ui/DeviceValidationUiSmokeTest.kt](../app/src/androidTest/java/com/plearn/appcontrol/ui/DeviceValidationUiSmokeTest.kt)
- smoke check 文案护栏测试：[app/src/test/java/com/plearn/appcontrol/ui/AppControlAppFormattingTest.kt](../app/src/test/java/com/plearn/appcontrol/ui/AppControlAppFormattingTest.kt)

## 2. Rooted 真机验证前置条件

- 设备为 Android 9 或 Android 10
- 设备已 root，且 root shell 可正常执行
- AppControl Debug 包与 debugAndroidTest 包已安装
- 无障碍服务已启用并已连接
- 前台服务通知可正常显示，通知栏可见运行中状态与停止入口
- 目标测试 App 已安装，测试账号和任务配置已导入
- 设备已关闭会明显干扰调度的电池优化或自启动限制
- 时区与任务配置预期一致，若用默认样例则保持 Asia/Shanghai
- 若要验证 continuous，设备必须是 dedicated 测试机，避免人工前台干扰与其他任务抢占

## 3. 可执行入口

### 3.1 本地窄验证

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.plearn.appcontrol.ui.AppControlAppFormattingTest"
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

建议顺序：

1. 先跑 `testDebugUnitTest` 或单个 formatter guardrail，确认纯 Kotlin 或 UI 文案映射没有回归。
2. 再跑 `compileDebugAndroidTestKotlin`，把 Hilt、Room、androidTest 依赖和 Compose 编译问题提前暴露出来。
3. 只有本地窄验证稳定后，才进入类级或整套 instrumentation。

### 3.2 设备冒烟验证

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

说明：当前仓库的自定义 instrumentation runner 默认会开启 deterministic device-control override，因此上面的命令默认用于本地模拟器或本地设备 smoke，不直接等同于 rooted 真机验收。

如果要在 rooted 真机上复用同一套 instrumentation 并走真实 root 路径，必须显式关闭 override：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.appcontrol.deterministicDeviceControl=false"
```

PowerShell 下的定向 instrumentation 命令建议固定使用以下写法，必须把整个 `-P...` 参数放进引号，避免属性被错误拆分：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.plearn.appcontrol.ui.AppControlAppSmokeTest#shouldShowFailureContextForFailedManualRun"
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.plearn.appcontrol.ui.AppControlAppSmokeTest"
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.appcontrol.deterministicDeviceControl=false"
```

预期结果：

- App 可以启动到主界面
- 顶部摘要卡片可见
- 任务导入入口、环境检查入口、点击链路 smoke 验证入口可滚动到并显示
- `AppControlAppSmokeTest` 会验证任务编辑器中的样例 JSON 经唯一 taskId/name 改写后可被导入，任务停用再启用的往返操作会刷新到读侧任务视图，可从任务行把当前仓库中的任务 JSON 重新载回编辑器，并可打开任务监控详情读取与刷新调度摘要，以及在手动运行后看到 recent run、step 记录和任务列表 latest 摘要刷新
- `DeviceValidationUiSmokeTest` 会在独立的 deterministic UI harness 中验证点击“检查环境”后，环境卡片能刷新出 `Root`、`Accessibility enabled`、`Accessibility connected`、`Foreground package` 四行结果

### 3.2.1 首次失败排障顺序

当 instrumentation 首次失败时，建议固定按以下顺序排障，而不是直接扩大 timeout 或改 helper：

1. 先看 `compileDebugAndroidTestKotlin` 是否已暴露编译、Hilt、Room 或 androidTest 依赖问题。
2. 如果失败来自整套 `connectedDebugAndroidTest`，先回退到单方法，再回退到整类，确认是局部缺陷还是 suite 顺序污染。
3. 再看 `app/build/reports/androidTests/connected/debug/index.html` 的 HTML 报告，以及 `app/build/outputs/androidTest-results/connected/debug` 下的结构化结果，确认失败点是方法级、类级还是整套顺序污染。
4. 再抓 logcat 与 Activity lifecycle，优先排查是否有外部 Activity 抢前台、应用进程崩溃、SQLite 约束异常或 root 授权界面插入。
5. 最后才调整 wait helper、timeout、seed 方式或环境探测逻辑。

### 3.3 模拟器预检步骤

适用目标：在 Android 9/10 模拟器上先验证 UI 启动、任务导入与启停往返写读侧链路、当前仓库中的任务 JSON 载回编辑器、任务详情调度摘要读侧与停用后的刷新、手动运行后的 recent run/step 详情刷新与任务列表 latest 摘要刷新、入口可见性与设备验证入口的文本刷新护栏，不替代 rooted 真机验收。

模拟器预检前置条件：

- 设备已连接，且 Android 版本为 9 或 10
- `sys.boot_completed` 返回 `1`
- AppControl Debug 包与 debugAndroidTest 包可安装
- 若镜像支持，可执行 `adb root`
- androidTest 通过自定义 instrumentation runner 默认启用 deterministic device-control override，避免本地模拟器 smoke 依赖交互式 `su` 授权；若后续要在 rooted 真机上复用 instrumentation，可通过 `-Pandroid.testInstrumentationRunnerArguments.appcontrol.deterministicDeviceControl=false` 显式关闭
- 当前代码中仍保留 `Build.*` 启发式 fallback，但验证口径上不依赖它来判定本地环境；若需要 deterministic 路径，以显式 runner override 为准

```powershell
adb devices
adb -s <device-id> shell getprop ro.build.version.release
adb -s <device-id> shell getprop sys.boot_completed
adb -s <device-id> root
.\gradlew.bat :app:connectedDebugAndroidTest
```

执行步骤：

1. 确认设备已连接，且 Android 版本为 9 或 10。
2. 确认 `sys.boot_completed` 返回 `1`，避免在系统未完全启动时触发 UI 用例。
3. 若镜像支持，执行 `adb root`，用于尽早暴露 root shell 差异。
4. 运行 androidTest 套件，确认 `AppControlAppSmokeTest` 通过，主界面、任务导入与启停往返写读侧链路、当前仓库中的任务 JSON 载回编辑器、任务详情调度摘要读侧与停用后的刷新、手动运行后的 recent run/step 详情刷新、任务列表 latest 摘要刷新、recent run 详情切换，以及 failure context 读侧展示正常。
5. 检查 `DeviceValidationUiSmokeTest` 已通过，证明设备验证入口中的“检查环境”按钮能驱动环境文本刷新护栏。
6. 若 smoke 依赖预置任务、recent run 或 failure context，seed 只能走应用单例数据库或主源码 EntryPoint；不要在 androidTest 里新开 Room 连接或直接清库。

已验证基线：

- 2026-05-12：`emulator-5554`，Android 9，`.\gradlew.bat :app:connectedDebugAndroidTest` 通过，共 12 条 instrumentation 用例。

当前模拟器自动化覆盖快照：

| 用例 | 覆盖内容 | 结果 |
| --- | --- | --- |
| `AppControlAppSmokeTest.shouldRenderDashboardSummaryCardsOnLaunch` | MainActivity 主界面与摘要卡片启动 | Passed on 2026-05-12 |
| `AppControlAppSmokeTest.shouldExposeTaskAndDeviceValidationEntryPoints` | 任务导入、环境检查、点击链路入口可见 | Passed on 2026-05-12 |
| `AppControlAppSmokeTest.shouldImportDefaultTaskAndShowItInTaskList` | 唯一任务导入后读侧任务行可见 | Passed on 2026-05-12 |
| `AppControlAppSmokeTest.shouldToggleImportedTaskDisabledAndEnabledAgain` | 唯一任务停用再启用的写读侧往返刷新 | Passed on 2026-05-12 |
| `AppControlAppSmokeTest.shouldLoadImportedTaskJsonBackIntoEditor` | 任务行“载入 JSON”会把当前仓库中的任务 JSON 重新写回编辑器 | Passed on 2026-05-12 |
| `AppControlAppSmokeTest.shouldOpenImportedTaskDetailAndShowScheduleSummary` | 唯一任务可从任务行打开监控详情，并显示调度摘要 | Passed on 2026-05-12 |
| `AppControlAppSmokeTest.shouldRefreshTaskDetailScheduleSummaryAfterDisablingSelectedTask` | 已打开详情的唯一任务在停用后，详情调度摘要会刷新为 `standby=false` | Passed on 2026-05-12 |
| `AppControlAppSmokeTest.shouldShowRecentRunAndStepRecordsAfterManualRun` | 手动运行唯一任务后，详情会出现 latest manual run 与 `step-start-app` 步骤记录 | Passed on 2026-05-12 |
| `AppControlAppSmokeTest.shouldRefreshTaskLatestSummaryAfterManualRun` | 手动运行唯一任务后，任务列表 latest 摘要会刷新为 manual | Passed on 2026-05-12 |
| `AppControlAppSmokeTest.shouldSwitchSelectedRunWhenChoosingOlderRunDetail` | 任务详情页默认选中最新 run，并可切换回 older recent run 查看步骤详情 | Passed on 2026-05-12 |
| `AppControlAppSmokeTest.shouldShowFailureContextForFailedManualRun` | 手动运行 runtime-invalid package 任务后，详情会展示 failed 状态与 `STEP_INVALID_ARGUMENT` failure context | Passed on 2026-05-12 |
| `DeviceValidationUiSmokeTest.shouldRenderEnvironmentDetailsAfterInspectEnvironmentClick` | 设备验证入口的 deterministic 环境文本刷新 | Passed on 2026-05-12 |

建议记录的证据：

- `adb devices` 输出
- `ro.build.version.release` 与 `sys.boot_completed` 输出
- `connectedDebugAndroidTest` 通过日志

### 3.4 Rooted 真机后续验证

模拟器预检只覆盖 UI 启动、任务导入与启停往返写读侧链路、当前仓库中的任务 JSON 载回编辑器、任务详情调度摘要读侧与停用后的刷新、手动运行后的 recent run/step 详情刷新、任务列表 latest 摘要刷新、recent run 详情切换、failure context 读侧展示、入口可见性和 deterministic 文本刷新护栏。deterministic override 是正式的本地测试路径，不是生产能力的替代实现。以下能力仍必须在 Android 9/10 rooted 真机上完成：

- 新任务或修改后的任务先做本机手动真实执行，再进入测试机调度待命或 continuous 验收
- 无障碍服务启用与连接闭环
- 前台服务通知、停止入口和运行中状态展示
- 手动真实执行与步骤诊断产物落库
- 环境检查中的通知状态、目标 App 安装状态，以及电池优化、自启动、时区引导
- cron 与 continuous 调度行为
- continuous 会话中的当前轮次、当前账号和下一轮状态可见性
- 若本轮改动涉及 scheduler recovery 或 watchdog，还必须验证进程恢复或设备重启后的恢复链路
- 失败截图或抑制原因链路
- 72 小时稳定性验证

## 4. 真机验证记录模板

### 4.1 设备信息

| 字段 | 值 |
| --- | --- |
| 设备型号 | Pending |
| Android 版本 | Pending |
| Root 方案 | Pending |
| 构建版本 | Pending |
| 测试日期 | Pending |
| 测试人 | Pending |

### 4.2 回归场景矩阵

| 场景 | 步骤 | 预期 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 模拟器预检 | 运行 androidTest 套件 | MainActivity 主界面可见，唯一任务导入与停用再启用会刷新到读侧任务视图，当前仓库中的任务 JSON 可载回编辑器，可打开任务详情读取调度摘要，并可在停用后看到 `standby=false`，手动运行后可看到 recent run、step 记录、latest manual 摘要和 failure context，且可在详情页切换 older recent run；deterministic UI harness 中环境检查按钮点击后出现四行环境文本 | Passed on 2026-05-12 | connectedDebugAndroidTest on emulator-5554 |
| 应用启动冒烟 | 运行 AppControlAppSmokeTest | 主界面、入口文案以及唯一任务导入、停用再启用、当前仓库中的任务 JSON 载回编辑器、任务详情调度摘要读侧与停用后的刷新、手动运行后的 recent run/step 详情刷新、latest manual 摘要刷新、recent run 详情切换和 failure context 展示正常 | Passed on 2026-05-12 | connectedDebugAndroidTest on emulator-5554 |
| 环境检查（rooted 真机） | 点击“检查环境” | Root/Accessibility/Foreground package/通知状态/目标 App 安装状态正确显示，并能提示电池优化、自启动、时区等设置风险 | Pending | Pending |
| 手动真实执行 | 从任务列表触发手动运行 | 生成 taskRun、stepRun 与诊断证据 | Pending | Pending |
| 前台服务通知 | 启动手动运行或调度运行 | 通知栏可见运行中状态、当前步骤摘要与停止入口 | Pending | Pending |
| cron 调度 | 启用 cron 任务并等待触发 | 调度待命状态正确，任务按 cron 触发 | Pending | Pending |
| continuous 轮转 | 启用 continuous 任务并观察多轮 | 轮次推进、账号切换、当前轮次/当前账号展示和会话记录一致 | Pending | Pending |
| 恢复与 Watchdog | 杀掉前台服务或重启设备后恢复 | 若设计要求恢复，调度服务可恢复待命与会话状态；若无需恢复，系统明确记录原因且不产生静默丢调度 | Pending | Pending |
| 失败诊断 | 注入失败场景 | 保留截图或抑制原因，日志可定位 | Pending | Pending |

## 5. PRD MVP 验收清单

来源：[doc/PRD.md](PRD.md) 第 15 节。

| 验收项 | 状态 | 证据 | 备注 |
| --- | --- | --- | --- |
| 通过 JSON 配置导入或原始文本编辑创建、修改、启停任务 | Pending | Pending | |
| 支持启动、重启、点击、滑动、输入 | Pending | Pending | |
| 支持基于元素定位，并可通过截图或截图抑制原因排障 | Pending | Pending | |
| 支持分钟级 cron 风格调度 | Pending | Pending | |
| 支持 continuous 连续循环执行与账号组顺序切换 | Pending | Pending | |
| 失败时输出日志与诊断证据，敏感场景输出明确抑制原因 | Pending | Pending | |
| 新增任务无需重新发版即可生效 | Pending | Pending | |
| 本机手动真实执行模式可用 | Pending | Pending | |
| 测试机运行模式可用，并能看到待命状态、当前轮次和当前账号 | Pending | Pending | |
| rooted 测试机完成至少 72 小时稳定性验证 | Pending | Pending | |

## 6. 72 小时稳定性记录模板

### 6.1 运行配置

| 字段 | 值 |
| --- | --- |
| 开始时间 | Pending |
| 结束时间 | Pending |
| 总时长 | Pending |
| 目标任务 | Pending |
| 触发方式 | Pending |
| 账号组 | Pending |
| 设备温控/供电策略 | Pending |

### 6.2 指标记录

| 指标 | 值 | 备注 |
| --- | --- | --- |
| 总运行次数 | Pending | |
| 成功次数 | Pending | |
| 失败次数 | Pending | |
| 超时次数 | Pending | |
| 取消次数 | Pending | |
| 调度丢触发次数 | Pending | |
| 连续运行中断次数 | Pending | |
| diagnostics 清理触发次数 | Pending | |

### 6.3 事件与缺陷

| 时间 | 现象 | 影响级别 | 处理动作 | 证据 |
| --- | --- | --- | --- | --- |
| Pending | Pending | Pending | Pending | Pending |

### 6.4 结论

- 72 小时是否通过：Pending
- 是否存在 P0/P1 缺陷：Pending
- 发布阻断项：Pending
- 下一步修复或观察项：Pending

## 7. DoD 复核清单

- [ ] 需求与设计文档已同步
- [ ] 单元测试通过
- [ ] 必要的 androidTest 与对应 rooted 真机验证通过
- [ ] 关键错误码、日志与诊断链路已复核
- [ ] 代码评审通过
- [ ] 未引入已知稳定性回退
- [ ] MVP 验收项已逐条填写证据
- [ ] 72 小时稳定性报告已归档