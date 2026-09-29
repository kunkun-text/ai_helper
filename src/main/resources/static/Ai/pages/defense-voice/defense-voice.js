const config = require('../../utils/config.js');

/**
 * 语音答辩页（F10 · 2026-09-29）。
 *
 * 设计原则（与需求文档一致）：**语音只是输入/输出的载体，不是新流程**。
 * - AI 提问 → 前端用「微信同声传译」插件把题目文本念出来（TTS）
 * - 学生作答 → 插件把语音转成文字（ASR）→ 回填到可编辑输入框 → 拿这段文字调**现有的 /api/chat**
 * 于是题库、10 轮节奏、五维评分、三档复核、幂等、轮次计数、收尾、续答 —— 全部零改动复用。
 *
 * 与文字答辩（pages/defense）的关系：接口调用、回复解析、序号标注、收尾弹窗**完全同口径**，
 * 只把底部「输入栏」换成「按住说话 + 识别回填」。因此本页独立成页，避免改动已稳定的文字答辩页。
 */

// 【2026-09-29 方案调整（重要）】
// 原计划用「微信同声传译」插件在前端做语音识别与合成，但**已验证个人主体的小程序无法添加该插件**
// （插件管理里搜不到、服务市场里也搜不到，改类目同样无效）—— 这是微信对个人主体的限制。
// 因此改为全部走后端，彻底不依赖小程序主体资质、换任何小程序都能用：
//   ① AI 提问的语音播报 → POST /api/voice/tts（后端离线合成，小程序只负责播放）
//   ② 学生语音作答     → 小程序原生录音 wx.getRecorderManager（**不需要插件**）
//                        → wx.uploadFile 上传 → POST /api/voice/asr（后端 whisper.cpp 识别）
//                        → 识别文字回填到可编辑输入框，确认后再「发送」走现有 /api/chat
// 注意：识别结果**允许修改** —— 专业术语识别错字时学生可以自己纠正，避免影响得分。

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

    // ======== 语音作答状态 ========
    answerText: '',       // 识别结果 / 手输文字（送进 /api/chat 的就是它）
    isRecording: false,   // 是否正在录音
    isRecognizing: false, // 松开后到识别结果返回之间
    recordSeconds: 0,     // 录音计时（提示用）
    isSpeaking: false,    // 是否正在播报题目
    voiceTip: '',         // 语音区提示文案（优先级低于录音/识别/播报状态）
    speakText: '',        // 最近一次可重播的题目文本
    asrReady: true,       // 后端语音识别引擎是否就绪（进页面时查询一次）
    asrTip: '',           // 引擎不可用时的提示（展示在底部，避免"点了没反应"）

    lastAiMessage: null,
    questionCount: 0,
    presetRounds: 5, // 预设题数量（10 轮答辩 = 5 预设题 + 5 追问）
    totalRounds: 10,
    lastError: '',
    showRetry: false,

    // 等待计时器
    waitSeconds: 0,
    showWaitTimer: false,
    _lastPrompt: '',
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

    this.initVoice();
    this.checkVoiceStatus();

    // 与文字答辩同一套进场逻辑：先只读探测「有没有未完成的答辩」，有则让用户自己选。
    wx.request({
      url: config.getBaseUrl() + '/api/chat/resume-info',
      method: 'POST',
      data: { topicId: this.data.topicId, userId: this.data.userId },
      header: {
        'Authorization': 'Bearer ' + (wx.getStorageSync('token') || ''),
        'content-type': 'application/json',
      },
      success: (res) => {
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

  onUnload() {
    this._stopWaitTimer();
    this._stopRecordTimer();
    this.stopSpeak();
  },

  // ======== 语音能力（ASR + TTS） ========

  /**
   * 语音能力初始化：
   * ① 允许 iOS 静音模式下也能听到题目播报；
   * ② 初始化**小程序原生录音管理器**（{@code wx.getRecorderManager}，不需要任何插件）。
   */
  initVoice() {
    try {
      wx.setInnerAudioOption({ obeyMuteSwitch: false });
    } catch (e) {
      // 低版本基础库不支持该配置，忽略
    }

    const recorder = wx.getRecorderManager();

    recorder.onStart(() => {
      if (!this._voicePressed) {
        // 已松手（快速点按），不再展示录音中状态
        return;
      }
      this.setData({ isRecording: true, isRecognizing: false, voiceTip: '' });
      this._startRecordTimer();
    });

    recorder.onStop((res) => {
      this._voicePressed = false;
      this._stopRecordTimer();
      this.setData({ isRecording: false });

      const filePath = res && res.tempFilePath;
      if (!filePath) {
        this.setData({ isRecognizing: false, voiceTip: '录音失败，请重试' });
        wx.showToast({ title: '录音失败，请重试', icon: 'none' });
        return;
      }
      this.setData({ isRecognizing: true, voiceTip: '识别中…' });
      this.uploadAudioForText(filePath);
    });

    recorder.onError((err) => {
      this._voicePressed = false;
      this._stopRecordTimer();
      this.setData({ isRecording: false, isRecognizing: false });
      const msg = (err && (err.errMsg || err.msg)) || '';
      if (msg.indexOf('auth') > -1 || msg.indexOf('permission') > -1 || msg.indexOf('deny') > -1) {
        this.setData({ voiceTip: '未获得麦克风权限，可在设置中开启' });
        wx.showModal({
          title: '需要麦克风权限',
          content: '语音作答需要录音权限才能把你的话转成文字，是否去设置里开启？',
          confirmText: '去设置',
          success: (r) => {
            if (r.confirm) wx.openSetting({});
          },
        });
      } else {
        this.setData({ voiceTip: '录音失败，请重试或改用文字输入' });
        wx.showToast({ title: '录音失败，请重试', icon: 'none' });
      }
      console.error('[语音答辩] 录音出错：', err);
    });

    this._recorder = recorder;

    // 【录音格式按运行环境自适应 —— 同时满足「电脑端（开发者工具）」与「真机」】
    // · 开发者工具（模拟器）→ wav：模拟器产出的压缩格式常与真机不一致（后端解不了），
    //   wav 是通用无损格式，后端可直通识别引擎，最适合电脑端联调。
    // · 真机（iOS / Android）→ mp3：微信官方明确支持的格式，后端会解码成 wav 再识别。
    let platform = '';
    try {
      platform = (wx.getSystemInfoSync() || {}).platform || '';
    } catch (e) {
      platform = '';
    }
    this._recordFormat = platform === 'devtools' ? 'wav' : 'mp3';
    console.log('[语音答辩] 运行平台:', platform || '未知', ', 录音格式:', this._recordFormat);
  },

  /** 查询后端识别引擎是否就绪：未装好时直接提示用文字作答，避免学生按半天没反应 */
  checkVoiceStatus() {
    wx.request({
      url: config.getBaseUrl() + '/api/voice/status',
      method: 'GET',
      header: {
        'Authorization': 'Bearer ' + (wx.getStorageSync('token') || ''),
      },
      success: (res) => {
        const data = (res.data && res.data.data) || {};
        const ready = data.asrReady !== false;
        this.setData({
          asrReady: ready,
          asrTip: ready ? '' : ('语音识别未就绪：' + (data.asrMessage || '') + '（可先用文字作答）'),
        });
        if (!ready) {
          console.warn('[语音答辩] 识别引擎未就绪：', data.asrMessage);
        }
      },
      fail: () => {
        // 查询失败不阻塞答辩：按可用处理，真按下去了再由识别接口给出提示
      },
    });
  },

  /** 按住说话：开始录音 */
  onVoiceTouchStart() {
    if (this.data.isFinished) {
      return;
    }
    if (this.data.isAiThinking) {
      wx.showToast({ title: 'AI 思考中，请稍候', icon: 'none' });
      return;
    }
    if (!this.data.asrReady) {
      wx.showToast({ title: '语音识别未就绪，请用文字作答', icon: 'none' });
      return;
    }
    if (!this._recorder) {
      wx.showToast({ title: '录音不可用，请用文字作答', icon: 'none' });
      return;
    }
    // 先停掉正在播报的题目，避免把播报声录进去
    this.stopSpeak();
    this._voicePressed = true;
    this.setData({ answerText: '', voiceTip: '', isRecognizing: false });
    try {
      this._recorder.start({
        duration: 60000,
        sampleRate: 16000,
        numberOfChannels: 1,
        encodeBitRate: 48000,
        // 实际格式见 initVoice：开发者工具 wav / 真机 mp3
        format: this._recordFormat || 'mp3',
      });
    } catch (e) {
      this._voicePressed = false;
      console.error('[语音答辩] 启动录音失败：', e);
      this.setData({ voiceTip: '录音启动失败，请重试' });
    }
  },

  /** 松开手指：结束录音（识别在 onStop 回调里发起） */
  onVoiceTouchEnd() {
    this._stopRecordingInner(false);
  },

  /** 手指滑出按钮：同样结束录音，避免录音一直开着 */
  onVoiceTouchCancel() {
    this._stopRecordingInner(true);
  },

  _stopRecordingInner(cancel) {
    if (!this._voicePressed && !this.data.isRecording) {
      return;
    }
    this._voicePressed = false;
    try {
      if (this._recorder) this._recorder.stop();
    } catch (e) {
      // 未真正开始录音时 stop 会报错，忽略即可
    }
    this._stopRecordTimer();
    if (cancel) {
      this.setData({ isRecording: false, isRecognizing: false, voiceTip: '' });
    } else {
      this.setData({ isRecording: false, isRecognizing: true, voiceTip: '识别中…' });
    }
  },

  /**
   * 上传录音到后端识别，成功后**回填到可编辑输入框**（学生可先改错字再发送）。
   * 识别失败/引擎未就绪时给出可读提示，不阻塞答辩。
   */
  uploadAudioForText(tempFilePath) {
    wx.uploadFile({
      url: config.getBaseUrl() + '/api/voice/asr',
      filePath: tempFilePath,
      name: 'file',
      formData: { format: this._recordFormat || 'mp3' },
      timeout: 180000,
      header: {
        'Authorization': 'Bearer ' + (wx.getStorageSync('token') || ''),
      },
      success: (res) => {
        let payload = {};
        try {
          payload = JSON.parse(res.data || '{}');
        } catch (e) {
          payload = {};
        }

        // code 非 1 = 后端给出的业务提示（引擎未就绪 / 超时 / 音频异常）
        if (payload.code !== 1) {
          const msg = payload.msg || '识别失败，请重试';
          this.setData({ isRecognizing: false, voiceTip: msg });
          wx.showToast({ title: msg, icon: 'none', duration: 2500 });
          return;
        }

        const data = payload.data || {};
        const text = (data.text || '').trim();
        if (!text) {
          this.setData({ isRecognizing: false, voiceTip: '没听清，请按住再说一次' });
          wx.showToast({ title: '没听清，请重试', icon: 'none' });
          return;
        }

        this.setData({
          isRecognizing: false,
          answerText: text,
          voiceTip: '识别完成，确认无误后点「发送」',
        });
      },
      fail: (err) => {
        console.error('[语音答辩] 上传识别失败：', err);
        this.setData({ isRecognizing: false, voiceTip: '网络异常，识别失败，请重试' });
        wx.showToast({ title: '识别失败，请重试', icon: 'none' });
      },
    });
  },

  /** 识别结果（或手输文字）可编辑 */
  onAnswerInput(e) {
    this.setData({ answerText: e.detail.value });
  },

  /**
   * 播报文本：先让后端合成（同一段文字有缓存），拿到音频地址后用 innerAudioContext 播放。
   *
   * <p>合成失败只记日志、**不影响答辩流程**（屏幕上有完整文字，学生照样能答题）。</p>
   */
  speak(text) {
    if (!text) {
      return;
    }
    // 过长文本截断播报（屏幕上仍是完整文字）
    const content = text.length > 200 ? text.substring(0, 200) : text;
    this.stopSpeak();

    wx.request({
      url: config.getBaseUrl() + '/api/voice/tts',
      method: 'POST',
      header: {
        'Authorization': 'Bearer ' + (wx.getStorageSync('token') || ''),
        'content-type': 'application/json',
      },
      data: { text: content },
      success: (res) => {
        const data = (res.data && res.data.data) || {};
        if (res.statusCode !== 200 || !data.url) {
          console.warn('[语音答辩] 语音合成未成功，跳过本次播报');
          return;
        }
        this.playAudio(config.getBaseUrl() + data.url);
      },
      fail: (err) => {
        console.error('[语音答辩] 语音合成请求失败：', err);
      },
    });
  },

  /** 播放后端返回的音频地址 */
  playAudio(src) {
    if (!src) {
      return;
    }
    this.stopSpeak();
    const ctx = wx.createInnerAudioContext();
    ctx.src = src;
    ctx.onPlay(() => this.setData({ isSpeaking: true }));
    ctx.onEnded(() => this._releaseAudio());
    ctx.onError((err) => {
      console.error('[语音答辩] 音频播放失败：', err);
      this._releaseAudio();
    });
    ctx.play();
    this._audioCtx = ctx;
  },

  /** 停止播报并释放音频上下文 */
  stopSpeak() {
    if (this._audioCtx) {
      try {
        this._audioCtx.stop();
        this._audioCtx.destroy();
      } catch (e) {
        // 已销毁时忽略
      }
      this._audioCtx = null;
    }
    if (this.data.isSpeaking) {
      this.setData({ isSpeaking: false });
    }
  },

  _releaseAudio() {
    if (this._audioCtx) {
      try {
        this._audioCtx.destroy();
      } catch (e) {
        // 忽略
      }
      this._audioCtx = null;
    }
    this.setData({ isSpeaking: false });
  },

  /** 重播当前题目 */
  replayQuestion(e) {
    const text = (e && e.currentTarget && e.currentTarget.dataset.text) || this.data.speakText;
    if (!text) {
      wx.showToast({ title: '暂无可播报的题目', icon: 'none' });
      return;
    }
    this.speak(text);
  },

  // ======== 录音计时 ========

  _startRecordTimer() {
    this._stopRecordTimer();
    this.setData({ recordSeconds: 0 });
    this._recordTimer = setInterval(() => {
      this.setData({ recordSeconds: this.data.recordSeconds + 1 });
    }, 1000);
  },

  _stopRecordTimer() {
    if (this._recordTimer) {
      clearInterval(this._recordTimer);
      this._recordTimer = null;
    }
  },

  // ======== 等待计时器（与文字答辩一致） ========

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

    // 语音答辩的核心差异：AI 的题自动念出来（有题播题，收尾轮播总结）
    const toSpeak = msg.question || (msg.isSummary ? msg.summary : '');
    if (toSpeak) {
      this.setData({ speakText: toSpeak });
      this.speak(toSpeak);
    }

    // 只有真正出总结时才结束答辩；每轮常规打分不触发
    if (msg.isSummary) {
      this.finishDefense();
    }
    return msg;
  },

  // ======== AI 通信（180秒无硬杀） ========

  callAi(prompt) {
    this.setData({ isAiThinking: true, lastError: '', showRetry: false });
    this._startWaitTimer();
    this._lastPrompt = prompt;

    wx.request({
      url: config.getBaseUrl() + '/api/chat',
      method: 'POST',
      timeout: 180000,
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

  // ======== AI 回复解析（与文字答辩完全同口径） ========

  parseAiResponse(text) {
    const result = {
      displayText: '', evaluation: '', score: null, question: '',
      summary: '', videoAnalysis: '', reportAnalysis: '', totalScore: null,
    };

    if (/点评[:：]/.test(text) || /评分[:：]/.test(text) || /下一题[:：]/.test(text)) {
      return this.parseSegmentFormat(text, result);
    }

    if (text.includes('|') && !text.includes('【')) {
      return this.parsePipeFormat(text, result);
    }

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
        comment += t;
      }
    }
    while (dims.length < 5) dims.push(0);
    const dimLabels = ['表达', '逻辑', '专业', '应变', '创新'];
    let sum = 0;
    for (let i = 0; i < 5; i++) sum += dims[i];
    result.score = hasScore ? sum : null;
    result.totalScore = result.score;
    result.evaluation = hasScore ? dims.map((v, i) => `${dimLabels[i]}:${v}`).join(' ') : '';
    result.displayText = comment || result.evaluation;
    if (result.question) this.markQuestionNumber(result);
    return result;
  },

  /** 解析管道格式：得分/50|表达|逻辑|专业|应变|创新|优点|建议|下个问题 */
  parsePipeFormat(text, result) {
    const line = text.split('\n')[0].trim();
    const parts = line.split('|');
    if (parts.length >= 8) {
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

  // ======== 进场 / 续答（与文字答辩一致） ========

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
        'Authorization': 'Bearer ' + (wx.getStorageSync('token') || ''),
        'content-type': 'application/json',
      },
      success: (res) => {
        const data = res.data || {};
        if (data.resumed) {
          this.resumeDefense(data);
        } else {
          this.beginNewDefense();
        }
      },
      fail: () => this.beginNewDefense(),
    });
  },

  /** 全新答辩：与文字答辩完全一致的进场流程 */
  beginNewDefense() {
    this.addSystemMessage('语音答辩开始，AI考官已就位。题目会自动播报，请按住下方按钮作答。');
    this.callAi('');
  },

  /** 继续未完成的答辩：不调模型，直接展示当前该答的题 */
  resumeDefense(data) {
    const answered = data.answeredCount || 0;
    const roundNum = data.roundNum || (answered + 1);
    this.setData({ questionCount: answered, status: 'ongoing', statusText: '答辩进行中' });
    this.addSystemMessage(`检测到未完成的答辩，已为你继续：前 ${answered} 题已完成，当前第 ${roundNum} 题。`);

    if (data.currentQuestion) {
      const text = '点评:请继续作答。\n下一题:' + data.currentQuestion;
      this.addAiMessage(text, this.parseAiResponse(text));
    } else {
      this.addSystemMessage('请继续作答上一轮考官提出的问题。');
    }
  },

  // ======== 用户操作 ========

  /** 发送作答：识别出的文字（可编辑）走现有 /api/chat */
  sendAnswer() {
    const text = this.data.answerText.trim();
    if (!text) { wx.showToast({ title: '请先说话或输入回答', icon: 'none' }); return; }
    if (this.data.isFinished) { wx.showToast({ title: '答辩已结束', icon: 'none' }); return; }
    if (this.data.isAiThinking) { wx.showToast({ title: 'AI思考中，请稍候', icon: 'none' }); return; }
    if (this.data.isRecording || this.data.isRecognizing) {
      wx.showToast({ title: '正在录音/识别，请稍候', icon: 'none' });
      return;
    }

    this.stopSpeak();
    this.addUserMessage(text);
    this.setData({ answerText: '', voiceTip: '' });
    this.callAi(text);
  },

  // 复制题目文本：PC 工具模拟器不支持鼠标选择文本，user-select 只在真机长按生效，故给显式复制入口
  copyQuestion(e) {
    const text = e.currentTarget.dataset.text;
    if (!text) return;
    wx.setClipboardData({ data: text });
  },

  finishDefense() {
    this.setData({
      isFinished: true, status: 'finished', statusText: '答辩已完成', showFinishModal: true,
    });
  },

  goBack() {
    this.stopSpeak();
    wx.navigateBack({ delta: 1, fail: () => { wx.redirectTo({ url: '/pages/student/student' }); } });
  },

  closeFinishModal() { this.setData({ showFinishModal: false }); },

  onScrollToTop() {},
});
