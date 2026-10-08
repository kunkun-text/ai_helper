// 全局配置文件
const config = {
  // 当前环境：dev=开发者工具本机；lan=真机局域网；prod=体验版/正式版 HTTPS 域名
  env: 'dev',

  // 【开发者工具地址】开发者工具与后端跑在同一台机器上，用 localhost 即可
  devBaseUrl: 'http://localhost:8080',

  // 【真机调试地址】本机无线网卡(WLAN)的 IPv4 地址
  // ⚠️ 换网络 / 重启路由器后 IP 会变；真机连不上时，先跑 `ipconfig` 核对这里再改
  // 2026-09-25 确认：WLAN = 10.202.239.67（本机换到荣耀猎人 V700 + 换了系统盘后重新核对）
  // 注意：其余 172.18.176.1 / 192.168.137.1 / 192.168.109.1 是 Hyper-V/VMware 虚拟网卡，不要用
  lanBaseUrl: 'http://10.202.239.67:8080',

  // 【生产地址】拿到已备案 HTTPS 合法域名后再填写，并把 env 改为 prod
  prodBaseUrl: '',

  // 兼容旧字段：历史页面应统一走 getBaseUrl，新代码不要直接读取这两个字段
  serverUrl: 'http://10.202.239.67:8080',
  localServerUrl: 'http://localhost:8080',
  useRemoteServer: false
};

/**
 * 统一取后端基地址。
 *
 * 所有请求一律走这里。A2 已改为 dev/lan/prod 三环境：体验版/正式版必须切到 prod，且 prodBaseUrl
 * 必须是 HTTPS 合法域名，不能再依赖 urlCheck:false 或局域网 HTTP。
 */
config.getBaseUrl = function () {
  if (!['dev', 'lan', 'prod'].includes(config.env)) {
    throw new Error('未知运行环境 env=' + config.env);
  }
  if (config.env === 'prod') {
    if (!config.prodBaseUrl || !/^https:\/\//.test(config.prodBaseUrl)) {
      throw new Error('生产环境必须配置 HTTPS prodBaseUrl');
    }
    return config.prodBaseUrl.replace(/\/$/, '');
  }
  const baseUrl = config.env === 'lan' ? config.lanBaseUrl : config.devBaseUrl;
  return baseUrl.replace(/\/$/, '');
};

module.exports = config;
