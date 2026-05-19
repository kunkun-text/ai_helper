const config = require('../../utils/config.js');

Page({
  data: {
    // 页面信息
    topicId: null,
    topicName: '',
    userId: '',
    sessionId: '',

    // 状态
    status: 'ongoing', // ongoing | finished | error
    statusText: '答辩进行中',
    isAiThinking: false,
    isFinished: false,
    showFinishModal: false,

    // 消息
    messages: [],
    scrollToId: '',

    // 输入
    inputText: '',

    // 当前AI消息（用于完成弹窗）
    lastAiMessage: null,

    // 问题计数
    questionCount: 0,

    // 重试
    lastError: '',
  },

  onLoad(options) {
    const { topicId, topicName, userId, sessionId } = options;
    const token = wx.getStorageSync('token') || '';

    this.setData({
      topicId: parseInt(topicId),
      topicName: topicName || '答辩考试',
      userId: userId || '',
      sessionId: sessionId || `user_${userId}_topic_${topicId}`,
    });

    // 添加系统消息
    this.addSystemMessage('答辩考试开始，AI考官已就位，请做好准备。');

    // 调用AI开始答辩（空prompt触发第一个问题）
    this.callAi('');
  },

  onUnload() {
    // 页面退出时无需清理，session保留在Redis中
  },

  // ======== 消息管理 ========

  addSystemMessage(text) {
    const msg = {
      id: Date.now(),
      type: 'system',
      text,
    };
    this.setData({
      messages: [...this.data.messages, msg],
      scrollToId: 'scroll-bottom',
    });
  },

  addUserMessage(text) {
    const msg = {
      id: Date.now(),
      type: 'user',
      text,
    };
    this.setData({
      messages: [...this.data.messages, msg],
      scrollToId: 'scroll-bottom',
    });
  },

  addAiMessage(responseText, parsed) {
    const msg = {
      id: Date.now(),
      type: 'ai',
      text: responseText,
      displayText: parsed.displayText || '',
      evaluation: parsed.evaluation || '',
      score: parsed.score,
      question: parsed.question || '',
      summary: parsed.summary || '',
      videoAnalysis: parsed.videoAnalysis || '',
      reportAnalysis: parsed.reportAnalysis || '',
      totalScore: parsed.totalScore,
      isSummary: !!parsed.summary,
    };

    const messages = [...this.data.messages, msg];
    this.setData({
      messages,
      lastAiMessage: msg,
      scrollToId: 'scroll-bottom',
    });

    // 如果AI回复包含总结，标记答辩完成
    if (msg.isSummary || msg.totalScore !== null) {
      this.finishDefense();
    }

    return msg;
  },

  // ======== AI 通信 ========

  callAi(prompt) {
    this.setData({ isAiThinking: true, lastError: '' });

    wx.request({
      url: config.serverUrl + '/api/chat',
      method: 'POST',
      data: {
        prompt: prompt,
        topicId: this.data.topicId,
        sessionId: this.data.sessionId,
        userId: this.data.userId,
      },
      header: {
        'Authorization': 'Bearer ' + (wx.getStorageSync('token') || ''),
        'content-type': 'application/json',
      },
      responseType: 'text',
      success: (res) => {
        if (res.statusCode === 200) {
          const responseText = res.data || '';
          if (responseText.trim()) {
            const parsed = this.parseAiResponse(responseText);
            this.addAiMessage(responseText, parsed);
          } else {
            this.setData({
              status: 'error',
              statusText: 'AI回复异常',
              lastError: 'AI回复为空，请重试',
            });
          }
        } else {
          this.setData({
            status: 'error',
            statusText: '网络异常',
            lastError: `请求失败（${res.statusCode}）`,
          });
        }
      },
      fail: (err) => {
        console.error('AI请求失败:', err);
        this.setData({
          status: 'error',
          statusText: '网络异常',
          lastError: '网络请求失败，请检查连接',
        });
      },
      complete: () => {
        this.setData({ isAiThinking: false });
      },
    });
  },

  // ======== AI 回复解析 ========

  parseAiResponse(text) {
    const result = {
      displayText: '',
      evaluation: '',
      score: null,
      question: '',
      summary: '',
      videoAnalysis: '',
      reportAnalysis: '',
      totalScore: null,
    };

    // 提取各标记内容
    result.evaluation = this.extractMarkedText(text, '【评价】', ['【得分】', '【问题】', '【总结】', '【视频分析】', '【报告分析】', '【总得分】']);
    result.score = this.extractScore(text);
    result.question = this.extractMarkedText(text, '【问题】', ['【总结】', '【视频分析】', '【报告分析】', '【总得分】']);
    result.summary = this.extractMarkedText(text, '【总结】', ['【视频分析】', '【报告分析】', '【总得分】']);
    result.videoAnalysis = this.extractMarkedText(text, '【视频分析】', ['【报告分析】', '【总得分】']);
    result.reportAnalysis = this.extractMarkedText(text, '【报告分析】', ['【总得分】']);
    result.totalScore = this.extractTotalScore(text);

    // 构建显示文本（去掉已解析的标记部分，保留纯文本）
    let displayText = text;
    const markers = ['【评价】', '【得分】', '【问题】', '【总结】', '【视频分析】', '【报告分析】', '【总得分】'];
    // 找到第一个标记，之前的文本就是显示文本
    let firstMarkerIndex = -1;
    for (const marker of markers) {
      const idx = text.indexOf(marker);
      if (idx !== -1 && (firstMarkerIndex === -1 || idx < firstMarkerIndex)) {
        firstMarkerIndex = idx;
      }
    }
    if (firstMarkerIndex > 0) {
      displayText = text.substring(0, firstMarkerIndex).trim();
    } else if (firstMarkerIndex === -1) {
      displayText = text;
    } else {
      displayText = '';
    }

    result.displayText = displayText;

    // 增加问题计数
    if (result.question) {
      this.setData({ questionCount: this.data.questionCount + 1 });
    }

    return result;
  },

  extractMarkedText(text, marker, endMarkers) {
    const startIdx = text.indexOf(marker);
    if (startIdx === -1) return '';

    const contentStart = startIdx + marker.length;
    let contentEnd = text.length;

    for (const endMarker of endMarkers) {
      const idx = text.indexOf(endMarker, contentStart);
      if (idx !== -1 && idx < contentEnd) {
        contentEnd = idx;
      }
    }

    return text.substring(contentStart, contentEnd).trim();
  },

  extractScore(text) {
    // 先找【得分】标记
    const scoreSection = this.extractMarkedText(text, '【得分】', ['【问题】', '【评价】', '【总结】', '【视频分析】', '【报告分析】', '【总得分】']);
    if (scoreSection) {
      const match = scoreSection.match(/(\d+(?:\.\d+)?)/);
      if (match) return parseFloat(match[1]);
    }

    // 正则后备：xx分
    const match = text.match(/(\d+(?:\.\d+)?)\s*分(?!.*【总得分】)/);
    if (match) return parseFloat(match[1]);

    return null;
  },

  extractTotalScore(text) {
    const section = this.extractMarkedText(text, '【总得分】', []);
    if (section) {
      const match = section.match(/(\d+(?:\.\d+)?)/);
      if (match) return parseFloat(match[1]);
    }
    return null;
  },

  // ======== 用户操作 ========

  onInput(e) {
    this.setData({ inputText: e.detail.value });
  },

  sendMessage() {
    const text = this.data.inputText.trim();
    if (!text || this.data.isAiThinking || this.data.isFinished) return;

    // 添加用户消息到列表
    this.addUserMessage(text);
    this.setData({ inputText: '' });

    // 调用AI
    this.callAi(text);
  },

  finishDefense() {
    this.setData({
      isFinished: true,
      status: 'finished',
      statusText: '答辩已完成',
      showFinishModal: true,
    });
  },

  // ======== 导航 ========

  goBack() {
    wx.navigateBack({
      delta: 1,
      fail: () => {
        wx.redirectTo({
          url: '/pages/student/student',
        });
      },
    });
  },

  closeFinishModal() {
    this.setData({ showFinishModal: false });
  },

  onScrollToTop() {
    // scroll to top handler if needed
  },
});
