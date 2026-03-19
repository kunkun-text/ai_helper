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

    nextDefense: {
      topic: '大数据如何处理海量数据',
      date: '2024-04-25'
    },
    defenseRecords: [
      {
        id: '1',
        topic: '大数据如何分布式处理有哪些方法？',
        score: 82,
        date: '2024-04-21',
        feedback: '回答清晰，逻辑性强。建议加强对MapReduce原理的理解。'
      },
      {
        id: '2',
        topic: '生物技术对头发是否有提香功能？',
        score: 91,
        date: '2024-04-21',
        feedback: '表现优秀，论述全面。对生物技术应用理解深入。'
      },
      {
        id: '3',
        topic: '大数据如何分布式处理有哪些方法？',
        score: 88,
        date: '2024-04-21',
        feedback: '整体不错，建议在案例分析中更加详细。'
      }
    ],
    previewRecords: [],
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
    loadingMore: false
  },

  onLoad() {
    console.log('学生页面onLoad执行');
    this.loadUserInfo();

    this.setData({
      previewRecords: this.data.defenseRecords.slice(0, 2)
    });
  },

  onShow() {
    console.log('学生页面onShow执行');
    this.loadUserInfo();
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
    const { pageNum, pageSize } = this.data;
    
    // 使用传入的页码或当前页码
    const pageNumToUse = isRefresh ? 1 : (specificPageNum || pageNum);
    
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
      url: config.serverUrl + '/student/defense/records', // 根据实际接口路径调整
      method: 'GET',
      data: {
        pageNum: pageNumToUse,
        pageSize: pageSize
      },
      header: {
        'Authorization': 'Bearer ' + this.data.token
      },
      success(res) {
        console.log('答辩记录接口响应:', res);
        
        if (res.statusCode === 200 && res.data.code === 1) {
          const records = res.data.data.records || [];
          const totalPages = res.data.data.pages || 1;
          const currentPage = res.data.data.pageNum || 1;
          
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
            previewRecords: newRecords.slice(0, 3), // 首页只显示前3条
            pageNum: currentPage,
            pages: totalPages,
            hasMore: currentPage < totalPages,
            refreshing: false,
            loadingMore: false
          });
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

  switchTab(e) {
    const tab = e.currentTarget.dataset.tab;
    if (tab) {
      this.setData({ activeTab: tab });
    }
  },

  openRecord(e) {
    const recordId = e.currentTarget.dataset.id;
    const record = this.data.defenseRecords.find(item => item.id === recordId);
    if (record) {
      this.setData({ selectedRecord: record });
    }
  },

  closeRecord() {
    this.setData({ selectedRecord: null });
  },

  startEdit() {
    this.setData({
      isEditing: true,
      editedUser: { ...this.data.user }
    });
  },

  onEditName(e) {
    this.setData({
      'editedUser.name': e.detail.value
    });
  },

  onEditUserNumber(e) {
    this.setData({
      'editedUser.userNumber': e.detail.value
    });
  },

  onEditPhone(e) {
    this.setData({
      'editedUser.phone': e.detail.value
    });
  },

  onEditEmail(e) {
    this.setData({
      'editedUser.email': e.detail.value
    });
  },

  saveProfile() {
    const that = this;
    
    // 显示加载提示
    wx.showLoading({
      title: '保存中...'
    });

    // 构造请求参数
    const requestData = {
      id: this.data.user.id,
      name: this.data.editedUser.name,
      userNumber: this.data.editedUser.userNumber,
      phoneNumber: this.data.editedUser.phone,
      email: this.data.editedUser.email
    };

    console.log('发送到后端的数据:', requestData);

    // 发送请求到后端
    wx.request({
      url: config.serverUrl + '/editUserInfo',
      method: 'POST',
      data: requestData,
      header: {
        'content-type': 'application/json',
        'Authorization': 'Bearer ' + this.data.token
      },
      success(res) {
        console.log('后端响应:', res);
        
        if (res.statusCode === 200 && res.data.code === 1) {
          // 请求成功
          wx.showToast({
            title: '保存成功',
            icon: 'success'
          });

          // 更新本地用户信息
          const updatedUser = {
            name: that.data.editedUser.name,
            userNumber: that.data.editedUser.userNumber,
            phone: that.data.editedUser.phone,
            email: that.data.editedUser.email
          };

          that.setData({
            user: updatedUser,
            isEditing: false
          });

          // 更新本地存储的用户信息
          const userInfo = wx.getStorageSync('userInfo') || {};
          Object.assign(userInfo, updatedUser);
          wx.setStorageSync('userInfo', userInfo);

        } else {
          // 请求失败
          wx.showToast({
            title: res.data.msg || '保存失败',
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
        // 隐藏加载提示
        wx.hideLoading();
      }
    });
  },

  logout() {
    wx.showToast({
      title: '已退出登录',
      icon: 'none'
    });
  },

  // 视频上传相关方法
  selectVideo() {
    const that = this;
    
    // 检查是否正在上传
    if (this.data.isUploading) {
      wx.showToast({
        title: '正在上传中，请稍候',
        icon: 'none'
      });
      return;
    }

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
            // 用户取消或拒绝，不显示错误
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
                      that.selectVideo();
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

  // 开始上传流程
  startUpload(videoPath, fileName, fileSize) {
    const that = this;
    
    // 设置上传状态
    this.setData({
        isUploading: true,
        uploadStatus: 'init',
        currentFileName: fileName,
        currentFileSize: fileSize,
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

    wx.request({
      url: config.serverUrl + '/api/video/complete',
      method: 'POST',
      data: {
        uploadId: uploadId,
        fileName: fileName
      },
      header: {
        'Authorization': 'Bearer ' + this.data.token,
        'content-type': 'application/x-www-form-urlencoded'
      },
      success: function(res) {
        console.log('完成上传响应:', res);
        
        if (res.statusCode === 200) {
          // 上传成功
          that.setData({
            uploadStatus: 'completed',
            isUploading: false
          });
          
          wx.showToast({
            title: '上传成功！',
            icon: 'success'
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
  }
});