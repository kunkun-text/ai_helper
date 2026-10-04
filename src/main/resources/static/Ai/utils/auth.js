/**
 * 登录态统一管理（C1 · 2026-10-05）。
 *
 * 背景：此前各页面自己 wx.setStorageSync / getStorageSync / removeStorageSync，
 * key 五花八门（token / userInfo / user / userName / userRole / role），
 * 退出登录只删其中一部分，残留身份导致"退出后仍显示旧用户"。
 *
 * 约定：所有登录态读写一律走本模块；页面不得直接操作 storage key。
 */

/** 全量登录态相关 key：clearLogin 时逐个清除，兼容清理历史遗留的 user / role */
const AUTH_KEYS = ['token', 'userInfo', 'userName', 'userRole', 'user', 'role'];

/** 保存登录信息（login.js 成功回调里调用，唯一写入口） */
function saveLogin(data) {
  if (!data || !data.token) {
    return false;
  }
  wx.setStorageSync('token', data.token);
  wx.setStorageSync('userName', data.name || '');
  wx.setStorageSync('userRole', data.role || '');
  wx.setStorageSync('userInfo', {
    role: data.role || '',
    name: data.name || '',
    id: data.id || '',
    userNumber: data.userNumber || '',
    phoneNumber: data.phoneNumber || '',
    email: data.email || '',
    token: data.token
  });
  return true;
}

/** 取 token（未登录返回 ''） */
function getToken() {
  return wx.getStorageSync('token') || '';
}

/** 取完整用户信息（未登录返回 {}） */
function getUserInfo() {
  return wx.getStorageSync('userInfo') || {};
}

/** 局部更新用户信息（改资料成功后调用），token 不会被覆盖 */
function updateUserInfo(patch) {
  if (!patch) {
    return;
  }
  const info = getUserInfo();
  Object.assign(info, patch);
  wx.setStorageSync('userInfo', info);
}

/** 取登录学号/工号 */
function getUserNumber() {
  const info = getUserInfo();
  return info.userNumber || '';
}

/** 取登录角色 student / teacher */
function getUserRole() {
  const info = getUserInfo();
  return info.role || wx.getStorageSync('userRole') || '';
}

/** 彻底清除登录态（含历史遗留 key），退出登录时唯一出口 */
function clearLogin() {
  AUTH_KEYS.forEach(function (key) {
    try {
      wx.removeStorageSync(key);
    } catch (e) {
      // 单个 key 清除失败不阻塞退出
    }
  });
}

/** 是否已登录（有 token 且 userInfo 可用） */
function isLoggedIn() {
  return !!getToken();
}

/**
 * 登录态过期统一处理：清 storage + 提示 + 回登录页。
 * 请求封装收到 401 时调用，页面无需各自处理。
 */
let expiredHandling = false;
function handleAuthExpired() {
  if (expiredHandling) {
    return; // 防止并发请求同时 401 弹多个 toast / 多次 reLaunch
  }
  expiredHandling = true;
  clearLogin();
  wx.showToast({ title: '登录已过期，请重新登录', icon: 'none', duration: 1500 });
  setTimeout(function () {
    expiredHandling = false;
    wx.reLaunch({ url: '/pages/login/login' });
  }, 1500);
}

module.exports = {
  AUTH_KEYS: AUTH_KEYS,
  saveLogin: saveLogin,
  getToken: getToken,
  getUserInfo: getUserInfo,
  updateUserInfo: updateUserInfo,
  getUserNumber: getUserNumber,
  getUserRole: getUserRole,
  clearLogin: clearLogin,
  isLoggedIn: isLoggedIn,
  handleAuthExpired: handleAuthExpired
};
