// 引入全局配置
const config = require('../../utils/config.js');

Page({
  /**
   * 页面的初始数据
   */
  data: {
    email: '', // 邮箱
    emailFocus: false, // 邮箱输入框聚焦状态
    canSend: false // 是否可发送
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

  // 提交忘记密码表单
  submitForm() {
    if (!this.data.email) {
      wx.showToast({
        title: '请输入邮箱',
        icon: 'none'
      });
      return;
    }

    // 使用全局配置的服务器地址
    const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
    const requestUrl = `${serverUrl}/api/forgot-password`;

    wx.request({
      url: requestUrl,
      method: 'POST',
      data: {
        email: email
      },
      header: {
        'content-type': 'application/json'
      },
      success: (res) => {
        wx.hideLoading();
        if (res.data.code === 1) {
          wx.showToast({
            title: '邮件已发送',
            icon: 'success'
          });
          // 跳转到登录页
          setTimeout(() => {
            wx.redirectTo({
              url: '/pages/login/login'
            });
          }, 1500);
        } else {
          wx.showToast({
            title: res.data.msg || '发送失败',
            icon: 'error'
          });
        }
      },
      fail: (err) => {
        wx.hideLoading();
        wx.showToast({
          title: '网络请求失败',
          icon: 'error'
        });
        console.error('发送重置邮件失败:', err);
      }
    });
  }
});