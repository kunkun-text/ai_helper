// 意见反馈 — 2026-10-01 新增（借鉴 smart-medicine 的反馈模块）
const config = require('../../utils/config.js');

Page({

  data: {
    content: '',
    contact: '',
    list: [],
    submitting: false
  },

  // onShow 统一负责加载（首次进入 onLoad+onShow 会连发两次，去掉 onLoad 的重复请求）
  onShow() {
    this.loadList();
  },

  goBack() {
    wx.navigateBack({ delta: 1 });
  },

  onContentInput(e) {
    this.setData({ content: e.detail.value });
  },

  onContactInput(e) {
    this.setData({ contact: e.detail.value });
  },

  // 提交反馈
  submitFeedback() {
    const content = (this.data.content || '').trim();
    if (!content) {
      wx.showToast({ title: '请填写反馈内容', icon: 'none' });
      return;
    }
    if (this.data.submitting) {
      return;
    }
    const token = wx.getStorageSync('token');
    if (!token) {
      wx.showToast({ title: '登录已过期，请重新登录', icon: 'none' });
      return;
    }

    this.setData({ submitting: true });
    wx.request({
      url: config.getBaseUrl() + '/student/feedback/submit',
      method: 'POST',
      header: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + token
      },
      data: { content: content, contact: (this.data.contact || '').trim() },
      success: (res) => {
        const body = res && res.data;
        if (body && body.code === 1) {
          wx.showToast({ title: '提交成功', icon: 'success' });
          this.setData({ content: '', contact: '' });
          this.loadList();
        } else {
          wx.showToast({ title: (body && body.msg) || '提交失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.showToast({ title: '网络请求失败', icon: 'none' });
      },
      complete: () => {
        this.setData({ submitting: false });
      }
    });
  },

  // 加载我的反馈列表（失败要有提示，不能静默显示"暂无反馈"——2026-10-01 审计 P1-5 修复）
  loadList() {
    const token = wx.getStorageSync('token');
    if (!token) {
      wx.showToast({ title: '登录已过期，请重新登录', icon: 'none' });
      return;
    }
    wx.request({
      url: config.getBaseUrl() + '/student/feedback/my',
      method: 'GET',
      header: { 'Authorization': 'Bearer ' + token },
      success: (res) => {
        const body = res && res.data;
        if (body && body.code === 1) {
          this.setData({ list: body.data || [] });
        } else {
          wx.showToast({ title: (body && body.msg) || '加载失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.showToast({ title: '网络请求失败', icon: 'none' });
      }
    });
  }
});
