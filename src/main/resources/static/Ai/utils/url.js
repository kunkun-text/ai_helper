/**
 * URL 解析工具（D3 · 2026-10-05）。
 *
 * 后端返回的音频/文件地址有两种形态：
 * - 相对路径：/files/tts/xxxx.wav —— 需要拼上当前环境的服务器地址；
 * - 绝对地址：https://cdn.example.com/xxx.wav —— 原样使用（不能无脑拼 baseUrl，
 *   旧实现 `config.getBaseUrl() + data.url` 会把绝对地址拼成
 *   `http://hosthttp://cdn/...` 导致播放失败）。
 */

const config = require('./config.js');

/**
 * 解析为可直接播放/下载的绝对地址。
 * @param {string} url 后端返回的地址（相对路径或绝对地址）
 * @returns {string} 绝对地址；空值返回 ''
 */
function resolveUrl(url) {
  if (!url) {
    return '';
  }
  const value = String(url).trim();
  if (/^(https?:)?\/\//i.test(value)) {
    return value; // 已是绝对地址（含协议相对地址），原样返回
  }
  if (value.charAt(0) === '/') {
    return config.getBaseUrl() + value;
  }
  return value;
}

module.exports = {
  resolveUrl: resolveUrl
};
