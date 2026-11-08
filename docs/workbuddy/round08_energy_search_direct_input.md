# WorkBuddy 第八轮任务书｜双11能量「搜一搜」优先直接输入关键词

**基线仓库：** `layerverse3d/TaobaoCoinAssistant`  
**开发分支：** `fix/energy-search-direct-input`（由 `master` 创建）  
**基线版本：** `v1.2.0-d11`  
**需求与真机反馈：** 2026-11-08，小米 13 Ultra；用户反馈近期「搜一搜你喜欢的宝贝(0/5)」容易卡在搜索发现页，推荐卡片 OCR 难以定位。**本次尚未提供新完整日志，不能凭推测宣称唯一根因。**

## 0. 最重要的结论

原作者仓库 `Doplars/TaobaoCoinAssistant` 的 `AutomationService.java` 第 70 行附近已有 `SEARCH_KEYS_ASCII = ["iphone", "macbook", "xiaomi", "camera", "milk"]`，并非某个特殊的赚金币任务；它是所有搜索类任务共用的搜索词池。原版 `handleSearchTask()` 在 OCR 找到搜索框后，使用 Shizuku `input text` 输入关键词，再点击提交按钮。

当前 Fork **已经继承**这一能力；问题不是缺少代码，而是双11能量分支在 `handleSearchTask()` 内优先尝试 `tryHandleSearchDiscoveryPage()` 找推荐卡片，失败后又会依赖 `findSearchVisualTarget()` 的 OCR 定位搜索框。用户的需求是**优先利用搜索页自动弹出键盘、搜索框已有焦点**的状态，直接输入并搜索，减少对“推荐卡片 / 搜索框位置” OCR 的依赖。

**禁止简单复制整段原作者方法覆盖当前代码。** 需保留已有 `SearchResult` 三态、金币模式、能量模式和返回任务面板逻辑。

## 1. 真实源码定位（本轮开始前请以新分支最新版本重新核对）

- 原作者：`Doplars/TaobaoCoinAssistant/app/src/main/java/com/local/taobaocoinassistant/AutomationService.java` 约 68–73、559–659 行：`SEARCH_KEYS_ASCII`、`handleSearchTask()`、`submitSearchByVisualTarget()`、`findSearchVisualTarget()`。
- 当前 Fork：`app/src/main/java/com/local/taobaocoinassistant/AutomationService.java` 约 77–82、693–760、813–1060 行：`SEARCH_KEYS_ASCII`、`executeTask()`、`tryHandleSearchDiscoveryPage()`、`SearchResult`、`handleSearchTask()`、`submitSearchByVisualTarget()`。
- 当前 Fork：`TaskParser.java` 约 295 行 `isEnergySearchCandidate()`，约 340 行 `isSearch()`；`TaskRuleStore.java` 约 74 行 `DEFAULT_SEARCH`。
- `ShizukuShell.exec()`：现有 Shell 控制通道；本次不增加 Root、无障碍或外部浏览器依赖。

## 2. P0：能量搜索改为「先用已聚焦输入框，再用旧方法兜底」

### 2.1 进入搜索页后的顺序

仅当 `currentMode == DOUBLE11_ENERGY` 且来源任务属于搜索类时，按以下顺序：

1. 点击任务「去完成」后，确认仍在淘宝包名内；通过「历史搜索 / 搜索发现」等页面特征，结合可用的前台 Activity/输入法状态识别当前为合法搜索页。**不能仅凭键盘弹出就执行输入。**
2. 若可信地确认搜索输入框**已拥有焦点**（真机通常自动弹出键盘），首先尝试不额外点击 OCR 坐标的 `ShizukuShell.exec("input text ...")`。
3. 输入前只对**已认证的搜索输入框**做清除/替换；不要在焦点不明时盲目发送 24 次 DEL。沿用原作者逻辑时需加强前置安全认证。
4. 优先尝试键盘搜索/回车动作（如 `KEYCODE_ENTER`）；随后新截图/前台状态验证已从搜索入口转到结果或浏览页。**Shell 返回成功不是搜索成功证据。**
5. 如未能验证搜索提交，**不**在搜索入口空滑 22 秒；尝试有界的兼容回退：原有 `findSearchVisualTarget()` 输入/提交法（最多一小轮），或仍可认证时的旧 `tryHandleSearchDiscoveryPage()` 推荐项法。不修改这两个 OCR 定位器为本轮主要目标。
6. 所有路径最终复用已有 `returnToTaskList(currentMode)`，回到「做任务赚能量」并核对 (0/5)→(1/5)、(1/5)→(2/5) 等真实进度。有限尝试，无进度则停止重复本项。

### 2.2 兼容规则

- **金币模式原样保留**现有搜索处理优先级，不允许回归。
- 能量模式先直接输入，旧推荐卡片逻辑降级为 fallback，不能删掉。
- 采用现有 `SearchResult`：
  - `FAILED`：安全返回，不浏览；
  - `SUBMITTED_NEEDS_BROWSE`：搜索已提交且结果页确认，外层进入浏览；
  - `HANDLED_AND_BROWSED`：本方法已完成浏览，不额外执行普通 22 秒浏览。
- 本任务只要求浏览 5 秒，建议结果或商品页稳定后 **8–10 秒含适量滑动**，并根据返回任务列表的真实进度确认；若仅在搜索结果页停留未记进度，再有限度地尝试进入安全的商品浏览页，不触发购买/加购/付款/优惠操作。不得在中间页无限搜索或重复操作。
- 遇到安全验证、未知前台页面、明确的支付确认或 `stopRequested`：立即停止交互并安全返回/停止。

## 3. P0：扩充关键词池，兼顾大小写与中文输入限制

原版词池为 `iphone, macbook, xiaomi, camera, milk`。希望用户可见的合理搜索词包含：

| 显示名称 | 首选实际输入（ASCII 稳定方案） |
| --- | --- |
| iPhone | iPhone |
| iPad | iPad |
| MacBook | MacBook |
| 小米 | Xiaomi |
| 咖啡豆 | coffeeBeans（若有可靠空格编码，可用 coffee beans） |
| 相机 | camera |
| 路由器 | router |
| 键盘 | keyboard |
| 固态硬盘 | SSD |
| 3D 打印耗材 | PETG |
| 牛奶 | milk |

说明：`adb shell input text` 一般只能可靠输入 ASCII。**禁止只把词池改为中文汉字就声称能可靠输入中文**；中文如「小米」「咖啡豆」应采用「显示名 + 安全 ASCII 搜索值」映射并准确记录；如果 WorkBuddy 找到当前项目内无需额外输入法/高危权限的可靠 Unicode 输入方法，可作为可选能力实现，但须先验证小米 13 Ultra 上稳定并提供可回退方案，不作为本轮完成的前置条件。

- 请实现单独的关键词选择与 Shell 参数安全处理，严禁把未知 OCR 字符串拼进 Shell；输入值需要 allowlist 且安全转义。
- 大小写按上表；如果淘宝或输入法将其规范化为小写，不视为失败，**以搜索结果与进度确认**。
- 可随机选词，但应避免一次 0/5 循环总是选到同一个词；无需新增复杂配置界面。
- `TaskRuleStore.DEFAULT_SEARCH` 是**任务标题分类规则**，与具体搜索关键词池是两回事；不要把 iPhone / 咖啡豆加到任务黑名单或分类规则里。

## 4. P1：强化判定与可诊断日志

建议关键事件（措辞可调整）：

```text
[能量] SEARCH_CLASSIFY task=搜一搜你喜欢的宝贝(0/5) mode=search
[能量] SEARCH_PAGE confirmed discovery=true imeVisible=true focused=confirmed
[能量] SEARCH_INPUT path=direct-ime label=小米 query=Xiaomi
[能量] SEARCH_SUBMIT path=enter confirmed=true page=results
[能量] SEARCH_BROWSE duration=8s result=completed
[能量] SEARCH_RETURN backSteps=2 panelConfirmed=true
[能量] SEARCH_PROGRESS 0/5 -> 1/5
```

失败时必须能区分：
- `PAGE_NOT_CONFIRMED`；
- `FOCUS_NOT_CONFIRMED`；
- `SHELL_INPUT_FAILED`；
- `SUBMIT_NOT_CONFIRMED`；
- `RESULT_NOT_ENTERED`；
- `PROGRESS_NOT_ADVANCED`。

不输出搜索历史或账号信息全文，不记录截图等敏感内容，禁止把“已输入”直接日志写成“已完成任务”。

## 5. 不允许回归的既有功能

金币模式浏览及搜索；双十一能量浏览、重复任务、考试答题、累计奖励；共享黑名单（含每拉、天猫积分、积分换能量、淘特、快手、微博）；顶部悬浮状态栏与原地重新开始；最新/复制/清空日志；Shizuku 权限与包名设置。

不能自动移除原作者署名、修改许可证、删除用户设置、重命名正式主分支或向上游发 PR。

## 6. 验收与交付

**自动化检查：**
1. 代码编译通过，确认 App 不增加不必要的新权限。
2. 关键词数组大小写、合法字符与随机选择的纯逻辑测试；中文显示名与实际搜索值映射测试。
3. 模拟状态机测试：已聚焦搜索 → direct input → submit → results → 浏览 → 返回；焦点未知回退；提交失败不误判成功；黑名单优先；停止立即生效；金币模式保持原处理顺序。
4. 检查提交只改这轮相关文件，不能意外影响上游或 `master`。

**小米 13 Ultra 真机验收（用户完成）：**
- 进入 `搜一搜你喜欢的宝贝(0/5)`，键盘已弹出时，不点击推荐卡片即可通过输入关键词进入搜索结果；
- 搜索提交、滑动浏览、返回面板；任务进度确实从 0/5 增长；连续完成 5 次或直至当天任务限额；
- 遇到未弹出键盘、焦点丢失、关键词无法输入时不乱点击，能安全回退；
- 金币模式及能量其他浏览任务不回归。

**交付：**
- 仅提交到 `fix/energy-search-direct-input`，分组 commit，提供 SHA、代码 diff 摘要、GitHub Actions 构建链接与测试 APK；
- 测试安装包保留现有 Application ID 约定；如果是 Debug 临时签名，说明可能无法覆盖安装，不得默认删除旧 App/用户设置；
- **不要自动合并到 `master`，不要创建正式 Release，不要触碰原作者仓库。** 用户真机验收后，由 ChatGPT 审核再做 PR、合并到本人的 `master`，并考虑下一版标签 `v1.2.1-d11` / README 说明。

## 7. WorkBuddy 开工指令

先读本文件和当前 GitHub 分支代码，再定位原作者 `SEARCH_KEYS_ASCII` / `submitSearchByVisualTarget` 的已有实现。**优先做“搜索页已自动聚焦 → 不需要 OCR 点输入框 → Shizuku 输入 → Enter 提交 → 证实结果页面”**。尽量复用旧代码而非重写状态机。保持每步日志可追踪，提交到指定功能分支，并通过 CI 提供测试包；无法由代码证明的页面行为标为待真机验收。
