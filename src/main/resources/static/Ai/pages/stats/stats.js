// 数据总览（教师端）— 2026-10-01 新增
const config = require('../../utils/config.js');
const auth = require('../../utils/auth.js');

Page({

  data: {
    overview: {},
    topicDistribution: [],
    dailyTrend: []
  },

  onLoad() {
    this.loadStats();
  },

  goBack() {
    wx.navigateBack({ delta: 1 });
  },

  loadStats() {
    const token = auth.getToken();
    if (!token) {
      auth.handleAuthExpired();
      return;
    }
    wx.request({
      url: config.getBaseUrl() + '/teacher/stats/overview',
      method: 'GET',
      header: { 'Authorization': 'Bearer ' + token },
      success: (res) => {
        // 【C2】401/403 统一处理
        if (res.statusCode === 401 || res.statusCode === 403) {
          auth.handleAuthExpired();
          return;
        }
        const body = res && res.data;
        if (body && body.code === 1 && body.data) {
          const data = body.data;
          this.setData({
            overview: data.overview || {},
            topicDistribution: this.withPercent(data.topicDistribution || [], 'defenseCount'),
            dailyTrend: this.withPercent(data.dailyTrend || [], 'cnt')
          });
        } else {
          wx.showToast({ title: (body && body.msg) || '加载失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.showToast({ title: '网络请求失败', icon: 'none' });
      }
    });
  },

  // 给条形图算相对百分比宽度
  withPercent(list, key) {
    let max = 0;
    list.forEach((it) => {
      const v = Number(it[key]) || 0;
      if (v > max) {
        max = v;
      }
    });
    return list.map((it) => {
      const v = Number(it[key]) || 0;
      const percent = max > 0 ? Math.max(4, Math.round(v * 100 / max)) : 0;
      return Object.assign({}, it, { percent: percent });
    });
  }
});
