// app.js
App({
  onLaunch() {
    // 【优化 · 2026-11-21】移除模板自带的 wx.login 空调用：项目使用「学号/工号 + 密码」登录（token 存本地），
    // 从未把 code 发往后端换取 openId，每次冷启动多一次无用的网络请求。需要接入微信登录时再按需恢复。
  },
  globalData: {
    userInfo: null
  }
})
