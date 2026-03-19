// 引入全局配置
const config = require('../../utils/config.js');

Page({
  data: {
    activeTab: 'home',
    token: '',
    user: {
      name: '李老师',
      id: 'T2024001',
      userNumber: 'T2024001',
      phone: '13800000000',
      email: 'teacher@example.com'
    },
    topics: [], // 清空初始数组，从后端获取数据
    total: 0, // 添加total字段存储总数
    // 主页面和搜索页面分别使用独立的数据源
    defenseRecords: [], // 通用数据源，根据当前tab动态指向不同数据
    homeDefenseRecords: [], // 主页面专用数据源
    searchDefenseRecords: [], // 搜索页面专用数据源
    defenseRecordsTotal: 0, // 添加答辩记录总数字段
    defenseRecordsCurrentPage: 1,
    defenseRecordsPageSize: 10, //
    defenseRecordsHasMore: true,
    defenseRecordsLoading: false,
    defenseRecordsRefreshing: false, // 添加下拉刷新状态
    
    // 搜索相关字段
    searchUserName: '',
    searchUserNumber: '',
    searchTopicName: '', // 新增题目名称搜索字段
    isSearching: false, // 标识是否处于搜索模式
    
    selectedRecord: null,
    selectedTopic: null, // 添加选中的题目详情
    showAddTopic: false,
    isEditing: false,
    editedUser: {
      name: '',
      id: '',
      userNumber: '',
      phone: '',
      email: ''
    },
    newTopic: {
      title: '',
      description: '',
      date: '',
      questions: [] // 用于存储问题和标准答案数组
    },
    // 分页相关数据
    currentPage: 1,
    pageSize: 3,
    hasMore: true,
    loading: false,
    isEditingTopic: false, // 标识是否正在编辑题目
    selectedAnswers: null // 添加选中的回答详情
  },

  onLoad() {
    console.log('老师页面onLoad执行');
    this.loadUserInfo();
    
    // 页面加载时获取第一页数据
    this.loadTopics();
    
    // 加载主页面答辩记录（全部数据）
    this.loadHomeDefenseRecords();

    this.setData({
      editedUser: { ...this.data.user }
    });
  },

  // 根据搜索条件加载答辩记录
  loadDefenseRecordsBySearch(isRefresh = false, specificPageNum = null) {
    if (!isRefresh && (this.data.defenseRecordsLoading || !this.data.defenseRecordsHasMore)) {
      console.log('搜索加载被阻止：正在加载中或没有更多数据');
      return;
    }

    this.setData({
      defenseRecordsLoading: !isRefresh,
      defenseRecordsRefreshing: isRefresh
    });

    // 使用全局配置的服务器地址
    const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
    const requestUrl = `${serverUrl}/teacher/defense/search`;
    const token = wx.getStorageSync('token');
    
    const pageNumToUse = isRefresh ? 1 : (specificPageNum || this.data.defenseRecordsCurrentPage);
    
    // 构建搜索参数，包含新增的topicName字段
    const searchParams = {
      pageNum: pageNumToUse,
      pageSize: this.data.defenseRecordsPageSize,
      userName: this.data.searchUserName.trim(),
      userNumber: this.data.searchUserNumber.trim(),
      topicName: this.data.searchTopicName.trim() // 新增题目名称搜索参数
    };
    
    console.log('搜索请求参数:', searchParams);
    console.log('搜索请求URL:', requestUrl);
    console.log('搜索请求Token:', token ? '存在' : '不存在');
    
    wx.request({
      url: requestUrl,
      method: 'POST',
      header: {
        'Content-Type': 'application/json',
        'Authorization': token ? 'Bearer ' + token : ''
      },
      data: searchParams,
      success: (res) => {
        console.log('搜索答辩记录成功:', res.data);
        
        if (res.data.code === 1) {
          const response = res.data.data;
          const newData = response.list.map(record => ({
            id: record.defenseRecordId.toString(),
            studentName: record.userName.trim(),
            studentId: record.userNumber,
            topic: record.topicName,
            score: parseFloat(record.score),
            aiScore: Math.floor(parseFloat(record.score) * 0.9),
            teacherScore: Math.ceil(parseFloat(record.score) * 1.1),
            date: record.defenseTime.split(' ')[0],
            defenseTime: record.defenseTime,
            feedback: `AI评分：${Math.floor(parseFloat(record.score) * 0.9)}分。学生表现良好。\n\n教师评分：${Math.ceil(parseFloat(record.score) * 1.1)}分。总体表现不错。`
          }));

          let updatedRecords = [];
          if (isRefresh) {
            updatedRecords = newData;
          } else {
            updatedRecords = [...this.data.searchDefenseRecords, ...newData];
          }
          
          this.setData({
            searchDefenseRecords: updatedRecords,
            defenseRecordsTotal: response.total,
            defenseRecordsCurrentPage: isRefresh ? 1 : response.pageNum,
            defenseRecordsHasMore: response.pageNum < response.pages,
            defenseRecordsLoading: false,
            defenseRecordsRefreshing: false
          });
          
          if (this.data.activeTab === 'records' && this.data.isSearching) {
            this.setData({
              defenseRecords: updatedRecords
            });
          }
        } else {
          console.log('搜索失败 - 后端返回错误:', res.data);
          wx.showToast({
            title: res.data.msg || '搜索失败',
            icon: 'error'
          });
          this.setData({
            defenseRecordsLoading: false,
            defenseRecordsRefreshing: false
          });
        }
      },
      fail: (error) => {
        console.error('搜索答辩记录网络请求失败:', error);
        console.error('错误详情 - statusCode:', error.statusCode);
        console.error('错误详情 - errMsg:', error.errMsg);
        wx.showToast({
          title: '网络错误或服务器无响应',
          icon: 'error'
        });
        this.setData({
          defenseRecordsLoading: false,
          defenseRecordsRefreshing: false
        });
      }
    });
  },

  // 加载主页面答辩记录（全部数据）
  loadHomeDefenseRecords(isRefresh = false, specificPageNum = null) {
    if (!isRefresh && (this.data.defenseRecordsLoading || !this.data.defenseRecordsHasMore)) {
      return;
    }

    this.setData({
      defenseRecordsLoading: !isRefresh,
      defenseRecordsRefreshing: isRefresh
    });

    // 使用全局配置的服务器地址
    const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
    const requestUrl = `${serverUrl}/teacher/defense/records`;
    const token = wx.getStorageSync('token');
    
    const pageNumToUse = isRefresh ? 1 : (specificPageNum || this.data.defenseRecordsCurrentPage);
    
    wx.request({
      url: requestUrl,
      method: 'GET',
      header: {
        'Content-Type': 'application/json',
        'Authorization': token ? 'Bearer ' + token : ''
      },
      data: {
        pageNum: pageNumToUse,
        pageSize: this.data.defenseRecordsPageSize
      },
      success: (res) => {
        console.log('获取答辩记录成功:', res.data);
        
        if (res.data.code === 1) {
          const response = res.data.data;
          const newData = response.list.map(record => ({
            id: record.defenseRecordId.toString(),
            studentName: record.userName,
            studentId: record.userNumber,
            topic: record.topicName,
            score: parseFloat(record.score),
            aiScore: Math.floor(parseFloat(record.score) * 0.9),
            teacherScore: Math.ceil(parseFloat(record.score) * 1.1),
            date: record.defenseTime.split(' ')[0],
            defenseTime: record.defenseTime,
            feedback: `AI评分：${Math.floor(parseFloat(record.score) * 0.9)}分。学生表现良好。\n\n教师评分：${Math.ceil(parseFloat(record.score) * 1.1)}分。总体表现不错。`
          }));

          let updatedRecords = [];
          if (isRefresh) {
            updatedRecords = newData;
          } else {
            updatedRecords = [...this.data.homeDefenseRecords, ...newData];
          }
          
          // 更新主页面专用数据源
          this.setData({
            homeDefenseRecords: updatedRecords,
            defenseRecordsTotal: response.total,
            defenseRecordsCurrentPage: isRefresh ? 1 : response.pageNum,
            defenseRecordsHasMore: response.pageNum < response.pages,
            defenseRecordsLoading: false,
            defenseRecordsRefreshing: false
          });
          
          // 如果当前在主页面，同步到通用数据源
          if (this.data.activeTab === 'home') {
            this.setData({
              defenseRecords: updatedRecords
            });
          }
          
          // 更新首页预览数据
          this.setData({
            previewRecords: updatedRecords.slice(0, 3)
          });
        } else {
          wx.showToast({
            title: res.data.msg || '获取答辩记录失败',
            icon: 'error'
          });
          this.setData({
            defenseRecordsLoading: false,
            defenseRecordsRefreshing: false
          });
        }
      },
      fail: (error) => {
        console.error('获取答辩记录失败:', error);
        wx.showToast({
          title: '网络错误或服务器无响应',
          icon: 'error'
        });
        this.setData({
          defenseRecordsLoading: false,
          defenseRecordsRefreshing: false
        });
      }
    });
  },

  // 搜索输入框事件处理
  onSearchUserNameInput(e) {
    this.setData({
      searchUserName: e.detail.value
    });
  },

  onSearchUserNumberInput(e) {
    this.setData({
      searchUserNumber: e.detail.value
    });
  },

  // 新增题目名称搜索输入处理
  onSearchTopicNameInput(e) {
    this.setData({
      searchTopicName: e.detail.value
    });
  },

  // 清空搜索条件
  clearSearch() {
    this.setData({
      searchUserName: '',
      searchUserNumber: '',
      searchTopicName: '', // 清空题目名称搜索条件
      isSearching: false,
      // 只清空搜索相关的数据，保留主页面数据
      searchDefenseRecords: []
    });
    
    // 如果当前在records页面，显示全部数据（使用已有的homeDefenseRecords）
    if (this.data.activeTab === 'records') {
      this.setData({
        defenseRecords: this.data.homeDefenseRecords
      });
    }
    // 如果当前在主页面，不需要额外操作，因为主页面本来就显示homeDefenseRecords
  },

  // 执行搜索
  performSearch() {
    const { searchUserName, searchUserNumber, searchTopicName } = this.data;
    
    console.log('执行搜索 - 用户名:', searchUserName, '学号:', searchUserNumber, '题目名称:', searchTopicName);
    
    // 如果所有搜索条件都为空，则清空搜索
    if (!searchUserName.trim() && !searchUserNumber.trim() && !searchTopicName.trim()) {
      console.log('搜索条件为空，执行清空搜索');
      this.clearSearch();
      return;
    }
    
    this.setData({
      isSearching: true,
      searchDefenseRecords: [],
      defenseRecordsCurrentPage: 1,
      defenseRecordsHasMore: true
    });
    
    console.log('开始执行搜索请求');
    // 执行搜索请求
    this.loadDefenseRecordsBySearch();
  },

  // 下拉刷新答辩记录
  onDefenseRecordsRefresh() {
    if (this.data.activeTab === 'records' && this.data.isSearching) {
      this.loadDefenseRecordsBySearch(true);
    } else if (this.data.activeTab === 'records') {
      this.loadHomeDefenseRecords(true);
    } else if (this.data.activeTab === 'home') {
      this.loadHomeDefenseRecords(true);
    }
  },

  // 监听答辩记录scroll-view滚动到底部
  onDefenseRecordsScrollToLower() {
    if (this.data.defenseRecordsHasMore) {
      const nextPage = this.data.defenseRecordsCurrentPage + 1;
      if (this.data.activeTab === 'records' && this.data.isSearching) {
        this.loadDefenseRecordsBySearch(false, nextPage);
      } else {
        this.loadHomeDefenseRecords(false, nextPage);
      }
    } else {
      wx.showToast({ title: '没有更多答辩记录了', icon: 'none' });
    }
  },

  // 修改重置方法，同时重置搜索状态
  resetDefenseRecords() {
    this.setData({
      defenseRecords: [],
      homeDefenseRecords: [],
      searchDefenseRecords: [],
      defenseRecordsCurrentPage: 1,
      defenseRecordsHasMore: true,
      isSearching: false,
      searchUserName: '',
      searchUserNumber: ''
    });
  },

  // 刷新答辩记录
  refreshDefenseRecords() {
    this.resetDefenseRecords();
    this.loadHomeDefenseRecords(false);
  },

  // 监听 scroll-view 滚动到底部
onScrollToLower() {
  // 这里是话题列表的滚动加载
  if (this.data.hasMore) {
    const nextPage = this.data.currentPage + 1;
    this.setData({ currentPage: nextPage });
    this.loadTopics();
  } else {
    wx.showToast({ title: '没有更多题目了', icon: 'none' });
  }
},

  // 只有题目列表滚动到底才会触发加载
loadMoreTopics() {
  if (this.data.hasMore) {
    const nextPage = this.data.currentPage + 1;
    this.setData({ currentPage: nextPage });
    this.loadTopics();
  } else {
    wx.showToast({
      title: '没有更多题目了',
      icon: 'none'
    });
  }
},

  onShow() {
    console.log('老师页面onShow执行');
    this.loadUserInfo();
  },

  loadUserInfo() {
    // 从本地存储获取登录时保存的用户信息
    const token = wx.getStorageSync('token') || '';
    const userInfo = wx.getStorageSync('userInfo') || {};

    console.log('=== 老师用户信息加载 ===');
    console.log('从storage获取的token:', token);
    console.log('从storage获取的userInfo:', userInfo);
    console.log('当前data中的user:', this.data.user);

    // 使用后端返回的实际数据
    const name = userInfo.name || this.data.user.name;
    const id = userInfo.id || userInfo.userNumber || this.data.user.id || '未填写';  // 优先使用id字段
    const userNumber = userInfo.userNumber || this.data.user.userNumber || '未填写';  // 保留userNumber字段
    const phone = userInfo.phoneNumber || this.data.user.phone || '未绑定';
    const email = userInfo.email || this.data.user.email || '未绑定';

    console.log('解析后的老师用户信息:');
    console.log('- 姓名:', name);
    console.log('- ID:', id);
    console.log('- 工号:', userNumber);
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
    console.log('=== 老师用户信息加载完成 ===');
  },

  // 加载答辩题目数据
  loadTopics() {
    if (this.data.loading || !this.data.hasMore) {
      return; // 如果正在加载或没有更多数据，则不执行
    }

    this.setData({
      loading: true
    });

    // 使用全局配置的服务器地址
    const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
    const requestUrl = `${serverUrl}/teacher/getAllDefense`;
    wx.request({
      url: requestUrl,
      method: 'GET',
      header: {
        'Content-Type': 'application/json',
        'Authorization': this.data.token ? 'Bearer ' + this.data.token : '' // 如果需要身份验证
      },
      data: {
        pageNum: this.data.currentPage,
        pageSize: this.data.pageSize
      },
      success: (res) => {
        console.log('获取答辩题目成功:', res.data);
        
        if (res.data.code === 1) {
          const response = res.data.data;
          const newData = response.list.map(topic => ({
            id: topic.topicId,
            title: topic.topicName,
            description: topic.topicDescription,
            date: topic.defenseTime,
            createTime: topic.createdAt
          }));

          // 更新话题列表
          const updatedTopics = [...this.data.topics, ...newData];
          
          this.setData({
            topics: updatedTopics,
            total: response.total, // 更新总数
            currentPage: response.pageNum,
            hasMore: response.pageNum * response.pageSize < response.total,
            loading: false
          });
        } else {
          wx.showToast({
            title: res.data.msg || '获取数据失败',
            icon: 'error'
          });
          this.setData({
            loading: false
          });
        }
      },
      fail: (error) => {
        console.error('获取答辩题目失败:', error);
        wx.showToast({
          title: '网络错误或服务器无响应',
          icon: 'error'
        });
        this.setData({
          loading: false
        });
      }
    });
  },

  // 上拉触底事件处理
  onReachBottom() {
    if (this.data.hasMore) {
      // 当还有更多数据时，加载下一页
      const nextPage = this.data.currentPage + 1;
      this.setData({
        currentPage: nextPage
      });
      this.loadTopics();
    } else {
      wx.showToast({
        title: '没有更多数据了',
        icon: 'none'
      });
    }
  },

  // 下拉刷新事件处理
  onPullDownRefresh() {
    // 重置分页参数
    this.setData({
      topics: [],
      currentPage: 1,
      hasMore: true
    });
    
    // 同时刷新答辩记录
    this.resetDefenseRecords();
    
    // 并行加载话题和答辩记录
    this.loadTopics();
    this.loadHomeDefenseRecords();
    
    // 停止下拉刷新动画
    wx.stopPullDownRefresh();
  },

  switchTab(e) {
    const tab = e.currentTarget.dataset.tab;
    if (tab) {
      this.setData({ activeTab: tab });
      
      // 根据切换的tab设置对应的数据显示
      if (tab === 'home') {
        this.setData({
          defenseRecords: this.data.homeDefenseRecords
        });
      } else if (tab === 'records') {
        if (this.data.isSearching) {
          this.setData({
            defenseRecords: this.data.searchDefenseRecords
          });
        } else {
          this.setData({
            defenseRecords: this.data.homeDefenseRecords
          });
        }
      }
    }
  },

  openRecord(e) {
    const recordId = e.currentTarget.dataset.id;
    const record = this.data.studentRecords.find((item) => item.id === recordId);
    this.setData({ selectedRecord: record || null });
  },

  closeRecord() {
    this.setData({ selectedRecord: null });
  },

  // 打开题目详情
  openTopicDetail(e) {
    const topicId = e.currentTarget.dataset.id;
    const topic = this.data.topics.find((item) => item.id === topicId);
    if (topic) {
      // 调用API获取该主题下的问题列表
      const token = wx.getStorageSync('token');
      // 使用全局配置的服务器地址
      const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
      wx.request({
        url: `${serverUrl}/teacher/getDefenseQuestionById?topicId=${topicId}`, // 将topicId作为查询参数
        method: 'GET',
        header: {
          'Content-Type': 'application/json',
          'Authorization': token ? 'Bearer ' + token : ''
        },
        success: (res) => {
          console.log('获取题目问题成功:', res.data);
          if (res.data.code === 1) {
            // 将问题数据合并到topic对象中
            const topicWithQuestions = {
              ...topic,
              questions: res.data.data || []
            };
            this.setData({ selectedTopic: topicWithQuestions });
          } else {
            // 即使获取问题失败，也显示基本的题目信息
            this.setData({ selectedTopic: topic });
            console.error('获取题目问题失败:', res.data.msg);
          }
        },
        fail: (error) => {
          console.error('获取题目问题失败:', error);
          // 获取问题失败时，仍显示基本的题目信息
          this.setData({ selectedTopic: topic });
        }
      });
    } else {
      this.setData({ selectedTopic: null });
    }
  },

  // 关闭题目详情
  closeTopicDetail() {
    this.setData({ selectedTopic: null });
  },

  // 查看反馈详情
  openFeedbackDetail(e) {
    const recordId = e.currentTarget.dataset.id;
    let record = null;
    
    // 根据当前状态从正确的数据源查找记录
    if (this.data.activeTab === 'records' && this.data.isSearching) {
      record = this.data.searchDefenseRecords.find((item) => item.id === recordId);
    } else {
      // 优先从主页面数据源查找，如果找不到再从通用数据源查找
      record = this.data.homeDefenseRecords.find((item) => item.id === recordId) || 
               this.data.defenseRecords.find((item) => item.id === recordId);
    }
    
    if (record) {
      // 显示加载提示
      wx.showLoading({
        title: '加载详情中...'
      });
      
      // 调用API获取详细的反馈信息
      const token = wx.getStorageSync('token');
      // 使用全局配置的服务器地址
      const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
      wx.request({
        url: `${serverUrl}/teacher/defense/DetailRecords/${recordId}`,
        method: 'GET',
        header: {
          'Content-Type': 'application/json',
          'Authorization': token ? 'Bearer ' + token : ''
        },
        success: (res) => {
          console.log('获取反馈详情成功:', res.data);
          if (res.data.code === 1) {
            const detailData = res.data.data;
            // 构建完整的反馈详情对象，确保包含topicName字段以匹配WXML期望的字段名
            const feedbackDetail = {
              id: detailData.defenseId.toString(),
              studentName: detailData.studentName.trim(),
              studentNumber: detailData.studentNumber,
              score: detailData.score,
              aiScore: detailData.aiScore || Math.floor(parseFloat(detailData.score) * 0.9), // 优先使用后端返回的AI评分，如果没有则计算
              defenseTime: detailData.defenseTime,
              defenseVideoUrl: detailData.defenseVideoUrl,
              defenseReportUrl: detailData.defenseReportUrl,
              aiVideoAnalysis: detailData.aiVideoAnalysis,
              aiReportAnalysis: detailData.aiReportAnalysis,
              aiAllAnalysis: detailData.aiAllAnalysis,
              topicName: detailData.topicName, // 确保有topicName字段用于WXML显示
              topic: detailData.topicName // 同时保留topic字段以保持与列表结构一致
            };
            this.setData({ 
              selectedRecord: feedbackDetail 
            });
          } else {
            wx.showToast({
              title: res.data.msg || '获取详情失败',
              icon: 'error'
            });
          }
        },
        fail: (error) => {
          console.error('获取反馈详情失败:', error);
          wx.showToast({
            title: '网络错误或服务器无响应',
            icon: 'error'
          });
        },
        complete: () => {
          wx.hideLoading();
        }
      });
    }
  },

  closeRecord() {
    this.setData({ selectedRecord: null });
  },

  // 查看回答详情
  openAnswerDetail(e) {
    const defenseId = e.currentTarget.dataset.id;
    
    // 显示加载提示
    wx.showLoading({
      title: '加载回答详情中...'
    });
    
    // 调用API获取回答详情
    const token = wx.getStorageSync('token');
    // 使用全局配置的服务器地址
    const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
    wx.request({
      url: `${serverUrl}/teacher/defense/questions/${defenseId}`,
      method: 'GET',
      header: {
        'Content-Type': 'application/json',
        'Authorization': token ? 'Bearer ' + token : ''
      },
      success: (res) => {
        console.log('获取回答详情成功:', res.data);
        if (res.data.code === 1) {
          const answers = res.data.data;
          // 处理数据，添加必要的字段用于显示
          const processedAnswers = answers.map(answer => ({
            ...answer,
            questionTypeLabel: answer.questionType === 'ai' ? 'AI问题' : '教师问题',
            questionTypeClass: answer.questionType === 'ai' ? 'ai-question' : 'teacher-question'
          }));
          this.setData({ 
            selectedAnswers: processedAnswers 
          });
        } else {
          wx.showToast({
            title: res.data.msg || '获取回答详情失败',
            icon: 'error'
          });
        }
      },
      fail: (error) => {
        console.error('获取回答详情失败:', error);
        wx.showToast({
          title: '网络错误或服务器无响应',
          icon: 'error'
        });
      },
      complete: () => {
        wx.hideLoading();
      }
    });
  },

  closeAnswers() {
    this.setData({ selectedAnswers: null });
  },

  // 查看答辩报告文档
  viewDefenseReport(e) {
    const reportUrl = e.currentTarget.dataset.url;
    if (reportUrl && reportUrl !== 'abc') {
      // 清理URL中的空白字符
      const cleanUrl = reportUrl.trim();
      
      // 检查URL是否有效
      if (!cleanUrl || cleanUrl === 'abc') {
        wx.showToast({
          title: '文档地址无效',
          icon: 'error'
        });
        return;
      }
      
      // 显示加载提示
      wx.showLoading({
        title: '正在加载文档...'
      });
      
      // 下载文件
      wx.downloadFile({
        url: cleanUrl,
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
            
            // 打开文档
            wx.openDocument({
              filePath: filePath,
              fileType: fileType,
              success: (openRes) => {
                console.log('文档打开成功', openRes);
              },
              fail: (openErr) => {
                console.error('文档打开失败', openErr);
                wx.showToast({
                  title: '文档打开失败，请重试',
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
              title: '文档下载失败',
              icon: 'error'
            });
          }
        },
        fail: (downloadErr) => {
          console.error('文档下载失败:', downloadErr);
          wx.hideLoading();
          wx.showToast({
            title: '文档加载失败，请检查网络或联系管理员',
            icon: 'error'
          });
        }
      });
    } else {
      wx.showToast({
        title: '文档地址无效',
        icon: 'error'
      });
    }
  },

  openAddTopic() {
    this.setData({
      showAddTopic: true,
      isEditingTopic: false,  // 确保重置为非编辑状态
      newTopic: {
        id: null,
        title: '',
        description: '',
        date: '',
        questions: [{ question: '', answer: '', questionType: 'teacher' }]  // 初始化一个空问题
      }
    });
  },

  closeAddTopic() {
    this.setData({ showAddTopic: false });
  },

  onTopicTitleInput(e) {
    this.setData({ 'newTopic.title': e.detail.value });
  },

  onTopicDescInput(e) {
    this.setData({ 'newTopic.description': e.detail.value });
  },

  onTopicDateChange(e) {
    const date = e.detail.value;
    this.setData({
      'newTopic.date': date
    });
  },

  onTopicDateInput(e) {
    const date = e.detail.value;
    this.setData({
      'newTopic.date': date
    });
  },

  // 编辑题目
  editTopic() {
    const topic = this.data.selectedTopic;
    if (topic) {
      this.setData({
        newTopic: {
          id: topic.id, // 保存原始ID用于后续更新
          title: topic.title,
          description: topic.description,
          date: topic.date,
          questions: topic.questions && topic.questions.length > 0 
            ? topic.questions.map(q => ({ 
                question: q.question, 
                answer: q.standardAnswer,
                questionType: q.questionType || 'teacher'
              })) 
            : [] // 如果存在之前的问题和答案则使用
        },
        showAddTopic: true, // 打开编辑模式
        selectedTopic: null, // 关闭详情弹窗
        isEditingTopic: true // 设置为编辑模式
      });
    }
  },
  
  // 添加新问题
  addQuestion() {
    const questions = this.data.newTopic.questions || [];
    questions.push({
      question: '',
      answer: ''
    });
    
    this.setData({
      'newTopic.questions': questions
    });
  },
  
  // 移除问题
  removeQuestion(e) {
    const index = e.currentTarget.dataset.index;
    const questions = [...this.data.newTopic.questions];
    questions.splice(index, 1);
    
    this.setData({
      'newTopic.questions': questions
    });
  },
  
  // 更新问题内容
  onQuestionInput(e) {
    const index = e.currentTarget.dataset.index;
    const questions = [...this.data.newTopic.questions];
    questions[index].question = e.detail.value;
    
    this.setData({
      'newTopic.questions': questions
    });
  },
  
  // 更新标准答案
  onAnswerInput(e) {
    const index = e.currentTarget.dataset.index;
    const questions = [...this.data.newTopic.questions];
    questions[index].answer = e.detail.value;
    
    this.setData({
      'newTopic.questions': questions
    });
  },

  addTopic() {
    const { id, title, description, date, questions } = this.data.newTopic;
    
    // 验证必填项
    if (!title || !title.trim()) {
      wx.showToast({
        title: '请输入题目标题',
        icon: 'none'
      });
      return;
    }
    
    if (!description || !description.trim()) {
      wx.showToast({
        title: '请输入题目描述',
        icon: 'none'
      });
      return;
    }
    
    if (!date) {
      wx.showToast({
        title: '请选择答辩日期',
        icon: 'none'
      });
      return;
    }
    
    // 验证每个问题和答案都不为空（如果有添加问题的话）
    if (questions && questions.length > 0) {
      for (let i = 0; i < questions.length; i++) {
        const q = questions[i];
        if (!q.question || !q.question.trim()) {
          wx.showToast({
            title: `第${i+1}个问题不能为空`,
            icon: 'none'
          });
          return;
        }
        if (!q.answer || !q.answer.trim()) {
          wx.showToast({
            title: `第${i+1}个标准答案不能为空`,
            icon: 'none'
          });
          return;
        }
      }
    }
    
    // 显示加载提示
    wx.showLoading({ 
      title: this.data.isEditingTopic ? '更新中...' : '添加中...' 
    });

    // 构建要发送到后端的数据
    const requestData = {
      topicId: id, // 使用题目ID来区分编辑和新增操作
      topicName: title.trim(),
      topicDescription: description.trim(),
      defenseTime: date,
      teacherId: 20, // 使用固定的teacherId 20，与后端测试数据一致
      questions: questions && questions.length > 0 ? questions.map(q => ({
        questionType: q.questionType || 'teacher', // 默认为teacher类型
        question: q.question.trim(),
        standardAnswer: q.answer.trim()
      })) : []  // 如果没有问题则传递空数组
    };

    // 发送请求到后端API - 根据是否为编辑模式选择不同的接口
    const token = wx.getStorageSync('token');
    // 使用全局配置的服务器地址
    const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
    const url = this.data.isEditingTopic ? `${serverUrl}/teacher/editDefense` : `${serverUrl}/teacher/addDefense`;
    const method = 'POST';

    wx.request({
      url: url,
      method: method,
      header: {
        'Content-Type': 'application/json',
        'Authorization': token ? 'Bearer ' + token : ''
      },
      data: requestData,
      success: (res) => {
        console.log(this.data.isEditingTopic ? '更新答辩题目成功:' : '添加答辩题目成功:', res.data);
        
        if (res.data.code === 1) {
          wx.showToast({
            title: this.data.isEditingTopic ? '更新成功' : '添加成功',
            icon: 'success'
          });

          // 无论成功与否都刷新数据列表
          this.setData({
            topics: [],
            currentPage: 1,
            hasMore: true
          });
          this.loadTopics();
          
          // 关闭弹窗
          this.closeAddTopic();
        } else {
          wx.showToast({
            title: res.data.msg || (this.data.isEditingTopic ? '更新失败' : '添加失败'),
            icon: 'error'
          });
        }
      },
      fail: (error) => {
        console.error(this.data.isEditingTopic ? '更新答辩题目失败:' : '添加答辩题目失败:', error);
        wx.showToast({
          title: '网络错误或服务器无响应',
          icon: 'error'
        });
      },
      complete: () => {
        wx.hideLoading();
      }
    });
  },

  // 删除题目
  deleteTopic() {
    const topicId = this.data.selectedTopic.id;
    wx.showModal({
      title: '确认删除',
      content: '您确定要删除这个答辩题目吗？此操作不可撤销！',
      success: (res) => {
        if (res.confirm) {
          // 发送删除请求到后端
          // 使用全局配置的服务器地址
          const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
          wx.request({
            url: `${serverUrl}/teacher/deleteDefenseTopics?topicId=${topicId}`, // 使用正确的删除接口
            method: 'DELETE',
            header: {
              'Content-Type': 'application/json',
              'Authorization': wx.getStorageSync('token') ? 'Bearer ' + wx.getStorageSync('token') : ''
            },
            success: (res) => {
              if (res.data.code === 1) {
                wx.showToast({
                  title: '删除成功',
                  icon: 'success'
                });

                // 重置分页参数并重新加载全部数据，确保列表同步最新状态
                this.setData({
                  topics: [],
                  currentPage: 1,
                  hasMore: true,
                  selectedTopic: null // 同时关闭详情弹窗
                });
                
                // 重新加载题目列表
                this.loadTopics();
              } else {
                wx.showToast({
                  title: res.data.msg || '删除失败',
                  icon: 'error'
                });
              }
            },
            fail: (error) => {
              console.error('删除答辩题目失败:', error);
              wx.showToast({
                title: '网络错误或服务器无响应',
                icon: 'error'
              });
            }
          });
        }
      }
    });
  },

  startEdit() {
    this.setData({
      isEditing: true,
      editedUser: { ...this.data.user }
    });
  },

  saveProfile() {
    const that = this;
    
    // 显示加载提示
    wx.showLoading({
      title: '保存中...'
    });

    // 构造请求参数（与学生端相同的格式）
    const requestData = {
      id: this.data.user.id,
      name: this.data.editedUser.name,
      userNumber: this.data.editedUser.userNumber,
      phoneNumber: this.data.editedUser.phone,
      email: this.data.editedUser.email
    };

    console.log('发送到后端的数据:', requestData);

    // 发送请求到后端（与学生端相同的接口）
    // 使用全局配置的服务器地址
    const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
    wx.request({
      url: `${serverUrl}/editUserInfo`,
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
            id: that.data.user.id,  // ID通常不允许修改
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

  onEditName(e) {
    this.setData({ 'editedUser.name': e.detail.value });
  },

  onEditUserNumber(e) {
    this.setData({ 'editedUser.userNumber': e.detail.value });
  },

  onEditPhone(e) {
    this.setData({ 'editedUser.phone': e.detail.value });
  },

  onEditEmail(e) {
    this.setData({ 'editedUser.email': e.detail.value });
  },

  logout() {
    wx.removeStorageSync('token');
    wx.removeStorageSync('userInfo');
    wx.removeStorageSync('user');
    wx.reLaunch({
      url: '/pages/login/login'
    });
    wx.showToast({ title: '已退出登录', icon: 'none' });

  },

  // 查看回答详情
  openAnswerDetail(e) {
    const defenseId = e.currentTarget.dataset.id;
    
    // 显示加载提示
    wx.showLoading({
      title: '加载回答详情中...'
    });
    
    // 调用API获取回答详情
    const token = wx.getStorageSync('token');
    // 使用全局配置的服务器地址
    const serverUrl = config.useRemoteServer ? config.serverUrl : config.localServerUrl;
    wx.request({
      url: `${serverUrl}/teacher/defense/questions/${defenseId}`,
      method: 'GET',
      header: {
        'Content-Type': 'application/json',
        'Authorization': token ? 'Bearer ' + token : ''
      },
      success: (res) => {
        console.log('获取回答详情成功:', res.data);
        if (res.data.code === 1) {
          const answers = res.data.data;
          // 处理数据，添加必要的字段用于显示
          const processedAnswers = answers.map(answer => ({
            ...answer,
            questionTypeLabel: answer.questionType === 'ai' ? 'AI问题' : '教师问题',
            questionTypeClass: answer.questionType === 'ai' ? 'ai-question' : 'teacher-question'
          }));
          this.setData({ 
            selectedAnswers: processedAnswers 
          });
        } else {
          wx.showToast({
            title: res.data.msg || '获取回答详情失败',
            icon: 'error'
          });
        }
      },
      fail: (error) => {
        console.error('获取回答详情失败:', error);
        wx.showToast({
          title: '网络错误或服务器无响应',
          icon: 'error'
        });
      },
      complete: () => {
        wx.hideLoading();
      }
    });
  },

  closeAnswers() {
    this.setData({ selectedAnswers: null });
  }
});