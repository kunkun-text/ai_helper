# 对抗性审查清单（六维度）

> 用途：① 新功能交付前的自查；② 用户要求"审查/审计/复审"时的标准作业程序。
> 方法：逐维度对抗性提问——"我是攻击者/粗心的开发者，怎么让这段代码出事？"
> 输出格式：P0 阻断（越权/注入/崩溃/协议失效，含文件行号与修法）→ P1 逻辑与兼容性 → P2 工程改进 → 终审裁决（通过/有条件通过/驳回）+ 边界用例清单。

## 一、业务安全与权限隔离（最高优先级）

- [ ] **水平越权（IDOR）**：每个按 id 查询/修改的接口，归属校验是否用**登录态身份**（`AuthInterceptor.currentUserNumber(request)` → 反查 user_id → SQL 带 `user_id` 条件）？凡是"前端传 userNumber/userId 就直接查库"的写法都是 P0。
- [ ] **垂直越权**：教师接口是否类级 `@RequireRole(UserRole.TEACHER)` 且路径在 `/teacher/**`（PROTECTED_PATHS 内）？新增控制器是否落进了保护路径？（放 `/api/**` 之外的裸路径 = 漏保护）
- [ ] 学生/教师双端共用的接口（如反馈提交），身份与角色语义是否明确？
- [ ] 历史兼容：旧 token（无角色）访问新接口的行为是否是"401 要求重登"而非放行？

## 二、数据与接口安全

- [ ] **SQL 注入**：MyBatis XML 全量搜 `${`（应零命中）；`ORDER BY`/表名动态拼接是重灾区。
- [ ] **CSV 导出**：`= + - @ \t \r` 开头的用户可控字段（姓名/课题名）是否前置单引号中和（CWE-1236）？BOM（`\uFEFF` 先于任何输出写入）？`setCharacterEncoding` 必须在 `getWriter()` 之前？行分隔符是否固定（`println` 随平台）？行数上限？
- [ ] **XSS**：小程序 `{{}}` 插值自动转义，天然安全；后端拼 HTML/富文本才是风险。
- [ ] 文件上传：格式白名单 + 大小上限 + 目录穿越（`resolveSafe`）是否在链路上。

## 三、参数校验与全局异常

- [ ] DTO 校验注解（`@NotBlank/@Size`）+ Controller `@Valid` 成对出现；只加注解不加 `@Valid` = 校验不生效。
- [ ] `GlobalExceptionHandler` 覆盖：`MethodArgumentNotValidException`（@RequestBody 路径）、`ConstraintViolationException`（@Validated 方法参数）、`HttpMessageNotReadableException`（非法 JSON）、`MissingServletRequestParameterException`、`MaxUploadSizeExceededException`、`BusinessException`、兜底 `Exception`。新增异常类型时确认不被兜底吞成"服务器处理失败"。
- [ ] **响应码兼容**：前端只判 `res.data.code === 1`（部分地方还带 `res.statusCode === 200`）。改错误码（0→400 等）前全量确认前端无 `code === 0` 依赖；HTTP 状态码保持 200（`@RestControllerAdvice` 默认），改状态码 = 大范围回归。
- [ ] 异常消息不透传 SQL/堆栈细节。

## 四、AI 协议一致性（chatController / defense.js 专用红线）

- [ ] 协议标签 `点评:` `评分:` `下一题:` `总结:` 与三档标记 `[切题]/[错误]/[跑题]` 一律引用 `AiProtocolConstants`，**禁止新写硬编码字面量**；发现散落字面量先收敛再改。
- [ ] 任何"常量化/重构"改动，用 `git diff` **逐字节比对**拼接串：冒号全角/半角、前后换行、`substring(3)/(4)` 改 `substring(TAG.length())` 后长度是否等值。
- [ ] 解析端（`defense.js parseSegmentFormat/parsePipeFormat`、`ScorePersistenceServiceImpl`、`AiTextUtils`）与拼接端必须同源——改一端必查另一端。
- [ ] prompt 里的自然语言示例文本**不**用常量替换（设计如此，见 AiProtocolConstants javadoc）。
- [ ] 收尾/轮次逻辑改动：权威口径是 `defense_score_record` 落库行数 +1，不是 Redis 历史消息数（历史教训：记忆裁剪导致兜底永不触发、死循环打爆内存）。

## 五、微信小程序前端

- [ ] **全局样式**：`app.wxss` 只做"追加+变量"；`page` 元素选择器作用于全站，加视觉属性（背景/字号/字体）= 全站回归，必须逐页比对或收敛到 `.page` 类。新页面 `.page` 背景用 `var(--g-bg, #f5f7fa)` 带回退值。
- [ ] WXSS 重复定义（后写静默覆盖）是本项目历史高发 bug，改样式前先搜同类名。
- [ ] `wx.request` 三态闭环：success（code===1 / else 提示 msg）、fail（网络提示）、401 显式处理（`handleAuthExpired` 或引导重登）。空态不能冒充错误态。
- [ ] 防抖：提交/回复/加载更多按钮加在途标记（`complete` 里释放）。
- [ ] 异步 setData 的列表/详情数据在**打开时重置**、失败时置空——防止换记录后显示上一条的陈旧数据（"串台"）。
- [ ] `wx:key` 齐全；wxml 引用的类名在 wxss 有定义；新页面注册进 `app.json`。
- [ ] 无未清理的定时器/事件监听（onUnload 配对）。

## 六、DDL 与部署

- [ ] 字符集 `utf8mb4` + `utf8mb4_0900_ai_ci`（与库默认一致，避免跨表比较排序规则冲突）。
- [ ] 索引匹配**实际查询形态**（如 `WHERE status ORDER BY created_at DESC` → 组合索引 `(status, created_at)`），不是"每列一个单列索引"。
- [ ] 逻辑外键（本项目约定不用物理 FK）；`IF NOT EXISTS` 幂等，但对**已存在的表不会补列/补索引**——需另出 ALTER 语句并在 CHANGES.md 提醒手动执行。
- [ ] 非空约束与默认值合理（状态列给默认值，时间列 `DEFAULT CURRENT_TIMESTAMP`）。

## 附：快速取证命令

```bat
:: 全项目搜 SQL 注入面（应零命中）
search_content: pattern="\$\{"  path="src/main/resources/Mapper"

:: 搜协议标签硬编码残留（只允许出现在 prompt 文本与 AiProtocolConstants）
search_content: pattern=""(点评|评分|下一题|总结):""  path="src/main/java"

:: 搜前端裸用地址（应零命中，统一走 config.getBaseUrl()）
search_content: pattern="http://localhost"  path="src/main/resources/static/Ai"
```
