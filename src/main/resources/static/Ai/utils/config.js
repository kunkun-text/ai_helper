// 全局配置文件
const config = {
  // 【真机调试地址】本机无线网卡(WLAN)的 IPv4 地址
  // ⚠️ 换网络 / 重启路由器后 IP 会变；真机连不上时，先跑 `ipconfig` 核对这里再改
  // 2026-09-25 确认：WLAN = 10.202.239.67（本机换到荣耀猎人 V700 + 换了系统盘后重新核对）
  // 注意：其余 172.18.176.1 / 192.168.137.1 / 192.168.109.1 是 Hyper-V/VMware 虚拟网卡，不要用
  serverUrl: 'http://10.202.239.67:8080',

  // 【开发者工具地址】开发者工具与后端跑在同一台机器上，用 localhost 即可
  localServerUrl: 'http://localhost:8080',

  // 当前使用的服务器地址配置
  // true : 使用 serverUrl（手机真机调试）—— 做真机测试前改成 true
  // false: 使用 localServerUrl（微信开发者工具调试）—— 默认，最稳
  useRemoteServer: false
};

/**
 * 统一取后端基地址。
 *
 * 以前各页面直接写 config.serverUrl，导致 useRemoteServer 开关对部分页面完全无效
 * （切地址时这些页面不跟随）。所有请求一律走这里。
 */
config.getBaseUrl = function () {
  return config.useRemoteServer ? config.serverUrl : config.localServerUrl;
};

module.exports = config;
