# Android Instrumentation Triage Repo Reference

本文件是 android-instrumentation-triage skill 的配套长文参考，和 `../SKILL.md` 一起构成同一个自包含 skill 资源。

- `../SKILL.md` 保留可直接执行的触发条件、最小排障流程、命令模板和常见故障速查
- 本文档只保留 repo-specific 边界、证据判读和少量 edge cases，避免与 `SKILL.md` 双写同一份操作卡

## 1. 验证边界

- 本仓库本地 instrumentation 默认走自定义 runner 的 deterministic device-control override，只用于本地 smoke 结论
- 任何关于真实 root shell、无障碍绑定、cron、continuous、前台服务通知、recovery、watchdog、截图和 72 小时稳定性的结论，都必须来自 rooted 真机验证
- 当模拟器与 rooted 真机结果不一致时，以显式 runner override 状态为准，不以 `Build.*` 或 qemu 启发式做主判断

## 2. 证据判读补充

- HTML 报告更适合先定位失败方法和失败顺序，结构化结果更适合对照类级或 suite 污染
- UI 超时场景需要额外结合 logcat 或 Activity lifecycle，确认是节点未刷新、应用退后台，还是外部界面插入

## 3. Repo-specific Edge Cases

### 3.1 单例数据库 seed 纪律

- 这类 smoke 在本仓库只能通过应用单例数据库 seed，优先使用主源码 Hilt EntryPoint 暴露的单例 DB 或主源码 helper
- 第二个 Room 连接或 `clearAllTables` 往往不会真正修复问题，只会把 suite 污染、事务边界错误或 seed 顺序错误暂时藏起来
- 如果出现 SQLite 外键异常，优先检查 TaskRun 和 StepRun 的 seed 顺序，以及是否绕开主事务边界

### 3.2 Superuser 或 su 干扰

- 如果 rooted 真机路径弹出授权界面，不要把结果当作 deterministic 本地 smoke 的失败
- 先确认当前命令是否显式关闭了 deterministic override
- 再确认失败是否来自授权界面抢前台，而不是节点查找或点击链路本身失效