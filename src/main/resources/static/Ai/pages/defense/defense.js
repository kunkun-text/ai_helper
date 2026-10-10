const config = require('../../utils/config.js');
const auth = require('../../utils/auth.js');
const protocol = require('../../utils/protocol.js');

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

    // 【2026-09-28 回归修复】答辩开端默认从第 1 题开始：
    // 先只读探测「有没有未完成的答辩」，有则弹窗让用户自己选，只有明确选「继续作答」才续答。
    // 上一版由后端自动续答，结果历史遗留的脏 pending 记录（例如已答满 10 轮却没写回收尾状态）
    // 会把学生一进答辩页就直接带到追问阶段。
    wx.request({
      url: config.getBaseUrl() + '/api/chat/resume-info',
      method: 'POST',
      data: { topicId: this.data.topicId, userId: this.data.userId },
      header: {
        'Authorization': 'Bearer ' + auth.getToken(),
        'content-type': 'application/json',
      },
      success: (res) => {
        // 【C2】401/403 统一处理：登录过期不再表现为"探测失败直接开始新答辩"
        if (res.statusCode === 401 || res.statusCode === 403) {
          auth.handleAuthExpired();
          return;
        }
        const info = res.data || {};
        if (info.resumable) {
          this.askResumeOrRestart(info);
        } else {
          this.enterDefense(false);
        }
      },
      fail: () => this.enterDefense(false),
    });
  },

  /** 探测到未完成答辩 → 询问用户（默认「重新开始」，避免误续历史数据） */
  askResumeOrRestart(info) {
    wx.showModal({
      title: '发现未完成的答辩',
      content: `你有一场答辩答到第 ${info.roundNum} 题（已完成 ${info.answeredCount} 题），要继续上次的作答吗？`,
      confirmText: '继续作答',
      cancelText: '重新开始',
      success: (r) => this.enterDefense(!!r.confirm),
      fail: () => this.enterDefense(false),
    });
  },

  /** 进入答辩：resume=true 续答既有场次；false（默认）从第 1 题开始 */
  enterDefense(resume) {
    wx.request({
      url: config.getBaseUrl() + '/api/chat/clear',
      method: 'POST',
      data: {
        sessionId: this.data.sessionId,
        topicId: this.data.topicId,
        userId: this.data.userId,
        resume: !!resume,
      },
      header: {
        'Authorization': 'Bearer ' + auth.getToken(),
        'content-type': 'application/json',
      },
      success: (res) => {
        // 【C2】401/403 统一处理
        if (res.statusCode === 401 || res.statusCode === 403) {
          auth.handleAuthExpired();
          return;
        }
        const data = res.data || {};
        if (data.resumed) {
          this.resumeDefense(data);
        } else {
          this.beginNewDefense();
        }
      },
      // 接口异常时退回原行为（从第 1 题开始），不阻塞答辩
      fail: () => this.beginNewDefense(),
    });
  },

  /** 全新答辩：与改造前完全一致的进场流程 */
  beginNewDefense() {
    this.addSystemMessage('答辩考试开始，AI考官已就位。');
    this.callAi('');
  },

  /**
   * 继续未完成的答辩（N1）：不调模型，直接展示当前该答的题。
   * 之后提交答案时，后端按已落库的轮次继续评分，不会从第 1 题重来，
   * 也不会再新开一条记录（原记录卡在「未完成」成为孤儿记录的问题一并解决）。
   */
  resumeDefense(data) {
    const answered = data.answeredCount || 0;
    const roundNum = data.roundNum || (answered + 1);
    // 题目序号接着已答轮次，避免重进后序号从 1 重新计数
    this.setData({ questionCount: answered, status: 'ongoing', statusText: '答辩进行中' });
    this.addSystemMessage(`检测到未完成的答辩，已为你继续：前 ${answered} 题已完成，当前第 ${roundNum} 题。`);

    if (data.currentQuestion) {
      // 【F1】协议标签统一走 protocol 常量
      const text = protocol.COMMENT_TAG + '请继续作答。\n'
        + protocol.NEXT_QUESTION_TAG + data.currentQuestion;
      this.addAiMessage(text, this.parseAiResponse(text));
    } else {
      this.addSystemMessage('请继续作答上一轮考官提出的问题。');
    }
  },

  onUnload() {
    this._stopWaitTimer();
    // 【C2/D2】卸载时中断在途 AI 请求，避免离开页面后回调报错
    if (this._chatTask) {
      try { this._chatTask.abort(); } catch (e) { /* 已结束 */ }
      this._chatTask = null;
    }
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
    // 路径式 setData：只下发新增这一条，不再把整个 messages 数组重新序列化传一遍
    const index = this.data.messages.length;
    this.setData({
      [`messages[${index}]`]: { id: Date.now(), type: 'system', text },
      scrollToId: 'scroll-bottom',
    });
  },

  addUserMessage(text) {
    const index = this.data.messages.length;
    this.setData({
      [`messages[${index}]`]: { id: Date.now(), type: 'user', text },
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
    // 与上两处同理：AI 消息体最大（含点评/评分/题目），尤其不该整数组重发
    const index = this.data.messages.length;
    this.setData({
      [`messages[${index}]`]: msg,
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

    this._chatTask = wx.request({
      url: config.getBaseUrl() + '/api/chat',
      method: 'POST',
      timeout: 180000, // 3分钟
      data: {
        prompt: prompt,
        topicId: this.data.topicId,
        sessionId: this.data.sessionId,
        // 【A1】userId 为兼容字段：后端以登录态为准，伪造他人 userId 会被拒绝
        userId: this.data.userId,
      },
      header: {
        'Authorization': 'Bearer ' + auth.getToken(),
        'content-type': 'application/json',
      },
      responseType: 'text',
      success: (res) => {
        // 【C2】401/403 统一处理
        if (res.statusCode === 401 || res.statusCode === 403) {
          auth.handleAuthExpired();
          return;
        }
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
        const errMsg = (err && err.errMsg) || '网络请求失败';
        this.setData({ status: 'error', statusText: '网络异常', lastError: errMsg, showRetry: true });
      },
      complete: () => {
        this._chatTask = null;
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
      } else if ((m = t.match(/^评分[:：]\s*\d+(?:\.\d+)?\s*[/／]\s*50\s*\|(.*)$/))) {
        hasScore = true;
        const dimParts = m[1].split('|');
        for (let i = 0; i < 5; i++) {
          const v = parseFloat(dimParts[i]);
          // 不四舍五入：明显错误档会按半分折算产生 3.5 这类小数，取整会与落库分打架
          if (isNaN(v)) dims.push(0);
          else dims.push(Math.max(0, Math.min(10, v)));
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
      // 总分统一取五维之和，与后端 parsePipeScores 及三段式 parseSegmentFormat 口径一致。
      // 历史缺陷：直接取管道第 1 段"模型自报总分"，模型总分≠五维和时，气泡展示与落库分不同
      const dims = [];
      for (let i = 1; i <= 5; i++) {
        let v = parseFloat(parts[i]);
        if (isNaN(v)) v = 0;
        v = Math.max(0, Math.min(10, v));
        dims.push(v);
      }
      result.score = dims.reduce((a, b) => a + b, 0);
      result.evaluation = `表达:${dims[0]} 逻辑:${dims[1]} 专业:${dims[2]} 应变:${dims[3]} 创新:${dims[4]}`;
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
