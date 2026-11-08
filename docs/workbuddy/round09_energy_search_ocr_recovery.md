# 第九轮 WorkBuddy 修复任务书：能量搜索页 OCR 容错与双路径回退
**日期**：2026-11-08  
**仓库**：`layerverse3d/TaobaoCoinAssistant`  
**仅允许修改**：`fix/energy-search-direct-input`  
**当前测试版本**：`1.2.0-d11-r8`（以最新分支实际构建为准）  
**证据**：随交付 ZIP 附 `1.txt`（能量）、`2.txt`（金币节选）、3 张真机图。原始日志/图含个人搜索词，**请勿提交到公开 GitHub 仓库**。本文件仅含必要匿名化示例。

## 一、结论与故障链（已由本轮代码+日志支持）

**不是缺少搜索关键词功能，也不应回滚/完整复制原作者搜索逻辑。** 原作者的 Shizuku + OCR 搜索输入已经被本 Fork 继承。第八轮的 direct-IME 方案至少连续两次被真机验证：
- 能量 14:41:11–14:41:36：`SEARCH_INPUT path=direct-ime label=iPhone query=iPhone`；`SEARCH_SUBMIT ... confirmed=true`；任务由 **1/5→2/5**。
- 能量 14:41:43–14:42:08：`iPad`；任务由 **2/5→3/5**。
- 14:42:19 起：`SEARCH_PAGE page_not_confirmed reason=PAGE_NOT_CONFIRMED`；后备 OCR 8 次均找不到输入栏，任务停在 **3/5**；同项两次失败后跳过。
- 失败帧已经识别到 **`搜素发现`**（“索”被 OCR 误成“素”）与多项搜索推荐词；截图显示实际淘宝搜索界面仍在，顶部有搜索框和橙色「搜索」，输入法已弹出。

**准确源码位置（第八轮开发分支 `AutomationService.java`，以实际文件再次核对）：**
- `isSearchDiscoveryPage()` 约第 837 行：目前只 `all.contains("搜索发现") || all.contains("历史搜索")`，对 `搜素发现` 不容错。
- `findSearchDiscoveryTarget()` 约第 857 行：寻找锚点也要求「搜索发现/历史搜索」精确匹配；即使进入 fallback，也可能同样找不到锚点。
- `isEnergySearchPage()` 约第 1109 行：仅接受上述判断或 `findSearchVisualTarget()`。
- `findSearchVisualTarget()` 约第 1223 行：当前页面证据仅接受「搜索有福利」「搜索后浏览」「搜索宝贝」，**不包含截图这种只显示「搜索」+搜索发现推荐列表的新版界面**。
- `tryDirectImeSearch()` 约第 1123 行：无法确认页面就返回 null，随后 fallback 的推荐卡和传统搜索框检测同时使用上述严格规则，因此三条路径一起失败。
- `tryDirectImeSearch()` 搜索提交确认约第 1189–1212 行：`entered = !isEnergySearchPage(after)` 将“没认出搜索页”当“已进入结果页”，存在假阳性风险。
- `executeTask()` 约第 730–760 行：搜索任务仍会尝试 Quiz 检测，日志出现无意义 `QUIZ_DETECT_FAILED`；可作为低优先级优化。
- `TaskParser.isEnergySearchCandidate()` 已能将被截断的“你喜欢的宝贝(3/5)”识别为搜索任务；**该部分别改坏**。

**金币模式对照：**`2.txt` 15:05:37、15:06:30、15:07:22 分别出现「OCR 已定位新版搜索页，搜索栏 y=436」，通过 `ocr-visual` 输入 `router/keyboard/SSD` 并浏览。说明旧 `submitSearchByVisualTarget()` 是有价值的 fallback，但金币和能量界面布局不同，**不能直接把金币 y=436 固定到能量页面**。

## 二、P0：建立稳健但保守的搜索页认证（最重要）

只针对来自**明确搜索任务**且仍在淘宝前台的执行路径；不能在任意页面见到「搜索」就发送键盘事件。

1. 容错 `搜索发现`/ `搜素发现`。可复用类中已有 `fuzzyContains(normalizedText, "搜索发现", 1)` 或有限明确变体；把判定做成可测试的纯逻辑。历史搜索标记也考虑 OCR 小偏差。**模糊词必须结合页面/任务上下文**，不能在任意长文中误匹配并直接点击。
2. 结合 `currentForeground()`：优先确认 `com.taobao.taobao` 及 `com.taobao.search.uniform.SearchActivity`（日志在能量返回时确实观察到）。再结合至少一种视觉证据：搜索发现/历史搜索标题、顶部橙色「搜索」按钮与输入栏组合、推荐词卡片区域。允许「历史搜索」已经被清空而不存在。不要要求特定推荐词，比如咖啡或光纤线。
3. `isImeVisible()` 只代表**输入法可见**，不等于百分百搜索框已获得焦点。将日志中的 `focused=confirmed` 改为 `focus=inferred_from_ime` 或加额外验证；在强搜索上下文内才允许 direct input。焦点不明时**不要盲发 DEL / input text**，转到安全的 OCR 定位（只在已确认搜索页）。
4. OCR 容错应当一次服务 direct-IME、搜索栏 fallback 和推荐卡 fallback，避免其中一个方法放宽而另两个仍使用精确词造成再次失败。建议统一 `SearchPageEvidence` / `classifySearchPage()`，带 `pageKind`、`activity`、`discoveryConfidence`、`imeVisible`、`topSearchButton`、`reason`。
5. 正常网页/商品结果页即使也出现「搜索」二字，不能据此识别为搜索输入页；不可将「搜索页无法认证」等同「已经提交」。

## 三、P0：搜索处理顺序与有界回退

优先使用第八轮成功路径，并融合已经在金币模式验证的部分：

- **路径 A（优先）**：从任务行进入 `SearchActivity`，输入法可见、搜索页证据充足时，保留 direct-IME `input text` + Enter，成功后浏览约 8–10s（可适度滑动）。
- **路径 B（焦点/Enter 失败后）**：复用金币旧版 `submitSearchByVisualTarget()`，但先认证为**搜索页**，用 OCR 识别顶部输入栏或右侧橙色「搜索」按钮**的真实边界**，再按页面宽高约束轻度几何兜底。可点击顶部输入框确保聚焦后输入，再点击文字「搜索」提交；不能照搬金币页面的绝对 y=436，也不能用页面中心固定坐标。
- **路径 C（输入仍失败，推荐卡可识别时）**：保留原 `tryHandleSearchDiscoveryPage()`，对「搜素发现」同样能够定位标题锚点；优先点击元素级安全推荐项（例如截图显示的普通商品/推荐词），而不是对两列合并的 OCR 行点击 union 中心。跳过「删除」「清空」「反馈」「客服」「购物车」「付款」「加购」等敏感操作。若文字区只出现不确定项目，不盲点。
- 所有方法优先 `stopRequested`、安全验证中止、错误可回退，限制尝试次数；**不要把 A 失败直接判为整项 FAILED 而不走安全 B/C**（但已输入了部分文本时应先判断当前页面状态，禁止重复提交）。
- 若成功进入结果页或安全浏览页，返回现有 `SearchResult` 三态；`HANDLED_AND_BROWSED` 不再额外 22 秒；`FAILED` 不在输入页滑动。
- 已有返回逻辑按 OCR/前台页面确认实际回到能量任务面板，不硬编码“返回两次”。无有效进度变化时应有有界重试上限，不陷入循环。

### 结果页必须**正向确认**（避免现有假成功）

禁止仅使用 `!isEnergySearchPage(after)` 认定提交成功：OCR 丢失「搜索发现」会误判。建议至少两个相互独立的信号，包括搜索页前后 Activity/窗口或布局变化、候选/输入法消失、结果列表明确标识、提交的 query 已显示等；从“没有检测到 A”无法逻辑推出“已经进入 B”。记录证据与失败原因；不要虚报“已完成任务”，实际任务进度才是最终验收。

## 四、P1：日志、选词与性能

- 保留已经验证成功的关键词池：iPhone、iPad、MacBook、Xiaomi、coffeeBeans、router、keyboard、SSD、PETG 等（ASCII 实际输入、中文仅显示名称）。不需本轮扩词。
- `SEARCH_PAGE` 输出 `taskMode`、`activity`、`pageEvidence`（例如 `discovery_fuzzy`）、`imeVisible`、`focusConfidence`；避免将推断标为确认。
- `SEARCH_INPUT path=direct-ime|ocr-visual|recommendation`，`SEARCH_SUBMIT confirmed=true/false evidence=...`，`SEARCH_RETURN panelConfirmed=true`，`SEARCH_PROGRESS before→after`。
- 搜索任务不需要先跑 2–3 次 Quiz OCR；仅在考试来源或有清晰答题结构时启用，确保不影响既有答题功能。
- 减少失败后的 8 次无变化 OCR 等待；若已确认是新版搜索页但仅旧 `findSearchVisualTarget` 不支持，可直接转下一 fallback，并限制总超时。

## 五、必须运行的回归与故障复现测试

1. `搜索发现` 正常识别；`搜素发现` 在正确任务 + 搜索 Activity + IME 场景下识别；“历史搜索已清空、只有搜索发现”仍能识别。
2. 搜索建议页的橙色「搜索」和上方输入框可作为辅助证据；仅有普通页面「搜索」文本不能触发输入。
3. 任意页输入法弹出 ≠ 焦点确认，不得直接向错误输入框发送 DEL / 搜索词。
4. A 成功：`1/5→2/5→3/5` 的现有路径继续成功；第三次 OCR 得到 `搜素发现` 时**不再 PAGE_NOT_CONFIRMED**。
5. A 失败时回退 B/C；推荐卡文字如“光纤线”“咖啡”存在时仅点击安全且有证据的推荐项，不点垃圾桶/危险操作/空白坐标。
6. 搜索结果页的正向确认：OCR 误漏搜索标题但页面未变化，必须算提交失败，不能误报成功。
7. 搜索任务后返回 0/5→1/5 或 3/5→4/5，连续完成余下任务（如果当天可重置）；单次失败有界重试并保留诊断日志。
8. **金币搜索保持原样**：保留 `ocr-visual` 路径和 22 秒浏览，原版「搜一搜你心仪的宝贝」可继续执行。
9. 既有能量浏览/答题/黑名单/悬浮窗重新开始/日志滑动与清空功能无回归。
10. 构建新测试 APK，Android 13+ 兼容；不更改 Application ID，不添加 Root、无障碍或不必要权限。

## 六、交付边界与下一步

- 仅在 `fix/energy-search-direct-input` 分支提交，本轮不得碰 `master`、`main` 或 `Doplars/TaobaoCoinAssistant`，也不得向上游发 PR。
- 分组提交代码（例如页面分类、回退状态机、测试/日志），检查 diff；在 GitHub Actions 构建 APK。
- 交付：commit SHA、关键改动文件/方法、具体测试结果、构建链接、APK 下载链接和剩余待真机测试的限制。源码/模拟测试通过不代表真机成功。
- 继续使用本 Fork 现有版本号规范，测试版可追加 `r9`，但**不要**提前更新稳定版标签或创建新 Release。
- **隐私：**作为公开仓库，禁止把随 ZIP 的原始日志或手机截图上传/提交公开仓库（其中有搜索历史）。只上传脱敏后的必要故障特征。
- 用户在小米 13 Ultra 真机验收后，再由 ChatGPT 审核并创建对**自己仓库** `master` 的 PR，考虑 `v1.2.1-d11` Release；不更改旧版本 Tag。

## 七、WorkBuddy 最短开工指令

阅读此任务书、两份日志与三张照片，先复现 `搜索发现→搜素发现` 的单字 OCR 偏差，并核对第八轮 `isSearchDiscoveryPage` / `isEnergySearchPage` / `findSearchDiscoveryTarget` / `findSearchVisualTarget` 与 `tryDirectImeSearch`。优先修复可信搜索页认证，把第八轮成功的 direct-IME 保留为主路径，金币旧版 OCR 输入与安全推荐卡作为 fallback；同时将“仅未识别到搜索页”不再视为“已进入搜索结果页”。完成 CI、提交测试 APK 和真机验收清单。
