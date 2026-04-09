// 引入全局配置
const config = require('../../utils/config.js');

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
    currentTotalChunks: 0,
    currentChunkIndex: 0,
    transcriptionRecordId: null,
    transcriptionStatus: null,
    
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
    hasUploadedVideo: false
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
      url: config.serverUrl + '/editUserInfo', // 使用学生专用接口
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
    let hasUploadedVideo = false;
    
    // 检查是否有答辩题目和答辩记录
    if (this.data.defenseTopics && this.data.defenseTopics.length > 0 && 
        this.data.defenseRecords && this.data.defenseRecords.length > 0) {
      
      const currentTopicId = this.data.defenseTopics[0].topicId || this.data.defenseTopics[0].id;
      
      console.log('检查视频上传状态:');
      console.log('currentTopicId:', currentTopicId);
      console.log('defenseTopics[0]:', this.data.defenseTopics[0]);
      console.log('defenseRecords:', this.data.defenseRecords);
      
      // 检查答辩记录中是否有对应当前题目的有效视频
      const existingRecord = this.data.defenseRecords.find(record => {
        // 根据实际的数据结构判断，这里假设record有topicId字段
        // 如果没有topicId，可能需要通过其他方式匹配
        const recordTopicId = record.topicId || record.id; // 尝试不同的字段名
        const hasValidVideo = record.defenseVideoUrl && 
                             record.defenseVideoUrl !== 'abc' && 
                             record.defenseVideoUrl.trim() !== '';
        console.log('检查记录:', record, 'recordTopicId:', recordTopicId, 'hasValidVideo:', hasValidVideo);
        return recordTopicId === currentTopicId && hasValidVideo;
      });
      
      hasUploadedVideo = !!existingRecord;
      console.log('hasUploadedVideo:', hasUploadedVideo, 'existingRecord:', existingRecord);
    } else {
      console.log('缺少必要数据: defenseTopics长度:', this.data.defenseTopics?.length, 
                  'defenseRecords长度:', this.data.defenseRecords?.length);
    }
    
    this.setData({
      hasUploadedVideo: hasUploadedVideo
    });
  },
  
  // 加载答辩题目
  loadDefenseTopics() {
    const config = require('../../utils/config.js');
    const token = this.data.token;
    
    wx.request({
      url: config.serverUrl + '/student/getDefenseTopic',
      method: 'GET',
      header: {
        'Authorization': 'Bearer ' + token
      },
      success: (res) => {
        console.log('答辩题目接口响应:', res);
        
        if (res.statusCode === 200 && res.data.code === 1) {
          const topics = res.data.data || [];
          
          // 更新所有答辩题目列表
          this.setData({
            defenseTopics: topics
          });
          
          // 如果有题目，设置下一轮答辩为第一个（最新的）
          if (topics.length > 0) {
            const latestTopic = topics[0];
            this.setData({
              nextDefense: {
                topic: latestTopic.topicName,
                date: latestTopic.defenseTime
              }
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
        // 更新视频上传状态
        this.updateVideoUploadStatus();
      }
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
        url: config.serverUrl + '/student/getDefenseTopic',
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
      url: config.serverUrl + '/student/DefenseRecords', // 使用实际的接口路径
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
            score: record.score ? parseFloat(record.score) : 0,
            date: record.defenseTime ? record.defenseTime.split(' ')[0] : '',
            defenseTime: record.defenseTime || '',
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
      url: config.serverUrl + '/student/DefenseDetailRecords',
      method: 'GET',
      data: {
        defenseRecordId: defenseId
      },
      header: {
        'Authorization': 'Bearer ' + this.data.token
      },
      success(res) {
        console.log('答辩详情接口响应:', res);
        
        if (res.statusCode === 200 && res.data.code === 1) {
          const detailData = res.data.data;
          
          // 转换后端数据格式为前端需要的格式
          const selectedRecord = {
            id: detailData.defenseId,
            topic: detailData.topicName || '未设置题目',
            date: detailData.defenseTime ? detailData.defenseTime.split(' ')[0] : '',
            defenseTime: detailData.defenseTime || '',
            score: detailData.score ? parseFloat(detailData.score) : 0,
            studentName: detailData.studentName || '',
            studentNumber: detailData.studentNumber || '',
            defenseVideoUrl: detailData.defenseVideoUrl || '',
            defenseReportUrl: detailData.defenseReportUrl || '',
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
      url: config.serverUrl + '/student/questions/' + defenseId,
      method: 'GET',
      header: {
        'Authorization': 'Bearer ' + this.data.token
      },
      success(res) {
        console.log('回答详情接口响应:', res);
        
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
          // 生成可靠的文件名
          const timestamp = Date.now();
          const randomStr = Math.random().toString(36).substring(2, 8);
          const fileName = `video_${timestamp}_${randomStr}.mp4`;
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

  // 开始上传流程
  startUpload(videoPath, fileName, fileSize) {
    const that = this;
    
    // 设置上传状态
    this.setData({
        isUploading: true,
        uploadStatus: 'init',
        currentFileName: fileName,
        currentFileSize: fileSize,
        currentFileSizeReadable: this.formatFileSize(fileSize),
        uploadProgress: 0,
        currentChunkIndex: 0
    });

    // 第一步：初始化上传
    wx.request({
        url: config.serverUrl + '/api/video/init',
        method: 'POST',
        data: {
            fileName: fileName
        },
        header: {
            'Authorization': 'Bearer ' + this.data.token,
            'content-type': 'application/x-www-form-urlencoded'
        },
        success: function(res) {
            console.log('初始化上传响应:', res);
            
            if (res.statusCode === 200 && res.data.code === 1) {
                // 直接从 data 对象获取
                const data = res.data.data;
                const uploadId = data.uploadId;
                const uniqueFileName = data.fileName;
                
                if (uploadId && uniqueFileName) {
                    that.setData({
                        currentUploadId: uploadId,
                        currentFileName: uniqueFileName
                    });
                    
                    // 计算分片数量（每片 20MB）
                    const PART_SIZE = 20 * 1024 * 1024; // 20MB
                    const totalChunks = Math.ceil(fileSize / PART_SIZE);
                    
                    that.setData({
                        currentTotalChunks: totalChunks,
                        uploadStatus: 'uploading'
                    });
                    
                    // 开始上传第一个分片
                    that.uploadChunk(videoPath, uploadId, uniqueFileName, 1, totalChunks);
                } else {
                    that.handleUploadError('初始化失败：无法解析 uploadId');
                }
            } else {
                // 增强错误信息显示
                let errorMsg = '初始化失败';
                if (res.data && typeof res.data === 'string') {
                    errorMsg += '：' + res.data;
                } else if (res.data && res.data.msg) {
                    errorMsg += '：' + res.data.msg;
                } else {
                    errorMsg += '：HTTP ' + res.statusCode;
                }
                that.handleUploadError(errorMsg);
            }
        },
        fail: function(err) {
            console.error('初始化上传失败:', err);
            let errorMsg = '初始化失败：网络错误';
            if (err.errMsg) {
                errorMsg += ' - ' + err.errMsg;
            }
            that.handleUploadError(errorMsg);
        }
    });
  },

  // 上传单个分片
  uploadChunk(videoPath, uploadId, fileName, chunkIndex, totalChunks) {
    const that = this;
    
    // 更新进度
    const progress = Math.floor((chunkIndex - 1) / totalChunks * 100);
    this.setData({
      uploadProgress: progress,
      currentChunkIndex: chunkIndex
    });

    // 创建分片上传任务
    wx.uploadFile({
      url: config.serverUrl + '/api/video/upload',
      filePath: videoPath,
      name: 'file',
      formData: {
        uploadId: uploadId,
        fileName: fileName,
        partNumber: chunkIndex
      },
      header: {
        'Authorization': 'Bearer ' + this.data.token,
        'content-type': 'multipart/form-data'
      },
      success: function(res) {
        console.log(`分片 ${chunkIndex} 上传成功:`, res);
        
        if (res.statusCode === 200) {
          // 如果还有更多分片，继续上传下一个
          if (chunkIndex < totalChunks) {
            that.uploadChunk(videoPath, uploadId, fileName, chunkIndex + 1, totalChunks);
          } else {
            // 所有分片上传完成，开始合并
            that.completeUpload(uploadId, fileName);
          }
        } else {
          that.handleUploadError(`分片 ${chunkIndex} 上传失败：${res.data || '未知错误'}`);
        }
      },
      fail: function(err) {
        console.error(`分片 ${chunkIndex} 上传失败:`, err);
        that.handleUploadError(`分片 ${chunkIndex} 上传失败：网络错误`);
      }
    });
  },

  // 完成上传（合并分片）
  completeUpload(uploadId, fileName) {
    const that = this;
    
    this.setData({
      uploadStatus: 'merging',
      uploadProgress: 100
    });

    // 获取当前答辩题目的topicId
    let topicId = null;
    if (this.data.defenseTopics && this.data.defenseTopics.length > 0) {
      topicId = this.data.defenseTopics[0].topicId || this.data.defenseTopics[0].id;
    }
    
    // 添加调试日志
    console.log('准备上传视频，参数信息:');
    console.log('userId:', this.data.user.userNumber);
    console.log('topicId:', topicId);
    console.log('defenseTopics[0]:', this.data.defenseTopics[0]);
    
    wx.request({
      url: config.serverUrl + '/api/video/complete',
      method: 'POST',
      data: {
        uploadId: uploadId,
        fileName: fileName,
        userId: this.data.user.userNumber,
        topicId: topicId // 添加topicId参数
      },
      header: {
        'Authorization': 'Bearer ' + this.data.token,
        'content-type': 'application/x-www-form-urlencoded'
      },
      success: function(res) {
        console.log('完成上传响应:', res);
        
        if (res.statusCode === 200 && res.data.code === 1) {
          // 上传成功，但视频仍在后台处理
          that.setData({
            uploadStatus: 'completed',
            isUploading: false,
            currentProcessingId: res.data.data.processingId // 如果后端返回处理ID
          });
          
          wx.showToast({
            title: '上传成功！',
            icon: 'success'
          });
          
          // 更新视频上传状态（稍后更新，因为数据库可能还未完成）
          setTimeout(() => {
            that.updateVideoUploadStatus();
          }, 3000);
          
          // 提示用户视频正在后台处理
          wx.showModal({
            title: '上传完成',
            content: '视频已上传成功，正在后台进行转码和AI分析处理，请稍后查看处理结果。',
            showCancel: false,
            confirmText: '确定'
          });
          
        } else {
          // 增强错误信息显示
          let errorMsg = '合并失败';
          if (res.data && typeof res.data === 'string') {
            errorMsg += '：' + res.data;
          } else if (res.data && res.data.msg) {
            errorMsg += '：' + res.data.msg;
          } else {
            errorMsg += '：HTTP ' + res.statusCode;
          }
          that.handleUploadError(errorMsg);
        }
      },
      fail: function(err) {
        console.error('完成上传失败:', err);
        let errorMsg = '合并失败：网络错误';
        if (err.errMsg) {
          errorMsg += ' - ' + err.errMsg;
        }
        that.handleUploadError(errorMsg);
      }
    });
  },

  // 取消上传
  cancelUpload() {
    const that = this;
    const { currentUploadId, currentFileName } = this.data;
    
    if (currentUploadId && currentFileName) {
      wx.request({
        url: config.serverUrl + '/api/video/abort',
        method: 'POST',
        data: {
          uploadId: currentUploadId,
          fileName: currentFileName
        },
        header: {
          'Authorization': 'Bearer ' + this.data.token,
          'content-type': 'application/x-www-form-urlencoded'
        },
        success: function(res) {
          console.log('取消上传成功:', res);
          that.resetUploadState();
          wx.showToast({
            title: '已取消上传',
            icon: 'none'
          });
        },
        fail: function(err) {
          console.error('取消上传失败:', err);
          that.resetUploadState();
          wx.showToast({
            title: '取消上传失败',
            icon: 'error'
          });
        }
      });
    } else {
      this.resetUploadState();
    }
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

  // 处理上传错误
  handleUploadError(errorMsg) {
    console.error('上传错误:', errorMsg);
    
    this.setData({
      uploadStatus: 'failed',
      isUploading: false
    });
    
    wx.showToast({
      title: errorMsg,
      icon: 'error',
      duration: 3000
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
  }
});