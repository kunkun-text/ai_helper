# 教师端网页演示（teacher-web）

课程考核AI答辩辅助平台 · 教师端的**浏览器展示版**，用于结项材料截图与演示。纯静态页面，**无需后端、无需联网**。

## 打开方式

直接双击 `index.html`（Chrome / Edge）即可。页面路由：

| 页面 | 地址 | 对应小程序 |
|---|---|---|
| 成绩统计 · 数据总览 | `index.html#stats` | `pages/stats`（数据总览）+ 按课题统计 |
| 学生答辩记录 | `index.html#records` | `pages/teacher` 「答辩记录」tab |
| 个人信息 | `index.html#profile` | `pages/teacher` 「我的」tab |

窗口宽度 < 760px 时自动切换为手机布局（底部 tab-bar 与小程序一致）。右上角可按课题筛选。

## 数据说明

- **全部为虚构示例数据**（`data.js`，固定随机种子，每次打开一致），学号统一以 `2026D` 开头，不对应任何真实学生。
- 字段名与后端一一对应：`/teacher/stats/overview`、`/teacher/stats/topics`、`DefenseRecordsVo`、`defense_score_record`。
- 统计口径（`stats-core.js`）：50 分制；等级阈值与小程序 `utils/scoreCard.js`、后端 `DocScoreParser.gradeLevel` 一致（≥35 优秀 / 26~34 良好 / 15~25 合格 / 5~14 不合格 / <5 无效）；合格率 = 合格及以上占已出分场次比例。
- 「导出 CSV」按钮在浏览器本地生成文件，表头与后端 `ExportController` 一致，含 BOM 与公式注入中和。

## 文件

```
teacher-web/
├── index.html          # 页面结构
├── styles.css          # 样式（配色沿用 app.wxss 变量）
├── stats-core.js       # 统计纯函数（浏览器 / node 共用，有单测）
├── data.js             # 虚构示例数据
├── app.js              # 视图与交互
└── vendor/chart.umd.min.js   # Chart.js 4.4.4（MIT，本地化，离线可用）
```

单测：在项目根目录执行 `node --test tools/test-miniapp-utils.js`。

截图（无界面 Chrome）：
```bash
google-chrome --headless=new --window-size=1440,900 --virtual-time-budget=3000 \
  --screenshot=stats.png "file://$PWD/teacher-web/index.html#stats"
```
