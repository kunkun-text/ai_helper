/**
 * 五维雷达图（N8 · 2026-10-08）。
 *
 * 【2026-11-17 · 第三次修复：彻底放弃 canvas，改纯 WXML/CSS 绘制】
 *
 * 历史教训（同一天的两次失败尝试，详见 CHANGES.md 2026-11-17 §六~§八）：
 *   ① canvas 2d + 重试/滚动补画 → 真机仍空白；
 *   ② canvas 2d 双作用域查询 + 尺寸兜底 + 旧版 canvas-id 兜底 → 真机仍空白。
 * 结论：本项目的弹层环境（`componentFramework: glass-easel` + canvas 位于 wx:if 弹层的
 * scroll-view 深处）里，canvas 节点始终拿不到可用上下文。
 *
 * 因此本模块改为**纯 WXML/CSS 实现**：
 *   - 五边形网格环 / 数据多边形：`clip-path: polygon(...)` 的绝对定位图层；
 *   - 五条轴线：细长 view 按 72° 旋转；
 *   - 顶点/圆心：小圆点 view；
 *   - 维度名+分值：绝对定位的文本。
 * 不依赖 canvas / selectorQuery，任何基础库、任何渲染器都必然显示。
 *
 * `averageDims` 跳过「五维全 0」的行（历史场次里这些是没写五维的"未作答"轮），
 * 非零值有 6% 最小可见半径，整场全 0 时数据多边形退化为圆心 —— 由页面附文字说明。
 */

/** 五维顺序与后端 SCORE_KEYS 一致：表达、逻辑、专业、应变、创新 */
const DIM_LABELS = ['表达', '逻辑', '专业', '应变', '创新'];

/** 单维满分（与评分口径一致：每维 10 分，五维和 50 分） */
const DIM_MAX = 10;

/** 网格环半径（占图表区比例），从外到内 */
const RING_RATIOS = [1, 0.75, 0.5, 0.25];

/** 环的交替底色（由外到内），保证环带可见 */
const RING_COLORS = ['#e9eef7', '#f7f9fc', '#e9eef7', '#f7f9fc'];

/** 图表半径（占容器宽/高的百分比；容器是正方形，留 10% 给维度文字） */
const CHART_RADIUS_PCT = 40;

/** 维度文字相对图表半径的外扩倍数 */
const LABEL_RADIUS_SCALE = 1.16;

/**
 * 非零维度的最小半径占比（旧场次可见性兜底）。
 * 历史场次里存在「整场几乎都是 0 分」的数据（如 262/282/241 十轮全部"未作答"），
 * 平均后五维都接近 0，多边形退化成圆心一个点 —— 视觉上和"没画出来"一模一样。
 * 这里把「大于 0 但很小」的值抬到至少 6% 半径；0 分仍留在圆心，语义不变。
 */
const MIN_VISIBLE_RATIO = 0.06;

/** 容器边长（rpx，正方形），用于把百分比半径换算成轴线长度 */
const BOX_SIZE_RPX = 560;

/**
 * 由逐轮评分明细算五维平均分（各维保留 1 位小数）。
 * 跳过「五维全 0」的行；整场全 0 时返回全 0，由界面单独说明。
 *
 * @param {Array} scoreRows /student/scoreDetail 或 /teacher/defense/scoreDetail 返回的数组
 * @returns {number[]} 长度 5；无有效数据时返回全 0
 */
function averageDims(scoreRows) {
  const keys = ['expressionScore', 'logicScore', 'professionalScore', 'adaptabilityScore', 'innovationScore'];
  const sums = [0, 0, 0, 0, 0];
  let count = 0;
  (scoreRows || []).forEach((row) => {
    if (!row) {
      return;
    }
    const rowValues = keys.map((k) => {
      const v = Number(row[k]);
      return isNaN(v) ? 0 : v;
    });
    if (!rowValues.some((v) => v > 0)) {
      return;
    }
    count += 1;
    rowValues.forEach((v, i) => {
      sums[i] += v;
    });
  });
  if (count === 0) {
    return [0, 0, 0, 0, 0];
  }
  return sums.map((s) => Math.round((s / count) * 10) / 10);
}

/** 归一化输入：必须正好 5 个数字 */
function normalizeDims(dims) {
  const values = (dims && dims.length === 5) ? dims : [0, 0, 0, 0, 0];
  return values.map((v) => {
    const n = Number(v);
    return isNaN(n) ? 0 : n;
  });
}

/** 第 index 个维度在给定半径比例下的坐标（容器百分比，y 轴向下） */
function pointPct(index, ratio) {
  const angle = ((-90 + index * 72) * Math.PI) / 180;
  return {
    x: 50 + CHART_RADIUS_PCT * Math.cos(angle) * ratio,
    y: 50 + CHART_RADIUS_PCT * Math.sin(angle) * ratio
  };
}

/** 给定半径比例的正五边形 clip-path */
function clipFor(ratio) {
  const pts = [];
  for (let i = 0; i < 5; i++) {
    const p = pointPct(i, ratio);
    pts.push(p.x.toFixed(2) + '% ' + p.y.toFixed(2) + '%');
  }
  return 'polygon(' + pts.join(', ') + ')';
}

/** 分值 → 半径比例：夹在 0~1；非零值给最小可见半径，0 分回到圆心 */
function ratioOf(rawValue) {
  const n = Number(rawValue);
  const v = Math.max(0, Math.min(DIM_MAX, isNaN(n) ? 0 : n));
  if (v <= 0) {
    return 0;
  }
  return Math.max(v / DIM_MAX, MIN_VISIBLE_RATIO);
}

/**
 * 生成雷达图的纯 CSS 渲染数据（配合 `radar-box`/`radar-abs`/`radar-label` 样式使用）。
 *
 * @param {number[]} dims 长度 5 的分值（0~10）
 * @returns {Object} { rings, axes, poly, center, dots, labels }，
 *                    每项为 { key, style, text? }，style 直接绑定到 view 的 style 属性
 */
function buildRadarView(dims) {
  const values = normalizeDims(dims);

  // 1. 网格环（由外到内叠放，交替底色形成环带）
  const rings = RING_RATIOS.map((r, i) => {
    const clip = clipFor(r);
    return {
      key: 'ring-' + i,
      style: 'left:0;top:0;width:100%;height:100%;clip-path:' + clip
        + ';-webkit-clip-path:' + clip + ';background:' + RING_COLORS[i] + ';'
    };
  });

  // 2. 五条轴线：细长 view 从圆心出发，按 72° 步进旋转（transform-origin 在圆心端）
  const axisLenRpx = Math.round((BOX_SIZE_RPX * CHART_RADIUS_PCT) / 100);
  const axes = [];
  for (let i = 0; i < 5; i++) {
    axes.push({
      key: 'axis-' + i,
      style: 'left:50%;top:50%;width:1px;height:' + axisLenRpx + 'rpx;'
        + 'margin-left:-0.5px;transform-origin:50% 0;transform:rotate(' + (i * 72) + 'deg);'
        + 'background:#dfe6f0;'
    });
  }

  // 3. 数据多边形（半透明蓝，叠在网格上）
  const dataPts = [];
  for (let i = 0; i < 5; i++) {
    const p = pointPct(i, ratioOf(values[i]));
    dataPts.push(p.x.toFixed(2) + '% ' + p.y.toFixed(2) + '%');
  }
  const dataClip = 'polygon(' + dataPts.join(', ') + ')';
  const poly = [{
    key: 'poly',
    style: 'left:0;top:0;width:100%;height:100%;clip-path:' + dataClip
      + ';-webkit-clip-path:' + dataClip + ';background:rgba(43,108,255,0.28);'
  }];

  // 4. 圆心点（整场 0 分时至少能看到"图在这里、分确实是 0"）
  const center = [{
    key: 'center',
    style: 'left:50%;top:50%;width:10rpx;height:10rpx;margin-left:-5rpx;margin-top:-5rpx;'
      + 'background:#c9d3e3;border-radius:50%;'
  }];

  // 5. 顶点（数据多边形的角）
  const dots = [];
  for (let i = 0; i < 5; i++) {
    const p = pointPct(i, ratioOf(values[i]));
    dots.push({
      key: 'dot-' + i,
      style: 'left:' + p.x.toFixed(2) + '%;top:' + p.y.toFixed(2) + '%;'
        + 'width:12rpx;height:12rpx;margin-left:-6rpx;margin-top:-6rpx;'
        + 'background:#2b6cff;border-radius:50%;'
    });
  }

  // 6. 维度名 + 分值（沿顶点方向外扩，绝对定位文本）
  const labels = [];
  for (let i = 0; i < 5; i++) {
    const p = pointPct(i, LABEL_RADIUS_SCALE);
    labels.push({
      key: 'label-' + i,
      style: 'left:' + p.x.toFixed(2) + '%;top:' + p.y.toFixed(2) + '%;transform:translate(-50%,-50%);',
      text: DIM_LABELS[i] + ' ' + values[i]
    });
  }

  return {
    rings: rings,
    axes: axes,
    poly: poly,
    center: center,
    dots: dots,
    labels: labels
  };
}

module.exports = {
  DIM_LABELS: DIM_LABELS,
  DIM_MAX: DIM_MAX,
  averageDims: averageDims,
  buildRadarView: buildRadarView
};
