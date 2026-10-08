/**
 * 五维雷达图（N8 · 2026-10-08）。
 *
 * 用小程序原生 canvas 2d 手绘，不引入任何图表库（项目无图表依赖，也不打算为一张图引入）。
 * 学生端与教师端的「回答详情」共用本模块，避免两处各写一份绘制逻辑。
 */

/** 五维顺序与后端 SCORE_KEYS 一致：表达、逻辑、专业、应变、创新 */
const DIM_LABELS = ['表达', '逻辑', '专业', '应变', '创新'];

/** 单维满分（与评分口径一致：每维 10 分，五维和 50 分） */
const DIM_MAX = 10;

/** 雷达环数 */
const RING_COUNT = 5;

/**
 * 由逐轮评分明细算五维平均分（各维保留 1 位小数）。
 * @param {Array} scoreRows /student/scoreDetail 或 /teacher/defense/scoreDetail 返回的数组
 * @returns {number[]} 长度 5；无数据时返回全 0
 */
function averageDims(scoreRows) {
  const keys = ['expressionScore', 'logicScore', 'professionalScore', 'adaptabilityScore', 'innovationScore'];
  const sums = [0, 0, 0, 0, 0];
  let count = 0;
  (scoreRows || []).forEach((row) => {
    if (!row) {
      return;
    }
    count += 1;
    keys.forEach((k, i) => {
      const v = Number(row[k]);
      sums[i] += isNaN(v) ? 0 : v;
    });
  });
  if (count === 0) {
    return [0, 0, 0, 0, 0];
  }
  return sums.map((s) => Math.round((s / count) * 10) / 10);
}

/**
 * 绘制雷达图。canvas 必须已在 WXML 中存在（`<canvas type="2d" id="..." />`）。
 * @param {Object} page Page 实例（用于定位 canvas 节点）
 * @param {string} canvasId WXML 上的 id（不带 #）
 * @param {number[]} dims 长度 5 的分值（0~10）
 * @param {Function} [callback] 绘制完成回调
 */
function drawRadar(page, canvasId, dims, callback) {
  const values = (dims && dims.length === 5) ? dims : [0, 0, 0, 0, 0];
  wx.createSelectorQuery()
    .in(page)
    .select('#' + canvasId)
    .fields({ node: true, size: true })
    .exec((res) => {
      const info = res && res[0];
      if (!info || !info.node) {
        // 弹层未渲染 / 被 wx:if 隐藏时不报错，静默跳过
        if (typeof callback === 'function') {
          callback(false);
        }
        return;
      }
      const canvas = info.node;
      const ctx = canvas.getContext('2d');
      // 高清屏适配：按设备像素比放大画布，再缩放绘图坐标，避免线条发虚
      const dpr = (wx.getSystemInfoSync && wx.getSystemInfoSync().pixelRatio) || 2;
      canvas.width = info.width * dpr;
      canvas.height = info.height * dpr;
      ctx.scale(dpr, dpr);

      const width = info.width;
      const height = info.height;
      const cx = width / 2;
      const cy = height / 2 + 4;
      const radius = Math.max(20, Math.min(width, height) / 2 - 34);

      const pointAt = (index, ratio) => {
        const angle = -Math.PI / 2 + index * (Math.PI * 2 / DIM_LABELS.length);
        return {
          x: cx + Math.cos(angle) * radius * ratio,
          y: cy + Math.sin(angle) * radius * ratio
        };
      };

      ctx.clearRect(0, 0, width, height);

      // 1. 背景网格（正五边形若干环）
      ctx.strokeStyle = '#e3e8f0';
      ctx.lineWidth = 1;
      for (let ring = 1; ring <= RING_COUNT; ring++) {
        const ratio = ring / RING_COUNT;
        ctx.beginPath();
        for (let i = 0; i < DIM_LABELS.length; i++) {
          const p = pointAt(i, ratio);
          if (i === 0) {
            ctx.moveTo(p.x, p.y);
          } else {
            ctx.lineTo(p.x, p.y);
          }
        }
        ctx.closePath();
        ctx.stroke();
      }

      // 2. 五条轴线
      for (let i = 0; i < DIM_LABELS.length; i++) {
        const p = pointAt(i, 1);
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.lineTo(p.x, p.y);
        ctx.stroke();
      }

      // 3. 数据多边形
      ctx.beginPath();
      for (let i = 0; i < DIM_LABELS.length; i++) {
        const raw = Number(values[i]);
        const v = Math.max(0, Math.min(DIM_MAX, isNaN(raw) ? 0 : raw));
        const p = pointAt(i, v / DIM_MAX);
        if (i === 0) {
          ctx.moveTo(p.x, p.y);
        } else {
          ctx.lineTo(p.x, p.y);
        }
      }
      ctx.closePath();
      ctx.fillStyle = 'rgba(43, 108, 255, 0.22)';
      ctx.fill();
      ctx.strokeStyle = '#2b6cff';
      ctx.lineWidth = 2;
      ctx.stroke();

      // 4. 顶点
      ctx.fillStyle = '#2b6cff';
      for (let i = 0; i < DIM_LABELS.length; i++) {
        const raw = Number(values[i]);
        const v = Math.max(0, Math.min(DIM_MAX, isNaN(raw) ? 0 : raw));
        const p = pointAt(i, v / DIM_MAX);
        ctx.beginPath();
        ctx.arc(p.x, p.y, 2.6, 0, Math.PI * 2);
        ctx.fill();
      }

      // 5. 维度名 + 分值
      ctx.fillStyle = '#4a5160';
      ctx.font = '11px sans-serif';
      for (let i = 0; i < DIM_LABELS.length; i++) {
        const angle = -Math.PI / 2 + i * (Math.PI * 2 / DIM_LABELS.length);
        const lx = cx + Math.cos(angle) * (radius + 20);
        const ly = cy + Math.sin(angle) * (radius + 20);
        const raw = Number(values[i]);
        const text = DIM_LABELS[i] + ' ' + (isNaN(raw) ? 0 : raw);
        ctx.textAlign = Math.abs(Math.cos(angle)) < 0.3 ? 'center' : (Math.cos(angle) > 0 ? 'left' : 'right');
        ctx.textBaseline = Math.abs(Math.sin(angle)) < 0.3 ? 'middle' : (Math.sin(angle) > 0 ? 'top' : 'bottom');
        ctx.fillText(text, lx, ly);
      }

      if (typeof callback === 'function') {
        callback(true);
      }
    });
}

module.exports = {
  DIM_LABELS: DIM_LABELS,
  DIM_MAX: DIM_MAX,
  averageDims: averageDims,
  drawRadar: drawRadar
};
