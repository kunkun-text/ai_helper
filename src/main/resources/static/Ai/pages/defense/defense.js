const config = require('../../utils/config.js');

Page({
  data: {
    topicId: null,
    topicName: '',
    userId: '',
    sessionId: '',

    status: 'ongoing',
    statusText: '答辩进行中',
    isAiThinking: false,
    isFinished: false,
    showFinishModal: false,

    messages: [],
    scrollToId: '',

    inputText: '',

    lastAiMessage: null,
    questionCount: 0,
    presetRounds: 5, // 预设题数量（十轮答辩 = 5 预设题 + 5 追问）
    totalRounds: 10, // 总轮次，顶栏进度显示用
    lastError: '',
    showRetry: false,

    // 等待计时器
    waitSeconds: 0,
    showWaitTimer: false,
    _lastPrompt: '', // 缓存最后一次请求的prompt用于重试
  },

  onLoad(options) {
    const { topicId, topicName, userId, sessionId } = options;
    const sid = sessionId || `user_${userId}_topic_${topicId}`;
    this.setData({
      topicId: parseInt(topicId),
      topicName: topicName || '答辩考试',
      userId: userId || '',
      sessionId: sid,
    });

    // 进入答辩页时清理 Redis 旧会话，从头开始
    wx.request({
      url: config.getBaseUrl() + '/api/chat/clear',
      method: 'POST',
      data: { sessionId: sid, topicId: this.data.topicId, userId: this.data.userId },
      header: {
        'Authorization': 'Bearer ' + (wx.getStorageSync('token') || ''),
        'content-type': 'application/json',
      },
      complete: () => {
        this.addSystemMessage('答辩考试开始，AI考官已就位。');
        this.callAi('');
      },
    });
  },

  onUnload() {
    this._stopWaitTimer();
  },

  // ======== 等待计时器 ========

  _startWaitTimer() {
    this._stopWaitTimer();
    this.setData({ waitSeconds: 0, showWaitTimer: true });
    this._waitTimer = setInterval(() => {
      this.setData({ waitSeconds: this.data.waitSeconds + 1 });
    }, 1000);
  },

  _stopWaitTimer() {
    if (this._waitTimer) {
      clearInterval(this._waitTimer);
      this._waitTimer = null;
    }
    this.setData({ showWaitTimer: false, waitSeconds: 0 });
  },

  // ======== 消息管理 ========

  addSystemMessage(text) {
    this.setData({
      messages: [...this.data.messages, { id: Date.now(), type: 'system', text }],
      scrollToId: 'scroll-bottom',
    });
  },

  addUserMessage(text) {
    this.setData({
      messages: [...this.data.messages, { id: Date.now(), type: 'user', text }],
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
      questionLabel: parsed.questionLabel || '',
      summary: parsed.summary || '',
      videoAnalysis: parsed.videoAnalysis || '',
      reportAnalysis: parsed.reportAnalysis || '',
      totalScore: parsed.totalScore,
      isSummary: !!parsed.summary,
    };
    this.setData({
      messages: [...this.data.messages, msg],
      lastAiMessage: msg,
      scrollToId: 'scroll-bottom',
      showRetry: false,
      lastError: '',
    });
    // 只有真正出总结（【总结】）时才结束答辩；每轮常规打分不触发
    if (msg.isSummary) {
      this.finishDefense();
    }
    return msg;
  },

  // ======== AI 通信（180秒无硬杀） ========

  callAi(prompt) {
    this.setData({ isAiThinking: true, lastError: '', showRetry: false });
    this._startWaitTimer();
    this._lastPrompt = prompt; // 缓存用于重试

    wx.request({
      url: config.getBaseUrl() + '/api/chat',
      method: 'POST',
      timeout: 180000, // 3分钟
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
            this.setData({ status: 'error', statusText: 'AI回复异常', lastError: 'AI回复为空，请重试', showRetry: true });
          }
        } else {
          this.setData({ status: 'error', statusText: '网络异常', lastError: `请求失败(${res.statusCode})`, showRetry: true });
        }
      },
      fail: (err) => {
        console.error('AI请求失败:', err);
        const errMsg = err.errMsg || '网络请求失败';
        this.setData({ status: 'error', statusText: '网络异常', lastError: errMsg, showRetry: true });
      },
      complete: () => {
        this.setData({ isAiThinking: false });
        this._stopWaitTimer();
      },
    });
  },

  // ======== 重试 ========

  retryLastMessage() {
    if (this.data.isAiThinking) return;
    this.setData({ showRetry: false, status: 'ongoing', statusText: '答辩进行中' });
    this.callAi(this._lastPrompt || '');
  },

  // ======== AI 回复解析（兼容新旧两种格式） ========

  parseAiResponse(text) {
    const result = {
      displayText: '', evaluation: '', score: null, question: '',
      summary: '', videoAnalysis: '', reportAnalysis: '', totalScore: null,
    };

    // 新三段式：点评:/评分:/下一题:（优先识别，兼容全角冒号）
    if (/点评[:：]/.test(text) || /评分[:：]/.test(text) || /下一题[:：]/.test(text)) {
      return this.parseSegmentFormat(text, result);
    }

    // 再尝试新管道格式：得分/50|表达|逻辑|专业|应变|创新|优点|建议|下个问题
    if (text.includes('|') && !text.includes('【')) {
      return this.parsePipeFormat(text, result);
    }

    // 旧格式兼容
    result.evaluation = this.extractMarkedText(text, '【评价】', ['【得分】', '【问题】', '【总结】', '【视频分析】', '【报告分析】', '【总得分】', '【表达】', '【逻辑】', '【专业】', '【应变】', '【创新】']);
    result.score = this.extractScore(text);
    result.question = this.extractMarkedText(text, '【问题】', ['【总结】', '【视频分析】', '【报告分析】', '【总得分】']);
    result.summary = this.extractMarkedText(text, '【总结】', ['【视频分析】', '【报告分析】', '【总得分】']);
    result.videoAnalysis = this.extractMarkedText(text, '【视频分析】', ['【报告分析】', '【总得分】']);
    result.reportAnalysis = this.extractMarkedText(text, '【报告分析】', ['【总得分】']);
    result.totalScore = this.extractTotalScore(text);

    let displayText = text;
    const markers = ['【评价】', '【得分】', '【问题】', '【总结】', '【视频分析】', '【报告分析】', '【总得分】', '【表达】', '【逻辑】', '【专业】', '【应变】', '【创新】'];
    let firstIdx = -1;
    for (const m of markers) {
      const idx = text.indexOf(m);
      if (idx !== -1 && (firstIdx === -1 || idx < firstIdx)) firstIdx = idx;
    }
    result.displayText = firstIdx > 0 ? text.substring(0, firstIdx).trim() : (firstIdx === -1 ? text : '');

    if (result.question) this.markQuestionNumber(result);
    return result;
  },

  /** 解析三段式：点评:... / 评分:总分/50|表达|逻辑|专业|应变|创新 / 下一题:... */
  parseSegmentFormat(text, result) {
    const lines = text.split('\n');
    let comment = '';
    let hasScore = false;
    const dims = [];
    for (const line of lines) {
      const t = line.trim();
      let m;
      if ((m = t.match(/^点评[:：]\s*(.*)$/))) {
        comment = m[1].trim();
      } else if ((m = t.match(/^评分[:：]\s*\d+\s*[/／]\s*50\s*\|(.*)$/))) {
        hasScore = true;
        const dimParts = m[1].split('|');
        for (let i = 0; i < 5; i++) {
          const v = parseFloat(dimParts[i]);
          if (isNaN(v)) dims.push(0);
          else dims.push(Math.max(0, Math.min(10, Math.round(v))));
        }
      } else if ((m = t.match(/^下一题[:：]\s*(.*)$/)) || (m = t.match(/^下一问[:：]\s*(.*)$/))) {
        result.question = m[1].trim();
      } else if ((m = t.match(/^总结[:：]\s*(.*)$/))) {
        result.summary = m[1].trim();
      } else if (comment && t && !hasScore) {
        // 点评可能跨行，非标签行且尚未出现评分行时拼接进点评
        comment += t;
      }
    }
    while (dims.length < 5) dims.push(0);
    const dimLabels = ['表达', '逻辑', '专业', '应变', '创新'];
    let sum = 0;
    for (let i = 0; i < 5; i++) sum += dims[i];
    // 展示总分一律 = 五维之和，保证"加起来对得上总分"
    result.score = hasScore ? sum : null;
    result.totalScore = result.score;
    // 无评分行的回复（如开场白）不展示五维评价，避免渲染出"表达:0 逻辑:0…"的全 0 区块
    result.evaluation = hasScore ? dims.map((v, i) => `${dimLabels[i]}:${v}`).join(' ') : '';
    // 气泡只显示点评（自然语言），评分/下一题走下方结构化区块，避免重复
    result.displayText = comment || result.evaluation;
    if (result.question) this.markQuestionNumber(result);
    return result;
  },

  /** 解析管道格式：得分/50|表达|逻辑|专业|应变|创新|优点|建议|下个问题 */
  parsePipeFormat(text, result) {
    const line = text.split('\n')[0].trim();
    const parts = line.split('|');
    if (parts.length >= 8) {
      const scorePart = parts[0].split('/');
      if (scorePart.length >= 2) {
        result.score = parseFloat(scorePart[0]);
      }
      result.evaluation = `表达:${parts[1] || '-'} 逻辑:${parts[2] || '-'} 专业:${parts[3] || '-'} 应变:${parts[4] || '-'} 创新:${parts[5] || '-'}`;
      if (parts[6]) result.evaluation += '\n优点：' + parts[6];
      if (parts[7]) result.evaluation += '\n建议：' + parts[7];
      result.question = parts.length >= 9 ? parts.slice(8).join('|') : '';
      result.displayText = result.evaluation;
    } else {
      result.displayText = line;
    }
    if (result.question) this.markQuestionNumber(result);
    return result;
  },

  /** 题目序号标注：每解析出一道题计数 +1。1~presetRounds 显示"第N题"，之后为追问轮显示"追问M" */
  markQuestionNumber(result) {
    const no = this.data.questionCount + 1;
    result.questionNo = no;
    result.questionLabel = no <= this.data.presetRounds ? `第${no}题` : `追问${no - this.data.presetRounds}`;
    this.setData({ questionCount: no });
  },

  extractMarkedText(text, marker, endMarkers) {
    const startIdx = text.indexOf(marker);
    if (startIdx === -1) return '';
    let end = text.length;
    for (const em of endMarkers) {
      const idx = text.indexOf(em, startIdx + marker.length);
      if (idx !== -1 && idx < end) end = idx;
    }
    return text.substring(startIdx + marker.length, end).trim();
  },

  extractScore(text) {
    const s = this.extractMarkedText(text, '【得分】', ['【问题】', '【评价】', '【总结】', '【视频分析】', '【报告分析】', '【总得分】', '【表达】', '【逻辑】', '【专业】', '【应变】', '【创新】']);
    if (s) { const m = s.match(/(\d+(?:\.\d+)?)/); if (m) return parseFloat(m[1]); }
    const m2 = text.match(/(\d+(?:\.\d+)?)\s*分(?!.*【总得分】)/);
    return m2 ? parseFloat(m2[1]) : null;
  },

  extractTotalScore(text) {
    const s = this.extractMarkedText(text, '【总得分】', []);
    if (s) { const m = s.match(/(\d+(?:\.\d+)?)/); if (m) return parseFloat(m[1]); }
    return null;
  },

  // ======== 用户操作 ========

  onInput(e) {
    this.setData({ inputText: e.detail.value });
  },

  // 复制题目文本：PC 工具模拟器不支持鼠标选择文本，user-select 只在真机长按生效，故给显式复制入口
  copyQuestion(e) {
    const text = e.currentTarget.dataset.text;
    if (!text) return;
    wx.setClipboardData({ data: text });
  },

  sendMessage() {
    const text = this.data.inputText.trim();
    if (!text) { wx.showToast({ title: '请输入回答', icon: 'none' }); return; }
    if (this.data.isFinished) { wx.showToast({ title: '答辩已结束', icon: 'none' }); return; }
    if (this.data.isAiThinking) { wx.showToast({ title: 'AI思考中，请稍候', icon: 'none' }); return; }

    this.addUserMessage(text);
    this.setData({ inputText: '' });
    this.callAi(text);
  },

  finishDefense() {
    this.setData({
      isFinished: true, status: 'finished', statusText: '答辩已完成', showFinishModal: true,
    });
  },

  goBack() {
    wx.navigateBack({ delta: 1, fail: () => { wx.redirectTo({ url: '/pages/student/student' }); } });
  },
  closeFinishModal() { this.setData({ showFinishModal: false }); },
  onScrollToTop() {},
});
