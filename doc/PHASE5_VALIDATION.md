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
- 目标测试 App 已安装，测试账号和任务配置已导入
- 设备已关闭会明显干扰调度的电池优化或自启动限制
- 时区与任务配置预期一致，若用默认样例则保持 Asia/Shanghai

## 3. 可执行入口

### 3.1 本地窄验证

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.plearn.appcontrol.ui.AppControlAppFormattingTest"
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

### 3.2 设备冒烟验证

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

预期结果：

- App 可以启动到主界面
- 顶部摘要卡片可见
- 任务导入入口、环境检查入口、点击链路 smoke 验证入口可滚动到并显示
- `AppControlAppSmokeTest` 会验证任务编辑器中的样例 JSON 经唯一 taskId/name 改写后可被导入，且导入结果会刷新到读侧任务视图
- `DeviceValidationUiSmokeTest` 会在独立的 deterministic UI harness 中验证点击“检查环境”后，环境卡片能刷新出 `Root`、`Accessibility enabled`、`Accessibility connected`、`Foreground package` 四行结果

### 3.3 模拟器预检步骤

适用目标：在 Android 9/10 模拟器上先验证 UI 启动、任务导入写读侧链路、入口可见性与设备验证入口的文本刷新护栏，不替代 rooted 真机验收。

模拟器预检前置条件：

- 设备已连接，且 Android 版本为 9 或 10
- `sys.boot_completed` 返回 `1`
- AppControl Debug 包与 debugAndroidTest 包可安装
- 若镜像支持，可执行 `adb root`

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
4. 运行 androidTest 套件，确认 `AppControlAppSmokeTest` 通过，主界面、任务导入写读侧链路和入口可见性正常。
5. 检查 `DeviceValidationUiSmokeTest` 已通过，证明设备验证入口中的“检查环境”按钮能驱动环境文本刷新护栏。

已验证基线：

- 2026-05-11：`emulator-5554`，Android 9，`.\gradlew.bat :app:connectedDebugAndroidTest` 通过。

建议记录的证据：

- `adb devices` 输出
- `ro.build.version.release` 与 `sys.boot_completed` 输出
- `connectedDebugAndroidTest` 通过日志

### 3.4 Rooted 真机后续验证

模拟器预检只覆盖 UI 启动、任务导入写读侧链路、入口可见性和 deterministic 文本刷新护栏。以下能力仍必须在 Android 9/10 rooted 真机上完成：

- 无障碍服务启用与连接闭环
- 手动真实执行与步骤诊断产物落库
- cron 与 continuous 调度行为
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
| 模拟器预检 | 运行 androidTest 套件 | MainActivity 主界面可见，唯一任务导入会刷新到读侧任务视图，且 deterministic UI harness 中环境检查按钮点击后出现四行环境文本 | Passed on 2026-05-11 | connectedDebugAndroidTest on emulator-5554 |
| 应用启动冒烟 | 运行 AppControlAppSmokeTest | 主界面、入口文案以及唯一任务导入后的读侧刷新正常 | Passed on 2026-05-11 | connectedDebugAndroidTest on emulator-5554 |
| 环境检查（rooted 真机） | 点击“检查环境” | Root/Accessibility/Foreground package 的真实设备状态正确显示 | Pending | Pending |
| 手动真实执行 | 从任务列表触发手动运行 | 生成 taskRun、stepRun 与诊断证据 | Pending | Pending |
| cron 调度 | 启用 cron 任务并等待触发 | 调度待命状态正确，任务按 cron 触发 | Pending | Pending |
| continuous 轮转 | 启用 continuous 任务并观察多轮 | 轮次推进、账号切换和会话记录一致 | Pending | Pending |
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
- [ ] 必要的 androidTest 或真机验证通过
- [ ] 关键错误码、日志与诊断链路已复核
- [ ] 代码评审通过
- [ ] 未引入已知稳定性回退
- [ ] MVP 验收项已逐条填写证据
- [ ] 72 小时稳定性报告已归档