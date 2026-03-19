Page({
  data: {
    url: ''
  },

  onLoad(options) {
    if (options.url) {
      this.setData({
        url: decodeURIComponent(options.url)
      });
    }
  },

  onWebviewError(e) {
    console.error('WebView加载失败:', e.detail);
    wx.showToast({
      title: '文档加载失败',
      icon: 'error'
    });
    setTimeout(() => {
      wx.navigateBack();
    }, 2000);
  }
});