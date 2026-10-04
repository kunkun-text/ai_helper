// 引入全局配置
const config = require('../../utils/config.js');
const auth = require('../../utils/auth.js');

Page({
  /**
   * 页面的初始数据
   */
  data: {
    currentRole: 'student',   // 默认选中学生
    account: '',              // 学号/工号
    password: '',             // 密码
    showPwd: false,           // 是否显示密码
    canLogin: false,          // 是否可登录
    submitting: false,        // 【C4】登录请求防重复提交
    accountFocus: false,      // 账号输入框聚焦状态
    pwdFocus: false,          // 密码输入框聚焦状态
    longPressTimer: null      // 长按计时器（用于密码显隐）
  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad(options) {},

  // 选择身份（学生/教师）
  selectRole(e) {
    const role = e.currentTarget.dataset.role;
    this.setData({ currentRole: role });
    this.checkLoginStatus(); // 切换身份后校验登录状态
  },

  // 账号输入框输入事件
  onAccountInput(e) {
    this.setData({ account: e.detail.value.trim() });
    this.checkLoginStatus();
  },

  // 账号输入框聚焦
  onAccountFocus() {
    this.setData({ accountFocus: true });
  },

  // 账号输入框失焦
  onAccountBlur() {
    this.setData({ accountFocus: false });
  },

  // 密码输入框输入事件
  onPasswordInput(e) {
    this.setData({ password: e.detail.value.trim() });
    this.checkLoginStatus();
  },

  // 密码输入框聚焦
  onPwdFocus() {
    this.setData({ pwdFocus: true });
  },

  // 密码输入框失焦
  onPwdBlur() {
    this.setData({ pwdFocus: false });
  },

  // 账号输入框按确认键（Enter）→ 光标跳到密码输入框
  onAccountConfirm() {
    this.setData({ pwdFocus: true });
  },

  // 点按眼睛前先记录密码框是否处于聚焦状态（显隐切换会重建 input 导致失焦）
  onToggleTouchStart() {
    this._pwdWasFocus = this.data.pwdFocus;
  },

  // 点击切换密码显隐
  togglePwd() {
    const keepKeyboard = !!this._pwdWasFocus;
    this._pwdWasFocus = false;
    const next = !this.data.showPwd;
    this.setData({ showPwd: next });
    console.log('切换密码显隐 - showPwd:', next);
    if (keepKeyboard) {
      // 显隐切换会重建 input 导致失焦：先置 false 再置 true，确保焦点属性真正发生变化
      setTimeout(() => {
        this.setData({ pwdFocus: false }, () => {
          this.setData({ pwdFocus: true });
        });
      }, 0);
    }
  },

  // 长按显示密码
  longPressShowPwd() {
    this.setData({ showPwd: true });
    if (this.data.longPressTimer) clearTimeout(this.data.longPressTimer);
  },

  // 长按结束/松手隐藏密码
  longPressEndPwd() {
    this.data.longPressTimer = setTimeout(() => {
      this.setData({ showPwd: false });
    }, 100);
  },

  // 校验是否可登录（账号+密码都不为空）
  checkLoginStatus() {
    const { account, password } = this.data;
    this.setData({ canLogin: account.length > 0 && password.length > 0 });
  },

  // 登录逻辑
  handleLogin() {
    // 【C4】防重复提交：请求未返回前连点不再发第二个请求
    if (this.data.submitting) {
      return;
    }
    const {currentRole, account, password} = this.data;
    if (!account || !password) {
      wx.showToast({ title: '请输入账号和密码', icon: 'none' });
      return;
    }
    // 使用全局配置的服务器地址
    const serverUrl = config.getBaseUrl();
    const requestUrl = `${serverUrl}/login/${currentRole}`;

    this.setData({ submitting: true });
    wx.showLoading({ title: '登录中...', mask: true });

    wx.request({
      url: requestUrl,
      method: 'POST',
      data: {
        role: currentRole,
        userNumber: account,
        password: password
      },
      header: {
        'content-type': 'application/json'
      },
      success: (res) => {
        if (res && res.data && typeof res.data === 'object') {
          if (res.data.code === 1) {
            const userInfo = res.data.data || {};
            const token = userInfo.token;
            const userName = userInfo.name || account;

            if (!token) {
              wx.showToast({ title: '登录响应缺少token', icon: 'error' });
              return;
            }

            // 【C1】统一写入口：token / userName / userRole / userInfo 一次写入
            auth.saveLogin({
              role: userInfo.role || currentRole,
              name: userName,
              id: userInfo.id || '',
              userNumber: userInfo.userNumber || account,
              phoneNumber: userInfo.phoneNumber || '',
              email: userInfo.email || '',
              token: token
            });

            wx.showToast({ title: '登录成功', icon: 'success' });

            // 延迟跳转到主页面
            setTimeout(() => {
              if (currentRole == 'student') {
                wx.reLaunch({ url: '/pages/student/student' });
              } else if (currentRole == 'teacher') {
                wx.reLaunch({ url: '/pages/teacher/teacher' });
              }
            }, 1500);
          } else {
            // 登录失败
            wx.showToast({ title: res.data.msg || '登录失败', icon: 'none' });
          }
        } else {
          wx.showToast({ title: '服务器响应格式错误', icon: 'none' });
        }
      },
      fail: () => {
        // 【C5】不再打印请求详情（含密码）；失败原因统一提示
        wx.showToast({ title: '网络请求失败', icon: 'none' });
      },
      complete: () => {
        // 【C4】无论成败都恢复按钮状态；loading 三态闭环
        wx.hideLoading();
        this.setData({ submitting: false });
      }
    });
  },

  // 跳转到注册页面
  goToRegister() {
    wx.navigateTo({ url: '/pages/register/register?role=' + this.data.currentRole });
  },

  // 跳转到忘记密码页面
  goToForgotPassword() {
    wx.navigateTo({ url: '/pages/forgetPassword/forgetPassword' });
  }
});