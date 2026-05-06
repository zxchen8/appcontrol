## Plan: AppControl 首版实施计划

基于现有仓库“仅文档、无代码”的状态，按“先工程骨架与 DSL、再执行能力、再调度与稳定性”的路径推进，严格以 doc 设计主源与 rules 开发规则为准，目标是交付可在 Android 9/10 rooted 测试机稳定运行的 v1。

**Steps**
1. Phase 1 - 基线冻结与脚手架落地：确认并冻结首版范围（v1 必做/不做、Android 9/10 边界、手动执行不进入 scheduler、OCR 暂不启用），创建 Android Kotlin 单模块工程骨架（app），补齐构建链路、依赖清单与基础分层目录。*后续所有阶段依赖此阶段*
2. Phase 1 - 设计契约落地：把 DETAILED_DESIGN 中任务 DSL、执行策略默认值、状态语义转换为可执行契约（Schema/解析规则/校验规则/错误码枚举）。先做解析与校验闭环，再做 UI 与调度接入。*depends on 1*
3. Phase 2 - 数据层与仓储边界：实现 Room 实体、DAO、Repository 边界（任务定义、调度状态、凭据、会话、运行记录、步骤记录），并落实事务边界（游标推进 + 新轮运行记录创建；任务结束回写 + 会话统计回写）。*depends on 2*
4. Phase 2 - 能力适配层 MVP：实现 device-control（start/stop/restart/tap/swipe/input）、accessibility 基础查询与点击闭环，形成 capability facade 统一入口；预留 vision OCR 插槽但默认禁用。*depends on 1, parallel with 3 after common model stable*
5. Phase 3 - runner-engine 状态机：实现运行实例生命周期（idle/preparing/running_step/retrying/success/failed/timed_out/cancelled/blocked）、步骤级超时重试、任务级重试、取消语义、统一结果结构。*depends on 3 and 4*
6. Phase 3 - scheduler：实现 cron（分钟级）与 continuous（轮次、冷却、maxCycles/maxDuration）、执行锁与冲突处理（conflictPolicy）、错过窗口策略（onMissedSchedule=skip）、服务恢复与开机恢复。*depends on 3 and 5*
7. Phase 4 - app-service 与 UI 最小闭环：实现环境检查页、任务列表/启停、原始 JSON 导入编辑、手动真实执行入口、运行监控页、运行记录页、账号配置页；确保 UI 不直接调用高权限能力。*depends on 5 and 6*
8. Phase 4 - 诊断与保留策略：结构化日志、错误码、失败截图与抑制原因统一链路，加入产物保留上限、容量水位清理与退化策略。*depends on 5, parallel with 7 then integrate*
9. Phase 5 - 测试与稳定性收敛：按规则完成单元/集成/设备测试；重点覆盖解析器、引擎、调度、凭据与变量替换、日志脱敏、截图抑制、账号轮换；执行 72 小时稳定性验证并修复高优缺陷。*depends on 6,7,8*
10. Phase 5 - 发布前验收：对照 PRD MVP 验收项逐条打勾，完成 DoD（测试、日志、错误处理、评审）并形成可追溯验收记录。*depends on 9*

**Relevant files**
- d:/PLearn/appcontrol/doc/PRD.md - 产品范围、MVP 验收标准、里程碑目标
- d:/PLearn/appcontrol/doc/HIGH_LEVEL_DESIGN.md - 架构分层、模块边界、调度高层语义
- d:/PLearn/appcontrol/doc/DETAILED_DESIGN.md - DSL 字段语义、状态语义、数据模型与记录结构主源
- d:/PLearn/appcontrol/rules/DEVELOPMENT_RULES.md - 测试覆盖率、日志/安全/DoD 强制规则
- d:/PLearn/appcontrol/src - 当前为空；按阶段落地工程与核心实现
- d:/PLearn/appcontrol/test - 当前为空；按阶段建立单元/集成/设备测试目录

**Verification**
1. 契约一致性验证：用文档驱动用例校验 DSL 解析默认值、字段约束与错误码映射，确保实现与详细设计一致。
2. 调度语义验证：构造 cron 与 continuous 并存、执行锁占用、服务恢复/重启恢复场景，验证 conflictPolicy 与 onMissedSchedule 行为。
3. 引擎健壮性验证：覆盖步骤成功/失败/超时/取消、步骤级与任务级重试计数口径一致性。
4. 安全与诊断验证：验证敏感变量日志脱敏、截图抑制条件、抑制原因可追踪、产物清理策略可触发。
5. 设备闭环验证：在 Android 9/10 rooted 测试机完成环境检查、手动执行、定时执行、连续轮换执行全链路测试。
6. 稳定性验证：完成至少一次 72 小时连续运行测试并记录稳定性指标。
7. 验收验证：按 PRD 第 15 节逐项验收，输出通过/失败与证据链接。

**Decisions**
- 包含范围：v1 必做能力（手动真实执行、cron、continuous、账号轮换、诊断链路、本地存储）。
- 排除范围：多机集中管理、可视化流程编排、模板管理 UI、远程上传导出、OCR 强制启用。
- 平台边界：只验证 Android 9/10 rooted；不扩大到 Android 11+ 行为适配。
- 实施策略：先实现统一契约与执行链路，再补 UI；避免页面逻辑反向绑定执行细节。

**Further Considerations**
1. 若资源有限，建议优先完成“单任务 + 单账号 + cron + 手动执行 + 基础诊断”的可运行纵切，再增量接入 continuous 与账号轮换。
2. 建议在 Phase 1 末即定义统一错误码命名规范与日志字段规范，减少后续跨模块返工。
3. 建议提前准备至少 1 台 Android 9 与 1 台 Android 10 rooted 设备，避免后期设备验证阻塞。

**Execution Breakdown (可直接分配)**
1. Phase 1A 工程初始化
- 产出：Android 工程可编译、基础依赖可解析、分层包目录创建完成。
- 子任务：初始化 app 模块、配置 minSdk/targetSdk/compileSdk、接入 Hilt/Room/Serialization/Coroutines、建立基础 CI 命令。
- 验收门槛：本地 Debug 构建通过；空白启动页可运行；依赖锁定清单可追踪。
2. Phase 1B DSL 契约测试先行
- 产出：任务顶层字段、trigger、executionPolicy、diagnostics、steps 的解析与校验用例。
- 子任务：先写失败用例（非法字段/缺省值/边界值），再实现 parser 与 validator。
- 验收门槛：DSL 核心测试通过，错误码与文档定义一致。
3. Phase 2A 数据模型与迁移策略
- 产出：TaskDefinition/TaskScheduleState/Credential/Session/TaskRun/StepRun 的实体与 DAO。
- 子任务：定义索引、外键、唯一约束；补齐 schema migration 与回滚策略。
- 验收门槛：Room migration 测试通过；关键查询与事务用例通过。
4. Phase 2B Repository 与事务边界
- 产出：TaskRepository/CredentialRepository/SessionRepository/RunRecordRepository。
- 子任务：封装 DAO，禁止上层直接触达 Entity；实现“游标推进+轮次创建”与“任务结束+会话回写”事务。
- 验收门槛：并发与一致性测试通过；接口契约稳定。
5. Phase 2C 能力层最小闭环
- 产出：device-control 与 accessibility 的 facade 接口与默认实现。
- 子任务：root shell 命令执行器、元素查询/等待/点击、input_text 脱敏日志摘要。
- 验收门槛：设备侧最小动作链（启动->点击/输入->停止）可复现。
6. Phase 3A 执行引擎状态机
- 产出：任务实例与步骤实例状态机、取消与超时控制、步骤级与任务级重试。
- 子任务：执行上下文模型、状态迁移守卫、统一结果对象。
- 验收门槛：状态迁移表全覆盖测试；异常路径无未分类错误。
7. Phase 3B 调度器实现
- 产出：cron 与 continuous 调度、执行锁冲突语义、服务恢复语义。
- 子任务：分钟级触发计算、轮次冷却、maxCycles/maxDuration、冲突与 miss 处理。
- 验收门槛：调度仿真测试通过；重启恢复后状态一致。
8. Phase 4A 应用服务层与 UI 串联
- 产出：手动真实执行入口、任务启停、监控页实时状态、运行记录查询。
- 子任务：ViewModel 状态模型、前台服务通知、停止动作联动。
- 验收门槛：从任务编辑到执行完成的端到端流程可演示。
9. Phase 4B 诊断治理
- 产出：结构化日志、错误码分层、截图与抑制原因共享引用、清理策略。
- 子任务：日志字段规范、保留上限、磁盘水位退化、抑制原因可追踪。
- 验收门槛：故障注入后可定位到步骤级；敏感输入场景不泄漏明文。
10. Phase 5 验证与发布收口
- 产出：测试报告、72 小时稳定性报告、MVP 验收清单。
- 子任务：核心模块覆盖率达标、真机回归、已知风险与遗留项归档。
- 验收门槛：满足 DoD 与 PRD 验收标准，形成可审计交付包。

**Parallelization Map**
1. 可并行：Phase 2A 与 Phase 2C 在领域模型冻结后并行推进。
2. 可并行：Phase 4A 与 Phase 4B 在引擎事件模型稳定后并行推进。
3. 串行依赖：Phase 3B 必须在 Phase 3A 稳定后执行；Phase 5 必须等待 4A/4B 全量合并。

**Risk Burn-down（前置化）**
1. 设备风险：Phase 1 即准备 Android 9/10 rooted 设备池与可重复刷机方案。
2. 调度风险：Phase 3 前先实现时间推进仿真测试工具，避免仅靠真机验证。
3. 凭据风险：Phase 2 明确 Keystore 不可用降级策略与统一错误码。
4. 观测风险：Phase 2 末冻结日志字段，避免后续跨模块重构。

**Delivery Mode（由我主导端到端开发）**
1. 执行方式：由我按既定 Phase 顺序持续实现、测试、收敛并汇报，不再使用按周时间排期。
2. 推进节奏：以“完成一个可验证子闭环再进入下一闭环”为准，不以日期驱动。
3. 交付门槛：每个 Phase 必须同时满足功能完成、测试通过、日志与错误码完整、风险项关闭后才进入下一 Phase。

**Multi-Agent Strategy（是否需要并行 Agent）**
1. 结论：需要，但只在“可并行且低耦合”的环节使用多 Agent；核心实现仍由主 Agent 统一落地，避免架构分叉。
2. 推荐并行场景：
- 并行代码审查：每个关键 Phase 完成后，使用 code-reviewer 并行审查高风险模块。
- 并行测试设计：核心状态机与调度语义变更后，使用 tdd-guide 补齐边界测试矩阵。
- 并行安全检查：凭据、日志脱敏、截图抑制链路变更后，使用 security-reviewer 做专项审查。
3. 不建议并行场景：
- 同一核心文件的同时改动。
- 状态机与调度规则尚未冻结前的并行实现。
- 跨模块接口未定时的大规模并行开发。

**Coordination Rules（并行协作约束）**
1. 主 Agent 负责：架构决策、接口冻结、最终代码合并与回归。
2. 子 Agent 负责：只读探索、专项审查、测试建议，不直接覆盖核心实现决策。
3. 合并策略：先冻结接口契约，再并行处理低耦合任务；每次并行后统一跑全量测试再继续。

**Tracking Metrics（按阶段更新）**
1. 阶段完成率：当前 Phase 子任务完成数/总数。
2. 质量指标：核心模块覆盖率、P0/P1 缺陷打开与关闭趋势。
3. 稳定性指标：调度丢触发次数、任务失败率、连续运行中断次数。
4. 安全指标：敏感日志违规数、截图抑制误判率、凭据读取失败率。

**Fallback Plan（进度或风险失控时）**
1. 回退到 MVP 纵切：单任务 + 单账号 + cron + 手动执行 + 基础诊断先交付。
2. continuous 与账号轮换延后到下一迭代，但保留数据模型与接口。
3. OCR/图像能力保持接口预留，不进入本期交付范围。