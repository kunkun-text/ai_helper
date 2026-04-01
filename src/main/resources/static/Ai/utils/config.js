// 全局配置文件
const config = {
  // 开发环境服务器地址（真机调试时使用）
  // 请将此IP地址修改为你开发机器的实际IP地址
  // 在Windows命令行中运行 ipconfig 查看IPv4地址
  // 在Mac/Linux终端中运行 ifconfig 或 ip addr 查看IP地址
  serverUrl: 'http://localhost:8080',
  //'http://10.77.7.152:8080'
  // 本地开发服务器地址（开发者工具调试时使用）
  localServerUrl: 'http://localhost:8080',
  
  // 当前使用的服务器地址配置
  // true: 使用真机调试地址（serverUrl），适用于手机真机调试
  // false: 使用本地地址（localServerUrl），适用于开发者工具调试
  useRemoteServer: true
};

module.exports = config;