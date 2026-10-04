/**
 * 统一请求封装（C2 · 2026-10-05）。
 *
 * 能力：
 * 1. 自动附加 Authorization: Bearer <token>；
 * 2. HTTP 401 / 403 统一走 auth.handleAuthExpired（清 storage + 回登录页），页面无需各自判断；
 * 3. 业务 code !== 1：toast 后端 msg（可 silent 关闭），Promise reject { code, msg }；
 * 4. 断网 / 超时：toast「网络连接失败」，不再让页面渲染假空态；
 * 5. showLoading 三态闭环：传 loading 文案时自动在结束时机 hideLoading。
 *
 * 用法：
 *   const { requestWithAuth } = require('../../utils/request.js');
 *   requestWithAuth({
 *     url: '/student/getDefenseTopic',
 *     method: 'GET',
 *     data: { page: 1 },
 *     loading: '加载中'
 *   }).then(data => ...).catch(err => ...);   // err.msg 可直接展示
 */

const config = require('./config.js');
const auth = require('./auth.js');

/** 默认超时（毫秒） */
const DEFAULT_TIMEOUT = 60000;

function buildHeader(options) {
  const header = options.header || {};
  if (!header['content-type'] && !header['Content-Type']) {
    header['content-type'] = options.contentType || 'application/json';
  }
  const token = auth.getToken();
  if (token) {
    header['Authorization'] = 'Bearer ' + token;
  }
  return header;
}

function handleBusinessError(body, silent) {
  const msg = (body && body.msg) || '操作失败';
  if (!silent) {
    wx.showToast({ title: msg, icon: 'none' });
  }
  return { code: body ? body.code : -1, msg: msg };
}

/**
 * 带登录态的请求。options 额外字段：
 * - loading: string  传则请求前 showLoading、结束时 hideLoading
 * - silent:  boolean 业务失败/网络失败时不自动 toast（页面自己处理错误展示时用）
 */
function requestWithAuth(options) {
  if (options.loading) {
    wx.showLoading({ title: options.loading, mask: true });
  }
  return new Promise(function (resolve, reject) {
    wx.request({
      url: options.url,
      method: options.method || 'GET',
      data: options.data,
      header: buildHeader(options),
      timeout: options.timeout || DEFAULT_TIMEOUT,
      success: function (res) {
        if (res.statusCode === 401 || res.statusCode === 403) {
          if (options.loading) { wx.hideLoading(); }
          // 401 = 未登录/过期；403 = 角色不符（同样按登录态处理，重新登录拿正确角色）
          auth.handleAuthExpired();
          reject({ code: res.statusCode, msg: res.statusCode === 403 ? '当前账号无权访问该功能' : '登录已过期，请重新登录' });
          return;
        }
        let body = res.data;
        if (typeof body === 'string') {
          try { body = JSON.parse(body); } catch (e) { body = null; }
        }
        if (!body || typeof body !== 'object') {
          if (options.loading) { wx.hideLoading(); }
          const err = { code: -1, msg: '服务端返回格式异常（HTTP ' + res.statusCode + '）' };
          if (!options.silent) { wx.showToast({ title: err.msg, icon: 'none' }); }
          reject(err);
          return;
        }
        if (body.code === 1) {
          resolve(body.data);
        } else {
          if (options.loading) { wx.hideLoading(); }
          reject(handleBusinessError(body, options.silent));
        }
      },
      fail: function (err) {
        if (options.loading) { wx.hideLoading(); }
        const msg = '网络连接失败，请检查网络';
        if (!options.silent) { wx.showToast({ title: msg, icon: 'none' }); }
        reject({ code: -1, msg: msg, raw: err });
      },
      complete: function () {
        // success/fail 分支已 hide 过，这里是幂等兜底（hideLoading 无副作用）
        if (options.loading) { wx.hideLoading(); }
        if (options.complete && typeof options.complete === 'function') {
          options.complete();
        }
      }
    });
  });
}

/**
 * 带登录态的文件上传（对应 wx.uploadFile）。
 * 返回 Promise（resolve 后端 data / reject { code, msg }），task 通过 options.onTask 回调交出供 abort。
 */
function uploadWithAuth(options) {
  if (options.loading) {
    wx.showLoading({ title: options.loading, mask: true });
  }
  return new Promise(function (resolve, reject) {
    const task = wx.uploadFile({
      url: options.url,
      filePath: options.filePath,
      name: options.name || 'file',
      formData: options.formData || {},
      header: buildHeader(options),
      timeout: options.timeout || 300000,
      success: function (res) {
        if (res.statusCode === 401 || res.statusCode === 403) {
          if (options.loading) { wx.hideLoading(); }
          auth.handleAuthExpired();
          reject({ code: res.statusCode, msg: '登录已过期，请重新登录' });
          return;
        }
        let body = res.data;
        if (typeof body === 'string') {
          try { body = JSON.parse(body); } catch (e) { body = null; }
        }
        if (body && body.code === 1) {
          if (options.loading) { wx.hideLoading(); }
          resolve(body.data);
        } else {
          if (options.loading) { wx.hideLoading(); }
          reject(handleBusinessError(body, options.silent));
        }
      },
      fail: function (err) {
        if (options.loading) { wx.hideLoading(); }
        const aborted = err && err.errMsg && err.errMsg.indexOf('abort') >= 0;
        if (!aborted && !options.silent) {
          wx.showToast({ title: '网络连接失败，请检查网络', icon: 'none' });
        }
        reject({ code: -1, msg: aborted ? '已取消' : '上传失败', raw: err });
      },
      complete: function () {
        if (options.loading) { wx.hideLoading(); }
        if (options.complete && typeof options.complete === 'function') {
          options.complete();
        }
      }
    });
    if (task && options.onTask && typeof options.onTask === 'function') {
      options.onTask(task);
    }
  });
}

/** 带登录态的下载（对应 wx.downloadFile），统一 401 处理 */
function downloadWithAuth(options) {
  return new Promise(function (resolve, reject) {
    wx.downloadFile({
      url: options.url,
      header: buildHeader(options),
      timeout: options.timeout || 300000,
      success: function (res) {
        if (res.statusCode === 401 || res.statusCode === 403) {
          auth.handleAuthExpired();
          reject({ code: res.statusCode, msg: '登录已过期，请重新登录' });
          return;
        }
        if (res.statusCode === 200) {
          resolve(res.tempFilePath);
        } else {
          reject({ code: res.statusCode, msg: '下载失败（HTTP ' + res.statusCode + '）' });
        }
      },
      fail: function (err) {
        reject({ code: -1, msg: (err && err.errMsg) || '下载失败', raw: err });
      }
    });
  });
}

/** 取后端基地址（拼相对路径用） */
function baseUrl() {
  return config.getBaseUrl();
}

module.exports = {
  requestWithAuth: requestWithAuth,
  uploadWithAuth: uploadWithAuth,
  downloadWithAuth: downloadWithAuth,
  baseUrl: baseUrl
};
