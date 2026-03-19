// 引入全局配置
const config = require('../../utils/config.js');

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

  // 点击切换密码显隐
  togglePwd() {
    this.setData({ showPwd: !this.data.showPwd });
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
    const {currentRole, account, password} = this.data;
    // 使用全局配置的服务器地址
    const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
    const requestUrl = `${serverUrl}/login/${currentRole}`;

    wx.showLoading({title: '登陆中...'});
    
    console.log('发送登录请求:', {
      url: requestUrl,
      data: {
        role: currentRole,
        userNumber: account,
        password: password
      }
    });

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
        console.log('登录请求成功响应:', res);
        
        // 先隐藏加载提示
        wx.hideLoading();
        
        // 检查响应是否包含所需字段
        if (res && res.data && typeof res.data === 'object') {
          if (res.data.code === 1) {
            try {
              const userInfo = res.data.data || {};
              const token = userInfo.token;
              const userName = userInfo.name || account;

              if (!token) {
                wx.showToast({
                  title: '登录响应缺少token',
                  icon: 'error'
                });
                return;
              }

              // 保存token到本地存储
              wx.setStorageSync('token', token);
              
              // 保存用户名到本地存储
              wx.setStorageSync('userName', userName);
              
              // 保存用户角色
              wx.setStorageSync('userRole', userInfo.role || currentRole);

              // 保存完整用户信息
              wx.setStorageSync('userInfo', {
                role: userInfo.role || currentRole,
                name: userName,
                id: userInfo.id || '',  // 添加ID字段
                userNumber: userInfo.userNumber || account,
                phoneNumber: userInfo.phoneNumber || '',
                email: userInfo.email || '',
                token
              });


              wx.showToast({
                title: '登录成功',
                icon: 'success'
              });

              // 延迟跳转到主页面
              setTimeout(() => {
                // 检查目标页面是否存在并决定使用哪种跳转方式
                if (currentRole == 'student') {
                  wx.reLaunch({
                    url: '/pages/student/student'
                  });
                }
                else if (currentRole == 'teacher') {
                  wx.reLaunch({
                    url: '/pages/teacher/teacher'
                  });
                }
                
                
                // 如果reLaunch不行，尝试redirectTo
                // wx.redirectTo({
                //   url: '/pages/student/student'
                // });
              }, 1500);
            } catch (error) {
              console.error('处理登录响应数据出错:', error);
              wx.showToast({
                title: '数据处理错误',
                icon: 'error'
              });
            }
          } else {
            // 登录失败
            const msg = res.data.msg || '登录失败';
            wx.showToast({
              title: msg,
              icon: 'error'
            });

            
          }
        } else {
          // 响应格式不正确
          wx.showToast({
            title: '服务器响应格式错误',
            icon: 'error'
          });
        }
      },
      fail: (err) => {
        
        console.error('登录请求失败:', err);
        wx.hideLoading();
        wx.showToast({
          title: '网络请求失败',
          icon: 'error'
        });
                      // // 延迟跳转到主页面
                      // setTimeout(() => {
                      //   // 检查目标页面是否存在并决定使用哪种跳转方式
                      //   wx.reLaunch({
                      //     url: '/pages/student/student'
                      //   });
                        
                      //   // 如果reLaunch不行，尝试redirectTo
                      //   // wx.redirectTo({
                      //   //   url: '/pages/student/student'
                      //   // });
                      // }, 1500);
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