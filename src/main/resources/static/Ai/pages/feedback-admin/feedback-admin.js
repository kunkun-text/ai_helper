// 反馈管理（教师端）— 2026-10-01 新增
const config = require('../../utils/config.js');
const auth = require('../../utils/auth.js');

/** 【C2】401/403 统一处理入口 */
function handleUnauthorized(res) {
  if (res.statusCode === 401 || res.statusCode === 403) {
    auth.handleAuthExpired();
    return true;
  }
  return false;
}

Page({

  data: {
    list: [],
    status: '',
    pageNum: 1,
    pageSize: 10,
    hasMore: false,
    replyInput: {},
    loadingMore: false
  },

  onLoad() {
    this.loadList(true);
  },

  goBack() {
    wx.navigateBack({ delta: 1 });
  },

  onFilter(e) {
    const status = e.currentTarget.dataset.status || '';
    this.setData({ status: status }, () => this.loadList(true));
  },

  loadList(reset) {
    const token = auth.getToken();
    if (!token) {
      auth.handleAuthExpired();
      return;
    }
    // 加载更多防连点：上一页还没回来不发新请求（2026-10-01 审计 P2 修复）
    if (!reset && this.data.loadingMore) {
      return;
    }
    const pageNum = reset ? 1 : this.data.pageNum + 1;
    if (!reset) {
      this.setData({ loadingMore: true });
    }
    wx.request({
      url: config.getBaseUrl() + '/teacher/feedback/list',
      method: 'GET',
      header: { 'Authorization': 'Bearer ' + token },
      data: { pageNum: pageNum, pageSize: this.data.pageSize, status: this.data.status },
      success: (res) => {
        if (handleUnauthorized(res)) {
          return;
        }
        const body = res && res.data;
        if (body && body.code === 1 && body.data) {
          const rows = body.data.list || [];
          this.setData({
            list: reset ? rows : this.data.list.concat(rows),
            pageNum: pageNum,
            hasMore: pageNum < (body.data.pages || 0)
          });
        } else {
          wx.showToast({ title: (body && body.msg) || '加载失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.showToast({ title: '网络请求失败', icon: 'none' });
      },
      complete: () => {
        if (!reset) {
          this.setData({ loadingMore: false });
        }
      }
    });
  },

  loadMore() {
    this.loadList(false);
  },

  onReplyInput(e) {
    const id = e.currentTarget.dataset.id;
    const key = 'replyInput.' + id;
    this.setData({ [key]: e.detail.value });
  },

  submitReply(e) {
    // 防双击重复回复（2026-10-01 审计 P2 修复）
    if (this._replying) {
      return;
    }
    const id = e.currentTarget.dataset.id;
    const reply = (this.data.replyInput[id] || '').trim();
    if (!reply) {
      wx.showToast({ title: '请输入回复内容', icon: 'none' });
      return;
    }
    const token = auth.getToken();
    this._replying = true;
    wx.request({
      url: config.getBaseUrl() + '/teacher/feedback/reply',
      method: 'POST',
      header: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + token
      },
      data: { feedbackId: Number(id), reply: reply },
      success: (res) => {
        if (handleUnauthorized(res)) {
          return;
        }
        const body = res && res.data;
        if (body && body.code === 1) {
          wx.showToast({ title: '回复成功', icon: 'success' });
          this.loadList(true);
        } else {
          wx.showToast({ title: (body && body.msg) || '回复失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.showToast({ title: '网络请求失败', icon: 'none' });
      },
      complete: () => {
        this._replying = false;
      }
    });
  }
});
