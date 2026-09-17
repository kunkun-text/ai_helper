// 引入全局配置与通用上传模块
const config = require('../../utils/config.js');
const uploader = require('../../utils/uploader.js');

/**
 * 登录态失效统一处理。
 *
 * 后端启用登录校验后，token 过期会让 /student/** 全部返回 401。
 * 若不处理，页面表现为「答辩记录、回答详情全部空白」，极易被误判成数据丢失。
 * 这里改为明确提示并回到登录页；写成模块级函数，避免依赖各回调里的 this。
 */
function handleAuthExpired() {
  wx.showModal({
    title: '登录已过期',
    content: '登录状态已失效，请重新登录后继续使用',
    showCancel: false,
    confirmText: '去登录',
    success: () => {
      wx.removeStorageSync('token');
      wx.removeStorageSync('userInfo');
      wx.redirectTo({ url: '/pages/login/login' });
    }
  });
}

Page({
  data: {
    activeTab: 'home',
    token: '',
    user: {
      name: '张三',
      id: '20240001',
      userNumber: '20240001',
      phone: '13800000000',
      email: 'student@example.com'
    },
    editedUser: {
      name: '张四',
      // id: '20240001',
      userNumber: '20240001',
      phone: '13800000000',
      email: 'student@example.com'
    },
    isEditing: false,

    // 下一轮答辩信息（从后端获取的第一条数据）
    nextDefense: {
      topic: '大数据如何处理海量数据',
      date: '2024-04-25'
    },
    // 所有答辩题目列表
    defenseTopics: [],
    // 控制所有答辩题目弹窗显示
    showAllTopics: false,
    // 控制题目描述弹窗显示
    showTopicDescription: false,
    // 当前显示的题目描述
    currentTopicDescription: '',
    
    defenseRecords: [],
    selectedRecord: null,
    
    // 视频上传相关数据
    uploadProgress: 0,
    isUploading: false,
    uploadStatus: 'idle', // 'idle', 'init', 'uploading', 'merging', 'completed', 'failed'
    currentUploadId: '',
    currentFileName: '',
    currentFileSize: 0,
    currentFileSizeReadable: '',
    currentTotalChunks: 0,
    currentChunkIndex: 0,
    currentProcessingId: '',
    currentVideoPath: '',
    uploadStageText: '',
    transcriptionRecordId: null,
    transcriptionStatus: null,

    // 附件所属的答辩题目（学生可切换，默认取题目列表第一项）
    uploadTopicIndex: 0,
    uploadTopicName: '',

    // 已上传附件的对外地址：用于预览 / 下载 / 删除
    uploadedVideoUrl: '',
    uploadedReportUrl: '',

    // 顶部提示条状态（上传成功/失败统一走它，5 秒自动消失）
    banner: {
      visible: false,
      type: 'success',
      text: ''
    },
    
    // 分页相关数据
    pageNum: 1,
    pageSize: 10,
    pages: 1,
    hasMore: true,
    refreshing: false,
    loadingMore: false,
    
    // 添加答辩记录总数字段
    defenseRecordsTotal: 0,
    
    // 添加回答详情字段
    selectedAnswers: null,
    
    // 添加视频上传状态
    hasUploadedVideo: false,

    // 报告上传相关数据
    isReportUploading: false,
    reportStatus: 'idle', // 'idle', 'uploading', 'completed', 'failed'
    reportUploadProgress: 0,
    reportFileName: '',
    reportFileSizeReadable: '',

    // 报告上传状态
    hasUploadedReport: false
  },

  onLoad() {
    console.log('学生页面onLoad执行');
    this.loadUserInfo();

    // 加载真实的答辩记录数据
    this.loadDefenseRecords();
    
    // 加载答辩题目数据
    this.loadDefenseTopics();
  },

  onShow() {
    console.log('学生页面onShow执行');
    this.loadUserInfo();
    // 从答辩页返回时刷新答辩记录（onLoad只在首次进入执行，onShow补一次刷新）
    if (this._recordsLoaded) {
      this.loadDefenseRecords(true);
    }
  },

  // 切换底部导航栏选项卡
  switchTab(e) {
    const tab = e.currentTarget.dataset.tab;
    if (tab && tab !== this.data.activeTab) {
      this.setData({
        activeTab: tab
      });
    }
  },

  // 开始编辑个人信息
  startEdit() {
    this.setData({
      isEditing: true,
      editedUser: { ...this.data.user }
    });
  },

  // 编辑姓名
  onEditName(e) {
    const name = e.detail.value;
    this.setData({
      'editedUser.name': name
    });
  },

  // 编辑学号
  onEditUserNumber(e) {
    const userNumber = e.detail.value;
    this.setData({
      'editedUser.userNumber': userNumber
    });
  },

  // 编辑手机号
  onEditPhone(e) {
    const phone = e.detail.value;
    this.setData({
      'editedUser.phone': phone
    });
  },

  // 编辑邮箱
  onEditEmail(e) {
    const email = e.detail.value;
    this.setData({
      'editedUser.email': email
    });
  },

  // 保存个人信息
  saveProfile() {
    const { editedUser, token, user } = this.data;
    const config = require('../../utils/config.js');
    
    // 验证必填字段
    if (!editedUser.name.trim()) {
      wx.showToast({
        title: '请输入姓名',
        icon: 'none'
      });
      return;
    }
    
    if (!editedUser.userNumber.trim()) {
      wx.showToast({
        title: '请输入学号',
        icon: 'none'
      });
      return;
    }
    
    // 验证手机号格式（如果提供了）
    if (editedUser.phone && editedUser.phone.trim()) {
      const phoneRegex = /^1[3-9]\d{9}$/;
      if (!phoneRegex.test(editedUser.phone.trim())) {
        wx.showToast({
          title: '手机号格式不正确',
          icon: 'none'
        });
        return;
      }
    }
    
    // 验证邮箱格式（如果提供了）
    if (editedUser.email && editedUser.email.trim()) {
      const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      if (!emailRegex.test(editedUser.email.trim())) {
        wx.showToast({
          title: '邮箱格式不正确',
          icon: 'none'
        });
        return;
      }
    }
    
    // 显示加载提示
    wx.showLoading({
      title: '保存中...',
      mask: true
    });
    
    // 调用后端接口更新用户信息
    wx.request({
      url: config.getBaseUrl() + '/editUserInfo', // 使用学生专用接口
      method: 'POST',
      data: {
        id: user.id, // 添加用户ID
        name: editedUser.name,
        userNumber: editedUser.userNumber,
        phoneNumber: editedUser.phone, // 修正字段名
        email: editedUser.email
      },
      header: {
        'Authorization': 'Bearer ' + token,
        'content-type': 'application/json'
      },
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode === 200 && res.data.code === 1) {
          // 更新本地用户信息
          const updatedUser = {
            id: user.id,
            name: editedUser.name,
            userNumber: editedUser.userNumber,
            phone: editedUser.phone,
            email: editedUser.email
          };

          this.setData({
            user: updatedUser,
            isEditing: false
          });

          // 更新本地存储的用户信息
          try {
            const userInfo = wx.getStorageSync('user') || {};
            Object.assign(userInfo, updatedUser);
            wx.setStorageSync('user', userInfo);
            
            // 同时更新userInfo存储（兼容性考虑）
            wx.setStorageSync('userInfo', userInfo);
          } catch (e) {
            console.warn('更新本地存储失败:', e);
          }

          wx.showToast({
            title: '保存成功',
            icon: 'success'
          });
        } else {
          wx.showToast({
            title: res.data.msg || '保存失败',
            icon: 'error'
          });
        }
      },
      fail: (err) => {
        wx.hideLoading();
        console.error('保存个人信息失败:', err);
        wx.showToast({
          title: '网络请求失败，请检查网络连接',
          icon: 'error'
        });
      }
    });
  },

  // 修改密码
  changePassword() {
    wx.navigateTo({
      url: '/pages/forgetPassword/forgetPassword'
    });
  },

  // 退出登录
  logout() {
    const that = this;
    
    // 显示确认对话框
    wx.showModal({
      title: '确认退出',
      content: '确定要退出登录吗？',
      success(res) {
        if (res.confirm) {
          // 清除本地存储的token和用户信息
          wx.removeStorageSync('token');
          wx.removeStorageSync('user');
          wx.removeStorageSync('role');
          
          
          // 跳转到登录页面
          wx.redirectTo({
            url: '/pages/login/login'
          });
        }
      }
    });
  },

  // 更新视频上传状态
  updateVideoUploadStatus() {
    // 改为直接向后端查询附件状态，见 refreshUploadStatus()。
    // 原先靠分页的 defenseRecords 列表去猜"有没有传过"，列表翻页后判断会失准。
    this.refreshUploadStatus();
  },
  
  // 更新报告上传状态
  updateReportUploadStatus() {
    this.refreshUploadStatus();
  },

  /**
   * 查询当前题目下已上传的附件（视频/报告）。
   *
   * 直接问后端，比翻分页的 defenseRecords 列表更准；同一时刻多处调用只发一轮请求。
   */
  refreshUploadStatus() {
    const topics = this.data.defenseTopics || [];
    if (topics.length === 0 || !this.data.token) {
      return;
    }
    if (this._statusRefreshing) {
      return;
    }
    this._statusRefreshing = true;
    setTimeout(() => { this._statusRefreshing = false; }, 300);

    const topic = topics[this.data.uploadTopicIndex] || topics[0];
    const topicId = topic.topicId || topic.id;
    const token = this.data.token;

    Promise.all([
      uploader.getMediaUrl('video', topicId, token).catch(() => ({})),
      uploader.getMediaUrl('report', topicId, token).catch(() => ({}))
    ]).then((results) => {
      const video = results[0] || {};
      const report = results[1] || {};
      this.setData({
        uploadedVideoUrl: video.videoUrl || '',
        hasUploadedVideo: !!video.hasVideo,
        uploadedReportUrl: report.reportUrl || '',
        hasUploadedReport: !!report.hasReport
      });
    });
  },

  /**
   * 切换附件所属的答辩题目。
   *
   * 原先视频与报告都硬编码取 defenseTopics[0]，学生若有多道题目，
   * 附件会被挂到错误的那一道上。
   */
  onUploadTopicChange(e) {
    const index = Number(e.detail.value) || 0;
    const topics = this.data.defenseTopics || [];
    const topic = topics[index];
    this.setData({
      uploadTopicIndex: index,
      uploadTopicName: topic ? topic.topicName : '',
      isUploading: false,
      uploadStatus: 'idle',
      uploadProgress: 0,
      uploadStageText: '',
      currentUploadId: '',
      reportStatus: 'idle',
      reportUploadProgress: 0
    });
    this.refreshUploadStatus();
  },

  /** 当前选中的答辩题目 ID */
  currentUploadTopicId() {
    const topics = this.data.defenseTopics || [];
    const topic = topics[this.data.uploadTopicIndex] || topics[0];
    return topic ? (topic.topicId || topic.id) : null;
  },

  // 选择报告文件
  selectReport() {
    const that = this;

    if (this.data.isReportUploading) {
      wx.showToast({
        title: '正在上传中，请稍候',
        icon: 'none'
      });
      return;
    }

    wx.chooseMessageFile({
      count: 1,
      type: 'file',
      success: function(res) {
        if (res.tempFiles && res.tempFiles.length > 0) {
          const file = res.tempFiles[0];
          that.uploadReport(file.path, file.name, file.size);
        }
      },
      fail: function(err) {
        if (err.errMsg && err.errMsg.indexOf('cancel') === -1) {
          wx.showToast({
            title: '选择文件失败',
            icon: 'error'
          });
        }
      }
    });
  },

  /**
   * 上传报告。
   *
   * 格式与大小校验改为读取服务端规则（/api/report/policy），
   * 避免前后端各写一份硬编码白名单导致标准不一致；
   * 进度改用 wx.uploadFile 的真实进度回调，不再用定时器伪造。
   */
  uploadReport(filePath, fileName, fileSize) {
    const that = this;
    const topicId = this.currentUploadTopicId();

    if (!topicId) {
      wx.showToast({ title: '请先选择对应的答辩题目', icon: 'none' });
      return;
    }

    this.setData({
      isReportUploading: true,
      reportStatus: 'uploading',
      reportUploadProgress: 0
    });

    uploader.fetchPolicy('report', that.data.token)
      .then((policy) => {
        const ext = uploader.extensionOf(fileName);
        if ((policy.allowedExts || []).indexOf(ext) === -1) {
          throw {
            code: -1,
            msg: '不支持的格式' + (ext ? '（.' + ext + '）' : '')
              + '，仅支持：' + (policy.allowedExts || []).join(' / ')
          };
        }
        if (fileSize > policy.maxSize) {
          throw {
            code: -1,
            msg: '文件过大：' + uploader.readableSize(fileSize) + '，上限 ' + policy.maxSizeText
          };
        }
        return uploader.uploadWholeFile({
          kind: 'report',
          topicId: topicId,
          filePath: filePath,
          token: that.data.token,
          onProgress: (percent) => {
            that.setData({ reportUploadProgress: percent });
          }
        });
      })
      .then((data) => {
        that.setData({
          reportStatus: 'completed',
          isReportUploading: false,
          reportUploadProgress: 100,
          reportFileName: fileName,
          reportFileSizeReadable: uploader.readableSize(fileSize),
          uploadedReportUrl: (data && data.url) || '',
          hasUploadedReport: true
        });
        that.showBanner('success', '报告上传成功');
        that.refreshUploadStatus();
        that.loadDefenseRecords(true);
      })
      .catch((err) => {
        that.handleReportUploadError((err && err.msg) || '上传失败');
      });
  },

  // 处理报告上传错误
  handleReportUploadError(errorMsg) {
    console.error('报告上传错误:', errorMsg);
    this.setData({
      reportStatus: 'failed',
      isReportUploading: false,
      reportUploadProgress: 0
    });
    this.showBanner('error', errorMsg);
  },

  // 加载答辩题目
  loadDefenseTopics() {
    const config = require('../../utils/config.js');
    const token = this.data.token;
    
    wx.request({
      url: uploader.getBaseUrl() + '/student/getDefenseTopic',
      method: 'GET',
      header: {
        'Authorization': 'Bearer ' + token
      },
      success: (res) => {
        console.log('答辩题目接口响应:', res);

        if (res.statusCode === 401) {
          handleAuthExpired();
          return;
        }
        
        if (res.statusCode === 200 && res.data.code === 1) {
          const topics = res.data.data || [];
          
          // 更新所有答辩题目列表
          this.setData({
            defenseTopics: topics
          });
          
          // 如果有题目，设置下一轮答辩为第一个（最新的），并初始化附件所属题目
          if (topics.length > 0) {
            const latestTopic = topics[0];
            this.setData({
              nextDefense: {
                topic: latestTopic.topicName,
                date: latestTopic.defenseTime
              },
              uploadTopicIndex: 0,
              uploadTopicName: latestTopic.topicName
            });
          }
        } else {
          wx.showToast({
            title: res.data.msg || '获取答辩题目失败',
            icon: 'error'
          });
        }
      },
      fail: (err) => {
        console.error('获取答辩题目失败:', err);
        wx.showToast({
          title: '网络请求失败',
          icon: 'error'
        });
      },
      complete: () => {
        // 题目列表就绪后刷新附件状态（视频 + 报告一次查完）
        this.refreshUploadStatus();
      }
    });
  },

  // 开始答辩
  startDefense() {
    const { defenseTopics, user } = this.data;

    if (!defenseTopics || defenseTopics.length === 0) {
      wx.showToast({
        title: '暂无答辩题目',
        icon: 'none'
      });
      return;
    }

    const topic = defenseTopics[0];
    const topicId = topic.topicId || topic.id;
    const topicName = topic.topicName;

    wx.navigateTo({
      url: `/pages/defense/defense?topicId=${topicId}&topicName=${encodeURIComponent(topicName)}&userId=${user.userNumber}`,
    });
  },

  // 显示所有答辩题目弹窗
  showAllTopicsModal() {
    this.setData({
      showAllTopics: true
    });
  },

  // 关闭所有答辩题目弹窗
  closeAllTopicsModal() {
    this.setData({
      showAllTopics: false
    });
  },

  // 显示题目描述弹窗
  showTopicDescription() {
    // 从nextDefense中获取题目描述，如果有的话
    const config = require('../../utils/config.js');
    const token = this.data.token;
    
    // 先尝试从已加载的defenseTopics中找到对应的描述
    const topics = this.data.defenseTopics;
    if (topics.length > 0) {
      const currentTopic = topics[0]; // 第一个就是最新的
      this.setData({
        currentTopicDescription: currentTopic.topicDescription || '暂无题目描述',
        showTopicDescription: true
      });
    } else {
      // 如果没有加载题目列表，重新获取
      wx.request({
        url: config.getBaseUrl() + '/student/getDefenseTopic',
        method: 'GET',
        header: {
          'Authorization': 'Bearer ' + token
        },
        success: (res) => {
          if (res.statusCode === 200 && res.data.code === 1) {
            const topics = res.data.data || [];
            if (topics.length > 0) {
              this.setData({
                currentTopicDescription: topics[0].topicDescription || '暂无题目描述',
                showTopicDescription: true
              });
            } else {
              this.setData({
                currentTopicDescription: '暂无题目描述',
                showTopicDescription: true
              });
            }
          } else {
            this.setData({
              currentTopicDescription: '获取题目描述失败',
              showTopicDescription: true
            });
          }
        },
        fail: (err) => {
          console.error('获取题目描述失败:', err);
          this.setData({
            currentTopicDescription: '网络请求失败',
            showTopicDescription: true
          });
        }
      });
    }
  },

  // 关闭题目描述弹窗
  closeTopicDescription() {
    this.setData({
      showTopicDescription: false,
      currentTopicDescription: ''
    });
  },

  loadUserInfo() {
    // 从本地存储获取登录时保存的用户信息
    const token = wx.getStorageSync('token') || '';
    const userInfo = wx.getStorageSync('userInfo') || {};

    console.log('=== 用户信息加载 ===');
    console.log('从storage获取的token:', token);
    console.log('从storage获取的userInfo:', userInfo);
    console.log('当前data中的user:', this.data.user);

    // 使用后端返回的实际数据
    const name = userInfo.name || this.data.user.name;
    const id = userInfo.id || userInfo.userNumber || this.data.user.id || '未填写';  // 优先使用id字段
    const userNumber = userInfo.userNumber || this.data.user.userNumber || '未填写';  // 保留userNumber字段
    const phone = userInfo.phoneNumber || this.data.user.phone || '未绑定';
    const email = userInfo.email || this.data.user.email || '未绑定';

    console.log('解析后的用户信息:');
    console.log('- 姓名:', name);
    console.log('- ID:', id);
    console.log('- 学号:', userNumber);
    console.log('- 手机:', phone);
    console.log('- 邮箱:', email);

    this.setData({
      token,
      user: {
        name: name,
        id: id,
        userNumber: userNumber,
        phone: phone,
        email: email
      },
      editedUser: {
        name: name,
        id: id,
        userNumber: userNumber,
        phone: phone,
        email: email
      }
    });

    console.log('更新后的data.user:', this.data.user);
    console.log('=== 用户信息加载完成 ===');
  },

  // 格式化时间到分钟：兼容 "2026-09-10 14:30:00" 与 ISO "2026-09-10T14:30:00" 两种格式
  formatDateTime(t) {
    if (!t) return '';
    const s = String(t).replace('T', ' ');
    return s.length >= 16 ? s.substring(0, 16) : s;
  },

  // 加载答辩记录
  loadDefenseRecords(isRefresh = false, specificPageNum = null) {
    const that = this;
    const { pageNum, pageSize, user } = this.data;
    
    // 使用传入的页码或当前页码
    const pageNumToUse = isRefresh ? 1 : (specificPageNum || pageNum);
    
    // 如果不是刷新且没有更多数据，直接返回
    if (!isRefresh && !this.data.hasMore) {
      return;
    }
    
    // 如果是刷新，重置页码
    if (isRefresh) {
      this.setData({
        pageNum: 1,
        refreshing: true
      });
    } else {
      this.setData({
        loadingMore: true
      });
    }
    
    // 显示加载提示
    if (!isRefresh) {
      wx.showLoading({
        title: '加载中...'
      });
    }
    
    // 调用后端接口获取答辩记录
    wx.request({
      url: config.getBaseUrl() + '/student/DefenseRecords', // 使用实际的接口路径
      method: 'GET',
      data: {
        pageNum: pageNumToUse,
        pageSize: pageSize,
        userNumber: user.userNumber // 添加用户学号参数
      },
      header: {
        'Authorization': 'Bearer ' + this.data.token
      },
      success(res) {
        console.log('答辩记录接口响应:', res);

        if (res.statusCode === 401) {
          handleAuthExpired();
          return;
        }
        
        if (res.statusCode === 200 && res.data.code === 1) {
          // 后端返回的实际数据结构
          const backendRecords = res.data.data.list || [];
          const totalPages = res.data.data.pages || 1;
          const currentPage = res.data.data.pageNum || 1;
          const total = res.data.data.total || 0;
          
          // 转换后端数据格式为前端需要的格式
          const records = backendRecords.map(record => ({
            id: record.defenseRecordId ? record.defenseRecordId.toString() : null,
            topic: record.topicName || '未设置题目',
            status: record.status || 'pending',
            score: record.score ? parseFloat(record.score) : 0,
            date: that.formatDateTime(record.defenseTime),
            defenseTime: that.formatDateTime(record.defenseTime),
            feedback: record.score ? `AI评分：${Math.floor(parseFloat(record.score) * 0.9)}分。学生表现良好。` : '暂无评分'
          }));
          
          let newRecords = [];
          if (isRefresh) {
            // 刷新：替换所有数据
            newRecords = records;
          } else {
            // 加载更多：追加数据
            newRecords = [...that.data.defenseRecords, ...records];
          }
          
          // 更新数据
          that.setData({
            defenseRecords: newRecords,
            pageNum: currentPage,
            pages: totalPages,
            defenseRecordsTotal: total,
            hasMore: currentPage < totalPages,
            refreshing: false,
            loadingMore: false
          });
          
          // 更新视频上传状态
          that.updateVideoUploadStatus();
          // 更新报告上传状态
          that.updateReportUploadStatus();
        } else {
          wx.showToast({
            title: res.data.msg || '加载失败',
            icon: 'error'
          });
          that.setData({
            refreshing: false,
            loadingMore: false
          });
        }
      },
      fail(err) {
        console.error('请求失败:', err);
        wx.showToast({
          title: '网络请求失败',
          icon: 'error'
        });
        that.setData({
          refreshing: false,
          loadingMore: false
        });
      },
      complete() {
        // 标记已加载过记录，供 onShow 判断是否需要刷新
        that._recordsLoaded = true;
        // 隐藏加载提示
        if (!isRefresh) {
          wx.hideLoading();
        }
      }
    });
  },

  // 下拉刷新
  onRefresh() {
    this.loadDefenseRecords(true);
  },

  // 上拉加载更多
  onScrollToLower() {
    const { hasMore, loadingMore } = this.data;
    if (hasMore && !loadingMore) {
      const nextPage = this.data.pageNum + 1;
      // 直接传递页码参数，避免异步setData问题
      this.loadDefenseRecords(false, nextPage);
    }
  },

  // 加载答辩详情
  loadDefenseDetail(defenseId) {
    const that = this;
    
    wx.showLoading({
      title: '加载详情中...'
    });
    
    wx.request({
      url: config.getBaseUrl() + '/student/DefenseDetailRecords',
      method: 'GET',
      data: {
        defenseRecordId: defenseId
      },
      header: {
        'Authorization': 'Bearer ' + this.data.token
      },
      success(res) {
        console.log('答辩详情接口响应:', res);
        wx.hideLoading();

        if (res.statusCode === 401) {
          handleAuthExpired();
          return;
        }
        
        if (res.statusCode === 200 && res.data.code === 1) {
          const detailData = res.data.data;
          
          // 转换后端数据格式为前端需要的格式
          const selectedRecord = {
            id: detailData.defenseId,
            topic: detailData.topicName || '未设置题目',
            date: that.formatDateTime(detailData.defenseTime),
            defenseTime: that.formatDateTime(detailData.defenseTime),
            score: detailData.score ? parseFloat(detailData.score) : 0,
            studentName: detailData.studentName || '',
            studentNumber: detailData.studentNumber || '',
            defenseVideoUrl: uploader.resolveFileUrl(detailData.defenseVideoUrl || ''),
            defenseReportUrl: uploader.resolveFileUrl(detailData.defenseReportUrl || ''),
            aiVideoAnalysis: detailData.aiVideoAnalysis || '暂无视频分析',
            aiReportAnalysis: detailData.aiReportAnalysis || '暂无报告分析',
            aiAllAnalysis: detailData.aiAllAnalysis || '暂无综合评价',
            feedback: detailData.aiAllAnalysis || '暂无综合评价'
          };
          
          that.setData({
            selectedRecord: selectedRecord
          });
        } else {
          wx.showToast({
            title: res.data.msg || '加载详情失败',
            icon: 'error'
          });
        }
      },
      fail(err) {
        console.error('请求失败:', err);
        wx.showToast({
          title: '网络请求失败',
          icon: 'error'
        });
      },
      complete() {
        wx.hideLoading();
      }
    });
  },

  openRecord(e) {
    const recordId = e.currentTarget.dataset.id;
    // 直接使用recordId调用详情接口
    if (recordId) {
      this.loadDefenseDetail(recordId);
    }
  },

  // 打开答辩视频
  openVideo() {
    const videoUrl = this.data.selectedRecord.defenseVideoUrl;
    if (!videoUrl || videoUrl === 'abc') {
      wx.showToast({
        title: '视频地址无效',
        icon: 'error'
      });
      return;
    }
    
    wx.showLoading({
      title: '正在加载视频...'
    });
    
    wx.downloadFile({
      url: videoUrl,
      success: (res) => {
        if (res.statusCode === 200) {
          wx.openDocument({
            filePath: res.tempFilePath,
            fileType: 'video',
            success: (openRes) => {
              console.log('视频打开成功', openRes);
            },
            fail: (openErr) => {
              console.error('视频打开失败', openErr);
              wx.showToast({
                title: '视频打开失败，请重试',
                icon: 'error'
              });
            },
            complete: () => {
              wx.hideLoading();
            }
          });
        } else {
          wx.hideLoading();
          wx.showToast({
            title: '视频下载失败',
            icon: 'error'
          });
        }
      },
      fail: (downloadErr) => {
        console.error('视频下载失败:', downloadErr);
        wx.hideLoading();
        wx.showToast({
          title: '视频加载失败，请检查网络',
          icon: 'error'
        });
      }
    });
  },

  // 打开答辩报告
  openReport() {
    const reportUrl = this.data.selectedRecord.defenseReportUrl;
    if (!reportUrl || reportUrl === 'abc') {
      wx.showToast({
        title: '报告地址无效',
        icon: 'error'
      });
      return;
    }
    
    wx.showLoading({
      title: '正在加载报告...'
    });
    
    wx.downloadFile({
      url: reportUrl,
      success: (res) => {
        if (res.statusCode === 200) {
          // 根据文件扩展名确定文件类型
          const filePath = res.tempFilePath;
          let fileType = 'doc'; // 默认为Word文档
          
          if (filePath.endsWith('.pdf')) {
            fileType = 'pdf';
          } else if (filePath.endsWith('.doc') || filePath.endsWith('.docx')) {
            fileType = 'doc';
          } else if (filePath.endsWith('.xls') || filePath.endsWith('.xlsx')) {
            fileType = 'xls';
          } else if (filePath.endsWith('.ppt') || filePath.endsWith('.pptx')) {
            fileType = 'ppt';
          }
          
          wx.openDocument({
            filePath: filePath,
            fileType: fileType,
            success: (openRes) => {
              console.log('报告打开成功', openRes);
            },
            fail: (openErr) => {
              console.error('报告打开失败', openErr);
              wx.showToast({
                title: '报告打开失败，请重试',
                icon: 'error'
              });
            },
            complete: () => {
              wx.hideLoading();
            }
          });
        } else {
          wx.hideLoading();
          wx.showToast({
            title: '报告下载失败',
            icon: 'error'
          });
        }
      },
      fail: (downloadErr) => {
        console.error('报告下载失败:', downloadErr);
        wx.hideLoading();
        wx.showToast({
          title: '报告加载失败，请检查网络',
          icon: 'error'
        });
      }
    });
  },

  closeRecord() {
    this.setData({ selectedRecord: null });
  },

  // 打开回答详情
  openAnswerDetail(e) {
    const defenseId = e.currentTarget.dataset.id;
    if (!defenseId) {
      wx.showToast({
        title: '无效的答辩ID',
        icon: 'error'
      });
      return;
    }
    
    const that = this;
    wx.showLoading({
      title: '加载回答详情...'
    });
    
    wx.request({
      url: config.getBaseUrl() + '/student/questions/' + defenseId,
      method: 'GET',
      header: {
        'Authorization': 'Bearer ' + this.data.token
      },
      success(res) {
        console.log('回答详情接口响应:', res);
        wx.hideLoading();

        if (res.statusCode === 401) {
          handleAuthExpired();
          return;
        }
        
        if (res.statusCode === 200 && res.data.code === 1) {
          const answers = res.data.data || [];
          
          // 处理数据格式，添加前端需要的字段
          const processedAnswers = answers.map(answer => {
            // 根据questionType设置显示标签和样式类，与教师端保持一致
            let questionTypeLabel = 'AI问题';
            let questionTypeClass = 'ai';
            
            if (answer.questionType && answer.questionType.trim() === 'teacher') {
              questionTypeLabel = '教师问题';
              questionTypeClass = 'teacher';
            }
            
            return {
              ...answer,
              questionTypeLabel: questionTypeLabel,
              questionTypeClass: questionTypeClass,
              answerId: answer.answerId // 用于wx:key
            };
          });
          
          that.setData({
            selectedAnswers: processedAnswers
          });
        } else {
          wx.showToast({
            title: res.data.msg || '加载回答详情失败',
            icon: 'error'
          });
        }
      },
      fail(err) {
        console.error('请求失败:', err);
        wx.showToast({
          title: '网络请求失败',
          icon: 'error'
        });
      },
      complete() {
        wx.hideLoading();
      }
    });
  },

  // 关闭回答详情
  closeAnswers() {
    this.setData({ selectedAnswers: null });
  },

  // 视频上传相关方法
  selectVideo() {
    const that = this;
    
    // 检查是否正在上传
    if (this.data.isUploading) {
      wx.showModal({
        title: '确认取消',
        content: '当前有上传任务正在进行，是否要取消并重新选择？',
        success: function(res) {
          if (res.confirm) {
            that.cancelUpload();
            setTimeout(() => that.selectVideo(), 100);
          }
        }
      });
      return;
    }

    // 检查是否已经上传过视频（基于当前答辩题目）
    let hasExistingVideo = false;
    if (this.data.defenseRecords && this.data.defenseTopics.length > 0) {
      const currentTopicId = this.data.defenseTopics[0].topicId || this.data.defenseTopics[0].id;
      // 检查答辩记录中是否有对应当前题目的视频
      const existingRecord = this.data.defenseRecords.find(record => {
        // 这里需要根据实际的数据结构来判断
        // 假设defenseRecords中的每个记录都有topicId字段
        return record.topicId === currentTopicId && record.defenseVideoUrl && record.defenseVideoUrl !== 'abc';
      });
      hasExistingVideo = !!existingRecord;
    }

    // 如果已经上传过视频，显示重新上传确认
    if (hasExistingVideo) {
      wx.showModal({
        title: '重新上传确认',
        content: '您已经为当前答辩题目上传过视频，是否要重新上传？这将替换原有的视频。',
        showCancel: true,
        cancelText: '取消',
        confirmText: '重新上传',
        success: function(res) {
          if (res.confirm) {
            // 继续选择视频
            that.proceedWithVideoSelection();
          }
        }
      });
    } else {
      // 直接选择视频
      that.proceedWithVideoSelection();
    }
  },

  // 实际的视频选择逻辑
  proceedWithVideoSelection() {
    const that = this;
    
    // 使用 chooseMedia 替代 chooseVideo（推荐的新API）
    wx.chooseMedia({
      count: 1, // 只能选择一个视频
      mediaType: ['video'], // 只选择视频
      sourceType: ['album', 'camera'], // 可以从相册选择或拍摄
      maxDuration: 600, // 最大时长10分钟（600秒）
      camera: 'back',
      success: function(res) {
        console.log('选择视频成功:', res);
        
        if (res.tempFiles && res.tempFiles.length > 0) {
          const videoFile = res.tempFiles[0];
          const videoPath = videoFile.tempFilePath;
          // 从临时路径取真实扩展名：原先一律硬编码成 .mp4，
          // 会把 mov/avi 等格式错误地当成 mp4 存下来
          const ext = uploader.extensionOf(videoPath.split('?')[0]) || 'mp4';
          const timestamp = Date.now();
          const randomStr = Math.random().toString(36).substring(2, 8);
          const fileName = `video_${timestamp}_${randomStr}.${ext}`;
          const fileSize = videoFile.size;
          
          // 开始上传流程
          that.startUpload(videoPath, fileName, fileSize);
        } else {
          wx.showToast({
            title: '未选择到视频文件',
            icon: 'error'
          });
        }
      },
      fail: function(err) {
        console.error('选择视频失败详细信息:', err);
        
        // 根据不同的错误类型提供具体提示
        let errorMessage = '选择视频失败';
        let showSettingButton = false;
        
        if (err.errMsg) {
          if (err.errMsg.includes('permission')) {
            errorMessage = '需要相册或相机权限';
            showSettingButton = true;
          } else if (err.errMsg.includes('cancel') || err.errMsg.includes('deny')) {
            // 用户取消了视频选择或拒绝了权限，不显示错误
            console.log('用户取消了视频选择或拒绝了权限');
            return;
          } else if (err.errMsg.includes('invalid')) {
            errorMessage = '视频格式不支持';
          } else if (err.errMsg.includes('size')) {
            errorMessage = '视频文件过大';
          } else if (err.errMsg.includes('scope is not declared')) {
            errorMessage = '缺少必要的权限声明，请检查app.json配置';
            showSettingButton = true;
          } else {
            errorMessage = '选择视频失败，请重试';
          }
        }
        
        if (showSettingButton) {
          wx.showModal({
            title: '权限不足',
            content: errorMessage + '。请在设置中开启相应权限，然后重试。',
            showCancel: true,
            cancelText: '取消',
            confirmText: '去设置',
            success: function(modalRes) {
              if (modalRes.confirm) {
                wx.openSetting({
                  success: function(settingRes) {
                    console.log('用户已授权:', settingRes.authSetting);
                    if (settingRes.authSetting['scope.writePhotosAlbum'] || 
                        settingRes.authSetting['scope.camera']) {
                      that.proceedWithVideoSelection();
                    }
                  }
                });
              }
            }
          });
        } else {
          wx.showToast({
            title: errorMessage,
            icon: 'error',
            duration: 3000
          });
        }
      }
    });
  },

  // 格式化文件大小
  formatFileSize(size) {
    if (size < 1024) {
      return size + ' B';
    } else if (size < 1024 * 1024) {
      return (size / 1024).toFixed(2) + ' KB';
    } else if (size < 1024 * 1024 * 1024) {
      return (size / (1024 * 1024)).toFixed(2) + ' MB';
    } else {
      return (size / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    }
  },

  // 开始上传流程（分片细节全部交给 utils/uploader.js，页面只负责状态与提示）
  startUpload(videoPath, fileName, fileSize) {
    const that = this;
    const topicId = this.currentUploadTopicId();

    if (!topicId) {
      wx.showToast({ title: '请先选择对应的答辩题目', icon: 'none' });
      return;
    }

    this.setData({
      isUploading: true,
      uploadStatus: 'init',
      currentFileName: fileName,
      currentFileSize: fileSize,
      currentFileSizeReadable: uploader.readableSize(fileSize),
      uploadProgress: 0,
      currentChunkIndex: 0,
      uploadStageText: '正在准备上传...',
      currentVideoPath: videoPath,
      currentUploadId: ''
    });

    uploader.fetchPolicy('video', that.data.token)
      .then((policy) => {
        const ext = uploader.extensionOf(fileName);
        if ((policy.allowedExts || []).indexOf(ext) === -1) {
          throw {
            code: -1,
            msg: '不支持的视频格式' + (ext ? '（.' + ext + '）' : '')
              + '，仅支持：' + (policy.allowedExts || []).join(' / ')
          };
        }
        if (fileSize > policy.maxSize) {
          throw {
            code: -1,
            msg: '视频过大：' + uploader.readableSize(fileSize) + '，上限 ' + policy.maxSizeText
          };
        }
        return uploader.uploadVideo({
          topicId: topicId,
          filePath: videoPath,
          fileName: fileName,
          fileSize: fileSize,
          token: that.data.token,
          uploadId: that.data.currentUploadId || '',
          onStage: (stage) => {
            if (stage === 'merging') {
              that.setData({ uploadStatus: 'merging', uploadStageText: '正在合并视频...' });
            } else if (stage === 'init') {
              that.setData({ uploadStatus: 'init', uploadStageText: '正在准备上传...' });
            } else {
              that.setData({ uploadStatus: 'uploading', uploadStageText: '正在上传分片...' });
            }
          },
          onProgress: (percent) => {
            that.setData({ uploadProgress: percent });
          }
        });
      })
      .then((data) => {
        that.setData({
          uploadStatus: 'completed',
          isUploading: false,
          uploadProgress: 100,
          uploadStageText: '',
          currentUploadId: '',
          currentProcessingId: (data && data.processingId) || '',
          uploadedVideoUrl: (data && data.videoUrl) || '',
          hasUploadedVideo: true
        });
        // 成功提示统一走顶部提示条（原先卡片内那行绿色文字不会消失）
        if (data && data.degraded) {
          that.showBanner('success', '视频上传成功（当前环境不支持分片，已整文件上传）');
        } else {
          that.showBanner('success', '视频上传成功');
        }
        that.refreshUploadStatus();
        that.loadDefenseRecords(true);
        if (data && data.processingId) {
          that.watchProcessingStatus(data.processingId);
        }
      })
      .catch((err) => {
        // 打印原始错误对象：否则只剩"上传失败"这种无信息量的兜底文案，无法定位
        console.error('[上传] 原始错误对象:', err);
        // 记住 uploadId：用户点"重试"时可续传，不必重传已成功的分片
        that.setData({ currentUploadId: (err && err.uploadId) || '' });
        that.handleUploadError(
          (err && err.msg) || (err && err.errMsg) || '上传失败，详见 Console 日志'
        );
      });
  },

  /** 轮询视频后处理状态（后端异步处理，失败只记日志不影响主流程） */
  watchProcessingStatus(processingId) {
    let times = 0;
    const timer = setInterval(() => {
      times += 1;
      if (times > 20) {
        clearInterval(timer);
        return;
      }
      uploader.getProcessingStatus(processingId, this.data.token)
        .then((data) => {
          if (!data || data.status === 'COMPLETED' || data.status === 'FAILED') {
            clearInterval(timer);
          }
        })
        .catch(() => {
          clearInterval(timer);
        });
    }, 3000);
  },

  /** 重试上传：带上 uploadId 续传，已成功的分片不会重传 */
  retryUploadVideo() {
    if (!this.data.currentVideoPath) {
      // 临时文件已被系统清理（例如中途退出过小程序），只能重新选择
      this.selectVideo();
      return;
    }
    this.startUpload(this.data.currentVideoPath, this.data.currentFileName, this.data.currentFileSize);
  },

  // 取消上传（服务端会删除已落盘的半成品文件并清理会话）
  cancelUpload() {
    const uploadId = this.data.currentUploadId;

    if (!uploadId) {
      this.resetUploadState();
      return;
    }

    uploader.abortUpload(uploadId, this.data.token)
      .then(() => {
        this.resetUploadState();
        wx.showToast({ title: '已取消上传', icon: 'none' });
      })
      .catch((err) => {
        console.error('取消上传失败:', err);
        this.resetUploadState();
        wx.showToast({ title: (err && err.msg) || '取消上传失败', icon: 'error' });
      });
  },

  // 重置上传状态
  resetUploadState() {
    this.setData({
      isUploading: false,
      uploadStatus: 'idle',
      uploadProgress: 0,
      currentChunkIndex: 0,
      currentUploadId: '',
      currentFileName: '',
      currentFileSize: 0,
      currentFileSizeReadable: '',
      currentTotalChunks: 0,
      currentProcessingId: ''
    });
  },

  /**
   * 顶部提示条（成功 / 失败统一走这里）。
   *
   * 替代原先卡片内那条「上传成功后常驻、无法消去」的绿色文字：
   * 现在统一在页面最上方提示，5 秒后自动消失，参考浏览器的成功/错误通知。
   */
  showBanner(type, text) {
    if (this._bannerTimer) {
      clearTimeout(this._bannerTimer);
    }
    this.setData({
      banner: {
        visible: true,
        type: type === 'error' ? 'error' : 'success',
        text: text || ''
      }
    });
    this._bannerTimer = setTimeout(() => {
      this.hideBanner();
    }, 5000);
  },

  /** 收起提示条 */
  hideBanner() {
    if (this._bannerTimer) {
      clearTimeout(this._bannerTimer);
      this._bannerTimer = null;
    }
    this.setData({ 'banner.visible': false });
  },

  /** 页面卸载时清掉定时器，避免对已销毁的页面 setData */
  onUnload() {
    if (this._bannerTimer) {
      clearTimeout(this._bannerTimer);
      this._bannerTimer = null;
    }
  },

  // 处理上传错误
  handleUploadError(errorMsg) {
    console.error('上传错误:', errorMsg);

    this.setData({
      uploadStatus: 'failed',
      isUploading: false
    });

    this.showBanner('error', errorMsg);
  },

  // 退出登录
  logout() {
    const that = this;
    
    // 显示确认对话框
    wx.showModal({
      title: '确认退出',
      content: '确定要退出登录吗？',
      success(res) {
        if (res.confirm) {
          // 清除本地存储的 token 和用户信息
          wx.removeStorageSync('token');
          wx.removeStorageSync('user');
          wx.removeStorageSync('role');
          
          // 跳转到登录页面
          wx.redirectTo({
            url: '/pages/login/login'
          });
        }
      }
    });
  },

  /** 预览已上传的视频 */
  previewVideo() {
    const url = uploader.resolveFileUrl(this.data.uploadedVideoUrl);
    if (!url) {
      wx.showToast({ title: '还没有上传视频', icon: 'none' });
      return;
    }
    wx.previewMedia({
      sources: [{ url: url, type: 'video' }],
      fail: () => {
        wx.showToast({ title: '当前微信版本不支持预览，请更新微信', icon: 'none' });
      }
    });
  },

  /** 查看已上传的报告（openDocument 右上角菜单可"保存到手机"，即下载） */
  openUploadedReport() {
    const url = uploader.resolveFileUrl(this.data.uploadedReportUrl);
    if (!url) {
      wx.showToast({ title: '还没有上传报告', icon: 'none' });
      return;
    }
    wx.showLoading({ title: '正在打开...' });
    wx.downloadFile({
      url: url,
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode !== 200) {
          wx.showToast({ title: '文件下载失败', icon: 'error' });
          return;
        }
        wx.openDocument({
          filePath: res.tempFilePath,
          showMenu: true,
          fail: () => {
            wx.showToast({ title: '该格式暂不支持预览，可用右上角菜单保存', icon: 'none' });
          }
        });
      },
      fail: () => {
        wx.hideLoading();
        wx.showToast({ title: '文件下载失败', icon: 'error' });
      }
    });
  },

  /** 删除已上传的视频 */
  deleteVideo() {
    this.confirmDeleteMedia('video');
  },

  /** 删除已上传的报告 */
  deleteReport() {
    this.confirmDeleteMedia('report');
  },

  /** 删除附件：二次确认 → 删库并清理磁盘文件 → 刷新状态 */
  confirmDeleteMedia(kind) {
    const isVideo = kind === 'video';
    const label = isVideo ? '答辩视频' : '答辩报告';
    const hasFile = isVideo ? this.data.hasUploadedVideo : this.data.hasUploadedReport;

    if (!hasFile) {
      wx.showToast({ title: '还没有上传' + label, icon: 'none' });
      return;
    }
    const topicId = this.currentUploadTopicId();
    if (!topicId) {
      wx.showToast({ title: '请先选择对应的答辩题目', icon: 'none' });
      return;
    }

    wx.showModal({
      title: '确认删除',
      content: '确定要删除已上传的' + label + '吗？删除后需要重新上传。',
      confirmText: '删除',
      confirmColor: '#e64340',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        wx.showLoading({ title: '正在删除...' });
        uploader.deleteMedia(kind, topicId, this.data.token)
          .then(() => {
            wx.hideLoading();
            this.setData(isVideo
              ? {
                uploadedVideoUrl: '',
                hasUploadedVideo: false,
                uploadStatus: 'idle',
                uploadProgress: 0,
                currentFileName: '',
                currentFileSizeReadable: ''
              }
              : {
                uploadedReportUrl: '',
                hasUploadedReport: false,
                reportStatus: 'idle',
                reportUploadProgress: 0,
                reportFileName: '',
                reportFileSizeReadable: ''
              });
            wx.showToast({ title: label + '已删除', icon: 'success' });
            this.loadDefenseRecords(true);
          })
          .catch((err) => {
            wx.hideLoading();
            wx.showToast({ title: (err && err.msg) || '删除失败', icon: 'error' });
          });
      }
    });
  }
});