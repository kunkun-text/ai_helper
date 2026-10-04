// 引入全局配置
const config = require('../../utils/config.js');

Page({
  /**
   * 页面的初始数据
   */
  data: {
    email: '', // 邮箱
    emailFocus: false, // 邮箱输入框聚焦状态
    canSend: false, // 是否可发送
    sending: false // 【C3/C4】发送中，防重复提交
  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad(options) {},

  // 返回上一页
  goBack() {
    wx.navigateBack();
  },

  // 邮箱输入
  onEmailInput(e) {
    const email = e.detail.value.trim();
    this.setData({ email });
    this.checkCanSend();
  },

  // 邮箱聚焦/失焦
  onEmailFocus() {
    this.setData({ emailFocus: true });
  },
  onEmailBlur() {
    this.setData({ emailFocus: false });
  },

  // 清空邮箱
  clearEmail() {
    this.setData({ email: '' });
    this.checkCanSend();
  },

  // 检查是否可发送
  checkCanSend() {
    const email = this.data.email;
    // 简单的邮箱格式验证
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    this.setData({ canSend: emailRegex.test(email) });
  },

  // 【C3】WXML 绑定的是 sendResetEmail，此处保留别名，避免按钮点了没反应
  sendResetEmail() {
    return this.submitForm();
  },

  // 提交忘记密码表单
  submitForm() {
    // 【C4】防重复：连点不会发多封
    if (this.data.sending) {
      return;
    }
    if (!this.data.email) {
      wx.showToast({ title: '请输入邮箱', icon: 'none' });
      return;
    }

    // 使用全局配置的服务器地址
    const serverUrl = config.getBaseUrl();
    const requestUrl = `${serverUrl}/api/forgot-password`;

    this.setData({ sending: true });
    wx.showLoading({ title: '发送中...', mask: true });

    wx.request({
      url: requestUrl,
      method: 'POST',
      data: {
        email: this.data.email
      },
      header: {
        'content-type': 'application/json'
      },
      success: (res) => {
        if (res && res.data && res.data.code === 1) {
          wx.showToast({ title: '邮件已发送', icon: 'success' });
          // 跳转到登录页
          setTimeout(() => {
            wx.redirectTo({ url: '/pages/login/login' });
          }, 1500);
        } else {
          wx.showToast({ title: (res && res.data && res.data.msg) || '发送失败', icon: 'none' });
        }
      },
      fail: () => {
        // 【C5】不再打印错误详情
        wx.showToast({ title: '网络请求失败', icon: 'none' });
      },
      complete: () => {
        // 【C4】loading 三态闭环 + 恢复按钮
        wx.hideLoading();
        this.setData({ sending: false });
      }
    });
  }
});