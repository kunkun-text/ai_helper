// 引入全局配置
const config = require('../../utils/config.js');

Page({
  /**
   * 页面的初始数据
   */
  data: {
    currentRole: 'student', // 默认选中学生（接收登录页参数）
    account: '', // 学号/工号
    name: '', // 真实姓名
    email: '', //邮箱
    password: '', // 密码
    confirmPassword: '', // 确认密码
    showPwd: false, // 显示密码
    showConfirmPwd: false, // 显示确认密码
    accountFocus: false,
    nameFocus: false,
    emailFocus: false, // 添加邮箱聚焦状态
    pwdFocus: false,
    confirmPwdFocus: false,
    pwdStrength: 0, // 密码强度 0-3
    pwdStrengthDesc: '未输入', // 密码强度描述
    pwdNotMatch: false, // 密码不一致
    canRegister: false // 是否可注册
  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad(options) {
    // 接收登录页传递的角色参数
    if (options.role) {
      this.setData({ currentRole: options.role });
    }
  },

  // 返回登录页
  goBack() {
    wx.navigateBack();
  },

  // 选择身份
  selectRole(e) {
    const role = e.currentTarget.dataset.role;
    this.setData({ currentRole: role });
    this.checkRegisterStatus();
  },

  // 账号输入
  onAccountInput(e) {
    this.setData({ account: e.detail.value.trim() });
    this.checkRegisterStatus();
  },

  // 账号聚焦/失焦
  onAccountFocus() {
    this.setData({ accountFocus: true });
  },
  onAccountBlur() {
    this.setData({ accountFocus: false });
  },

  // 清空账号
  clearAccount() {
    this.setData({ account: '' });
    this.checkRegisterStatus();
  },

  // 姓名输入
  onNameInput(e) {
    this.setData({ name: e.detail.value.trim() });
    this.checkRegisterStatus();
  },

  // 姓名聚焦/失焦
  onNameFocus() {
    this.setData({ nameFocus: true });
  },
  onNameBlur() {
    this.setData({ nameFocus: false });
  },

  // 清空姓名
  clearName() {
    this.setData({ name: '' });
    this.checkRegisterStatus();
  },

  // 邮箱输入
  onEmailInput(e) {
    this.setData({ email: e.detail.value.trim() });
    this.checkRegisterStatus();
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
    this.checkRegisterStatus();
  },

  // 密码输入（计算强度）
  onPasswordInput(e) {
    const password = e.detail.value.trim();
    this.setData({ password });
    // 计算密码强度
    this.calcPwdStrength(password);
    // 检查密码是否一致
    this.checkPwdMatch(password, this.data.confirmPassword);
    this.checkRegisterStatus();
  },

  // 密码聚焦/失焦
  onPwdFocus() {
    this.setData({ pwdFocus: true });
  },
  onPwdBlur() {
    this.setData({ pwdFocus: false });
  },

  // 清空密码
  clearPassword() {
    this.setData({ password: '' });
    this.calcPwdStrength('');
    this.checkPwdMatch('', this.data.confirmPassword);
    this.checkRegisterStatus();
  },

  // 切换密码显示
  togglePwd() {
    this.setData({ showPwd: !this.data.showPwd });
  },

  // 确认密码输入
  onConfirmPasswordInput(e) {
    const confirmPassword = e.detail.value.trim();
    this.setData({ confirmPassword });
    // 检查密码是否一致
    this.checkPwdMatch(this.data.password, confirmPassword);
    this.checkRegisterStatus();
  },

  // 确认密码聚焦/失焦
  onConfirmPwdFocus() {
    this.setData({ confirmPwdFocus: true });
  },
  onConfirmPwdBlur() {
    this.setData({ confirmPwdFocus: false });
  },

  // 清空确认密码
  clearConfirmPassword() {
    this.setData({ confirmPassword: '' });
    this.checkPwdMatch(this.data.password, '');
    this.checkRegisterStatus();
  },

  // 切换确认密码显示
  toggleConfirmPwd() {
    this.setData({ showConfirmPwd: !this.data.showConfirmPwd });
  },

  // 计算密码强度
  calcPwdStrength(password) {
    let strength = 0;
    let desc = '未输入';

    if (password.length === 0) {
      strength = 0;
      desc = '未输入';
    } else if (password.length < 6) {
      strength = 0;
      desc = '过短';
    } else {
      // 包含数字
      if (/[0-9]/.test(password)) strength++;
      // 包含字母
      if (/[a-zA-Z]/.test(password)) strength++;
      // 包含特殊字符
      if (/[^0-9a-zA-Z]/.test(password)) strength++;

      // 强度描述
      if (strength === 1) desc = '弱';
      else if (strength === 2) desc = '中';
      else if (strength === 3) desc = '强';
    }

    this.setData({
      pwdStrength: strength,
      pwdStrengthDesc: desc
    });
  },

  // 检查密码是否一致
  checkPwdMatch(pwd, confirmPwd) {
    if (confirmPwd.length === 0) {
      this.setData({ pwdNotMatch: false });
    } else {
      this.setData({ pwdNotMatch: pwd !== confirmPwd });
    }
  },

  // 检查是否可注册
  checkRegisterStatus() {
    const { account, name, email, password, confirmPassword, pwdNotMatch } = this.data;
    // 条件：账号、姓名、邮箱、密码、确认密码都不为空 + 密码≥6位 + 密码一致
    const canRegister = account.length > 0 && 
                       name.length > 0 && 
                       email.length > 0 && 
                       password.length >= 6 && 
                       confirmPassword.length >= 6 && 
                       !pwdNotMatch;
    this.setData({ canRegister });
  },

  // 注册逻辑
  handleRegister() {
    const { currentRole, account, name, email, password } = this.data;
    // 使用全局配置的服务器地址
    const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
    const requestUrl = `${serverUrl}/register/${currentRole}`;

    wx.showLoading({ title: '注册中...' });

    wx.request({
      url: requestUrl,
      method: 'POST',
      data: {
        role: currentRole,
        userNumber: account,
        name: name,
        email: email,
        password: password
      },
      header: {
        'content-type': 'application/json'
      },
      success: (res) => {
        wx.hideLoading();
        if (res.data.code === 1) {
          wx.showToast({
            title: '注册成功',
            icon: 'success'
          });
          // 跳转到登录页
          setTimeout(() => {
            wx.redirectTo({
              url: '/pages/login/login?role=' + currentRole
            });
          }, 1500);
        } else {
          wx.showToast({
            title: res.data.msg || '注册失败',
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
        console.error('注册请求失败:', err);
      }
    });
  }
});