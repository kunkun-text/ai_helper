/**
 * 教师端网页演示 · 模拟数据（全部为虚构示例，仅用于界面展示与截图）
 *
 * 字段名与后端接口逐一对应，便于日后直接替换为真实接口：
 *   - 个人信息   ← 登录返回 / editUserInfo：name、userNumber、phone、email
 *   - 答辩记录   ← /teacher/DefenseRecords（DefenseRecordsVo）：defenseRecordId、userName、userNumber、topicName、
 *                  score、status、defenseTime、reportDocScore、defenseMode、hasVideo、materialVerdict
 *   - 逐轮五维   ← /teacher/defense/scoreDetail（defense_score_record）：expressionScore … innovationScore（0~10）
 *   - 数据总览   ← /teacher/stats/overview：overview / topicDistribution / dailyTrend
 *   - 按课题统计 ← /teacher/stats/topics：topicId、topicName、teacherName、questionCount、defenseCount、completedCount、avgScore
 * 评分口径：单轮五维各 10 分、总分 50 分；等级阈值与后端 DocScoreParser.gradeLevel 一致。
 */
(function (global) {
  'use strict';

  // 固定种子的伪随机数，保证每次打开、每次截图数据完全一致
  let seed = 20261121;
  function rand() {
    seed = (seed * 1664525 + 1013904223) % 4294967296;
    return seed / 4294967296;
  }
  function pick(arr) { return arr[Math.floor(rand() * arr.length)]; }
  function gauss(mean, sd) {
    let u = 0, v = 0;
    while (u === 0) u = rand();
    while (v === 0) v = rand();
    return mean + sd * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
  }
  function clamp(x, lo, hi) { return Math.max(lo, Math.min(hi, x)); }
  function r1(x) { return Math.round(x * 10) / 10; }
  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  const teacher = {
    userId: 2,
    name: '林知远',
    userNumber: 'T20250018',
    phone: '138****6021',
    email: 'lin.zhiyuan@demo-university.edu.cn',
    role: 'teacher',
    college: '计算机科学与工程学院（示例）',
    title: '讲师（示例）'
  };

  const topics = [
    { topicId: 1, topicName: 'Hadoop 分布式文件系统原理与实践', defenseTime: '2026-11-08', questionCount: 5, mean: 33, desc: 'HDFS 架构、副本机制、NameNode 高可用' },
    { topicId: 2, topicName: 'MapReduce 编程与性能优化', defenseTime: '2026-11-10', questionCount: 5, mean: 30, desc: '小文件合并、Combiner、数据倾斜处理' },
    { topicId: 3, topicName: 'Spark 内存计算与 RDD 调优', defenseTime: '2026-11-12', questionCount: 5, mean: 31, desc: 'RDD 依赖、Shuffle、缓存策略' },
    { topicId: 4, topicName: 'Hive 数据仓库建模', defenseTime: '2026-11-14', questionCount: 5, mean: 28, desc: '分层建模、分区分桶、HQL 优化' },
    { topicId: 5, topicName: '数据安全与隐私保护（K 匿名）', defenseTime: '2026-11-16', questionCount: 5, mean: 27, desc: 'K 匿名、L 多样性、T 接近度' },
    { topicId: 6, topicName: 'Python 数据分析与可视化', defenseTime: '2026-11-18', questionCount: 5, mean: 34, desc: 'Pandas 清洗、统计检验、可视化表达' }
  ];

  const surnames = '王李张刘陈杨赵黄周吴徐孙马胡朱郭何罗高林郑梁谢唐许冯宋韩邓彭曹曾田萧潘袁蔡蒋余于杜叶程魏苏吕丁任沈'.split('');
  const given = ['子涵', '欣怡', '浩然', '梓萱', '宇轩', '雨桐', '俊杰', '思远', '佳琪', '一诺', '晨曦', '嘉懿', '明轩', '若曦', '天佑',
    '诗涵', '博文', '可馨', '泽宇', '语嫣', '沐阳', '静怡', '昊天', '雅琳', '锦程', '书瑶', '逸凡', '梦洁', '子墨', '安琪', '文博', '思彤'];

  const students = [];
  const usedNames = {};
  for (let i = 0; i < 48; i++) {
    let name;
    do { name = pick(surnames) + pick(given); } while (usedNames[name]);
    usedNames[name] = true;
    students.push({ userName: name, userNumber: '2026D' + pad(Math.floor(i / 10) + 1) + pad(i % 10 + 1) + '0' });
  }

  const modes = [
    { key: 'text', label: '文字答辩', w: 0.46 },
    { key: 'voice', label: '语音答辩', w: 0.18 },
    { key: 'report', label: '文档答辩', w: 0.22 },
    { key: 'video', label: '视频答辩', w: 0.14 }
  ];
  function pickMode() {
    let x = rand(), acc = 0;
    for (const m of modes) { acc += m.w; if (x <= acc) return m.key; }
    return 'text';
  }

  const DIM_KEYS = ['expressionScore', 'logicScore', 'professionalScore', 'adaptabilityScore', 'innovationScore'];
  const DIM_BIAS = [0.3, 0.6, 0.2, -0.6, -1.1]; // 应变、创新普遍偏弱（示例分布）

  const comments = [
    '回答结构清晰，能结合课题背景说明设计取舍。',
    '核心概念表述准确，但对边界条件的讨论不足。',
    '能举出具体实例，逻辑链条完整。',
    '部分术语使用不够严谨，建议补充原理层面的解释。',
    '对追问的应对较为从容，能主动补充实验数据。',
    '回答偏离题目要点，需加强对核心问题的把握。'
  ];

  const today = new Date('2026-11-21T10:00:00+08:00');
  const records = [];
  let id = 1001;
  for (let k = 0; k < 136; k++) {
    const topic = topics[Math.floor(rand() * topics.length)];
    const stu = pick(students);
    const mode = pickMode();
    const dayOffset = Math.floor(rand() * 14);
    const d = new Date(today.getTime() - dayOffset * 86400000);
    const hh = 8 + Math.floor(rand() * 12), mm = Math.floor(rand() * 60);
    const defenseTime = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(hh) + ':' + pad(mm) + ':00';
    const pending = rand() < 0.08;
    const offTopic = rand() < 0.035;
    // 逐轮五维（文字/语音为 10 轮；文档/视频为一张成绩单，记作 1 轮）
    const roundCount = (mode === 'text' || mode === 'voice') ? (pending ? 3 + Math.floor(rand() * 5) : 10) : 1;
    const ability = clamp(gauss(topic.mean / 5, 1.35), 1.2, 9.6);
    const rounds = [];
    for (let r = 1; r <= roundCount; r++) {
      const row = { roundNum: r };
      DIM_KEYS.forEach((key, i) => {
        row[key] = offTopic ? 0 : r1(clamp(gauss(ability + DIM_BIAS[i], 0.9), 0, 10));
      });
      row.comment = offTopic ? '[跑题] 回答与本题无关，按 0 分计。' : pick(comments);
      rounds.push(row);
    }
    const avgTotal = r1(rounds.reduce((s, row) => s + DIM_KEYS.reduce((a, key) => a + row[key], 0), 0) / rounds.length);
    const isDoc = mode === 'report';
    const verdict = isDoc && rand() < 0.12 ? pick(['suspect_offtopic', 'insufficient', 'unclear']) : (isDoc ? 'ok' : '');
    records.push({
      defenseRecordId: id++,
      userName: stu.userName,
      userNumber: stu.userNumber,
      topicId: topic.topicId,
      topicName: topic.topicName,
      score: isDoc ? 0 : avgTotal,
      reportDocScore: isDoc ? (verdict === 'ok' ? avgTotal : null) : null,
      status: pending ? 'pending' : 'completed',
      defenseTime: defenseTime,
      defenseMode: mode === 'voice' ? 'voice' : 'text',
      formLabel: modes.find((m) => m.key === mode).label,
      formKey: mode,
      hasVideo: mode === 'video',
      hasReport: isDoc,
      materialVerdict: verdict === 'ok' ? '' : verdict,
      rounds: rounds
    });
  }
  records.sort((a, b) => (a.defenseTime < b.defenseTime ? 1 : -1));

  const feedbacks = [
    { id: 1, userName: students[3].userName, content: '语音答辩识别专业术语偶有错字，希望支持手动修正后再提交。', status: 'replied' },
    { id: 2, userName: students[8].userName, content: '成绩单的五维雷达图很直观，建议增加历史成绩对比。', status: 'pending' },
    { id: 3, userName: students[15].userName, content: '视频上传进度条在弱网下更新较慢。', status: 'pending' }
  ];

  global.MOCK = {
    teacher: teacher,
    topics: topics,
    students: students,
    records: records,
    feedbacks: feedbacks,
    DIM_KEYS: DIM_KEYS,
    DIM_LABELS: ['表达', '逻辑', '专业', '应变', '创新'],
    today: today
  };
})(window);
