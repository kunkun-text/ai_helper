/* 教师端网页演示 · 视图逻辑（纯前端，无后端依赖） */
(function () {
  'use strict';
  const M = window.MOCK;
  const S = window.StatsCore;
  const $ = (sel) => document.querySelector(sel);
  const charts = {};
  let topicFilter = 'all';

  const COLORS = {
    green: '#4CAF50', greenDark: '#43a047', blue: '#008cba', teal: '#00897b', orange: '#f5a623', red: '#e74c3c', radar: '#2b6cff', purple: '#7b1fa2', grey: '#b0b7c3'
  };
  const GRADE_COLORS = ['#43a047', '#008cba', '#f5a623', '#e74c3c', '#b0b7c3'];

  if (window.Chart) {
    Chart.defaults.font.family = '"PingFang SC","Microsoft YaHei","Noto Sans CJK SC",sans-serif';
    Chart.defaults.color = '#666';
    Chart.defaults.plugins.legend.labels.boxWidth = 12;
    Chart.defaults.animation = false; // 截图稳定
    Chart.defaults.maintainAspectRatio = false;
  }

  function toast(msg) {
    const t = $('#toast'); t.textContent = msg; t.classList.add('show');
    clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.remove('show'), 1800);
  }
  function esc(s) { return String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
  function fmt(v, suffix) { return v === null || v === undefined ? '—' : v + (suffix || ''); }

  function filteredRecords() {
    return topicFilter === 'all' ? M.records : M.records.filter((r) => String(r.topicId) === topicFilter);
  }
  function filteredTopics() {
    return topicFilter === 'all' ? M.topics : M.topics.filter((t) => String(t.topicId) === topicFilter);
  }

  function download(name, text) {
    const blob = new Blob([text], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = name; a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    toast('已导出 ' + name);
  }

  // ---------- 成绩统计 ----------
  function statTile(icon, color, value, label) {
    return '<div class="stat"><div class="stat-icon ' + color + '">' + icon + '</div><div><div class="stat-value">' + value + '</div><div class="stat-label">' + label + '</div></div></div>';
  }

  function chart(id, cfg) {
    if (charts[id]) charts[id].destroy();
    charts[id] = new Chart(document.getElementById(id), cfg);
  }

  function renderStats() {
    const recs = filteredRecords();
    const tps = filteredTopics();
    const stuCount = new Set(recs.map((r) => r.userNumber)).size;
    const ov = S.overview(recs, tps, topicFilter === 'all' ? M.students.length : stuCount);

    $('#statGrid').innerHTML = [
      statTile('场', 'blue', ov.totalDefenses, '答辩总场次'),
      statTile('完', 'green', ov.completedDefenses, '已完成场次'),
      statTile('进', 'orange', ov.pendingDefenses, '进行中场次'),
      statTile('均', 'teal', fmt(ov.avgScore), '平均得分（/50）'),
      statTile('合', 'purple', fmt(ov.passRate, '%'), '合格率（≥15 分）'),
      statTile('优', 'red', fmt(ov.excellentRate, '%'), '优秀率（≥35 分）'),
      statTile('题', 'blue', ov.totalTopics, '课题总数'),
      statTile('生', 'green', ov.totalStudents, '学生总数'),
      statTile('中', 'teal', fmt(ov.medianScore), '中位数'),
      statTile('σ', 'orange', fmt(ov.stdScore), '标准差'),
      statTile('高', 'purple', fmt(ov.maxScore), '最高分'),
      statTile('评', 'red', ov.scoredCount, '已出分场次')
    ].join('');

    const gd = S.gradeDistribution(recs);
    chart('chartGrade', {
      type: 'doughnut',
      data: { labels: gd.map((g) => g.grade), datasets: [{ data: gd.map((g) => g.count), backgroundColor: GRADE_COLORS, borderWidth: 2, borderColor: '#fff' }] },
      options: { cutout: '58%', plugins: { legend: { position: 'right' }, tooltip: { callbacks: { label: (c) => c.label + '：' + c.raw + ' 场' } } } }
    });

    const hist = S.histogram(recs, 5);
    chart('chartHist', {
      type: 'bar',
      data: {
        labels: hist.map((b) => b.from + '–' + b.to),
        datasets: [{ label: '场次', data: hist.map((b) => b.count), backgroundColor: hist.map((b) => GRADE_COLORS[S.GRADES.indexOf(S.gradeLevel(b.from))]), borderRadius: 6 }]
      },
      options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { precision: 0 }, grid: { color: '#f0f2f5' } }, x: { grid: { display: false }, title: { display: true, text: '总分区间（分）' } } } }
    });

    const dims = S.dimAverages(recs);
    chart('chartRadar', {
      type: 'radar',
      data: { labels: M.DIM_LABELS, datasets: [{ label: '五维均值', data: dims, borderColor: COLORS.radar, backgroundColor: 'rgba(43,108,255,0.16)', pointBackgroundColor: COLORS.radar, borderWidth: 2 }] },
      options: { plugins: { legend: { display: false } }, scales: { r: { min: 0, max: 10, ticks: { stepSize: 2, backdropColor: 'transparent' }, pointLabels: { font: { size: 13, weight: '600' } } } } }
    });

    const modeOrder = ['文字答辩', '语音答辩', '文档答辩', '视频答辩'];
    const modeCnt = modeOrder.map((m) => recs.filter((r) => r.formLabel === m).length);
    chart('chartMode', {
      type: 'pie',
      data: { labels: modeOrder, datasets: [{ data: modeCnt, backgroundColor: [COLORS.green, COLORS.blue, COLORS.teal, COLORS.red], borderColor: '#fff', borderWidth: 2 }] },
      options: { plugins: { legend: { position: 'right' } } }
    });

    const ts = S.topicStats(recs, tps, M.teacher.name);
    const short = (n) => (n.length > 9 ? n.slice(0, 9) + '…' : n);
    chart('chartPass', {
      type: 'bar',
      data: {
        labels: ts.map((t) => short(t.topicName)),
        datasets: [
          { label: '合格率 %', data: ts.map((t) => t.passRate), backgroundColor: COLORS.green, borderRadius: 4 },
          { label: '优秀率 %', data: ts.map((t) => t.excellentRate), backgroundColor: COLORS.orange, borderRadius: 4 }
        ]
      },
      options: { indexAxis: 'y', scales: { x: { min: 0, max: 100, grid: { color: '#f0f2f5' } }, y: { grid: { display: false } } }, plugins: { legend: { position: 'bottom' } } }
    });

    chart('chartTopic', {
      type: 'bar',
      data: { labels: ts.map((t) => short(t.topicName)), datasets: [{ label: '答辩场次', data: ts.map((t) => t.defenseCount), backgroundColor: COLORS.blue, borderRadius: 6 }, { label: '已完成', data: ts.map((t) => t.completedCount), backgroundColor: COLORS.green, borderRadius: 6 }] },
      options: { scales: { y: { beginAtZero: true, ticks: { precision: 0 }, grid: { color: '#f0f2f5' } }, x: { grid: { display: false } } }, plugins: { legend: { position: 'bottom' } } }
    });

    const trend = S.dailyTrend(recs, M.today, 14);
    chart('chartTrend', {
      data: {
        labels: trend.map((d) => d.day.slice(5)),
        datasets: [
          { type: 'bar', label: '答辩场次', data: trend.map((d) => d.cnt), backgroundColor: 'rgba(76,175,80,0.55)', borderRadius: 4, yAxisID: 'y' },
          { type: 'line', label: '当日平均分', data: trend.map((d) => d.avgScore), borderColor: COLORS.blue, backgroundColor: COLORS.blue, tension: 0.35, spanGaps: true, yAxisID: 'y1' }
        ]
      },
      options: {
        scales: {
          y: { beginAtZero: true, ticks: { precision: 0 }, title: { display: true, text: '场次' }, grid: { color: '#f0f2f5' } },
          y1: { position: 'right', min: 0, max: 50, title: { display: true, text: '平均分' }, grid: { display: false } },
          x: { grid: { display: false } }
        },
        plugins: { legend: { position: 'bottom' } }
      }
    });

    $('#topicTable').innerHTML = '<thead><tr><th>课题</th><th>负责教师</th><th class="num">题目数</th><th class="num">场次</th><th class="num">已完成</th><th class="num">平均分</th><th>合格率</th><th class="num">优秀率</th></tr></thead><tbody>' +
      ts.map((t) => '<tr><td>' + esc(t.topicName) + '</td><td>' + esc(t.teacherName || '未指定教师') + '</td><td class="num">' + t.questionCount + '</td><td class="num">' + t.defenseCount +
        '</td><td class="num">' + t.completedCount + '</td><td class="num score">' + fmt(t.avgScore) + '</td><td><div class="bar-cell"><div class="mini-bar"><i style="width:' + (t.passRate || 0) + '%"></i></div><span>' + fmt(t.passRate, '%') +
        '</span></div></td><td class="num">' + fmt(t.excellentRate, '%') + '</td></tr>').join('') + '</tbody>';
  }

  // ---------- 答辩记录 ----------
  function recordRows() {
    const qn = $('#qName').value.trim(), qno = $('#qNo').value.trim();
    return filteredRecords().filter((r) => (!qn || r.userName.indexOf(qn) >= 0) && (!qno || r.userNumber.indexOf(qno) >= 0));
  }
  const VERDICT = { suspect_offtopic: '疑似跑题·待复审', suspect_nonmaterial: '非答辩材料·待复审', insufficient: '内容不足·待复审', unclear: '无法判定·待复审' };

  function renderRecords() {
    const rows = recordRows();
    $('#recordTable').innerHTML = '<thead><tr><th>答辩ID</th><th>学生姓名</th><th>学号</th><th>课题</th><th>答辩形式</th><th class="num">得分</th><th>等级</th><th>状态</th><th>标签</th><th>答辩时间</th></tr></thead><tbody>' +
      rows.slice(0, 60).map((r) => {
        const s = S.displayScore(r);
        const tags = [];
        if (r.reportDocScore !== null) tags.push('<span class="tag doc">基于报告</span>');
        else if (r.hasReport) tags.push('<span class="tag doc">报告已上传</span>');
        if (r.hasVideo) tags.push('<span class="tag video">视频已上传</span>');
        if (r.defenseMode === 'voice') tags.push('<span class="tag voice">语音答辩</span>');
        if (r.materialVerdict) tags.push('<span class="tag warn">' + VERDICT[r.materialVerdict] + '</span>');
        const g = s === null ? '' : S.gradeLevel(s);
        return '<tr><td>' + r.defenseRecordId + '</td><td>' + esc(r.userName) + '</td><td>' + r.userNumber + '</td><td>' + esc(r.topicName) + '</td><td>' + r.formLabel +
          '</td><td class="num score">' + (s === null ? '—' : s.toFixed(1)) + '</td><td><span class="grade ' + g + '">' + (g || '待复审') + '</span></td><td>' +
          (r.status === 'completed' ? '<span class="tag on">已完成</span>' : '<span class="tag warn">进行中</span>') + '</td><td>' + (tags.join('') || '—') + '</td><td>' + r.defenseTime + '</td></tr>';
      }).join('') + '</tbody>';
  }

  function exportRecords() {
    // 表头与后端 ExportController 完全一致
    const header = ['答辩ID', '学生姓名', '学号', '课题', '得分', '状态', '答辩时间', '报告评析得分', '报告评析等级'];
    const rows = filteredRecords().map((r) => [r.defenseRecordId, r.userName, r.userNumber, r.topicName, r.score, r.status === 'completed' ? '已完成' : '进行中', r.defenseTime,
      r.reportDocScore === null ? '' : r.reportDocScore, r.reportDocScore === null ? '' : S.gradeLevel(r.reportDocScore)]);
    download('答辩成绩_演示数据.csv', S.toCsv(header, rows));
  }
  function exportTopics() {
    const ts = S.topicStats(filteredRecords(), filteredTopics(), M.teacher.name);
    const header = ['课题ID', '课题名称', '负责教师', '答辩时间', '题目数', '答辩场次', '已完成', '平均分', '合格率(%)', '优秀率(%)'];
    download('课题统计_演示数据.csv', S.toCsv(header, ts.map((t) => [t.topicId, t.topicName, t.teacherName, t.defenseTime, t.questionCount, t.defenseCount, t.completedCount, t.avgScore, t.passRate, t.excellentRate])));
  }

  // ---------- 个人信息 ----------
  let editing = false;
  function renderProfile() {
    const u = M.teacher;
    $('#welcomeName').textContent = '欢迎，' + u.name;
    const f = $('#profileForm');
    ['name', 'userNumber', 'phone', 'email'].forEach((k) => { f.elements[k].value = u[k]; f.elements[k].disabled = !editing; });
    $('#btnEdit').textContent = editing ? '保存信息' : '修改个人信息';
    $('#btnCancel').classList.toggle('hidden', !editing);
    $('#editState').textContent = editing ? '编辑中' : '只读';
    $('#profileStats').innerHTML = [
      statTile('题', 'blue', M.topics.length, '答辩题目'),
      statTile('记', 'green', M.records.length, '答辩记录'),
      statTile('均', 'teal', fmt(S.overview(M.records, M.topics, 0).avgScore), '平均得分'),
      statTile('馈', 'orange', M.feedbacks.filter((x) => x.status === 'pending').length, '待回复反馈')
    ].join('');
    const pend = M.feedbacks.filter((x) => x.status === 'pending').length;
    $('#fbBadge').textContent = pend; $('#fbBadge').classList.toggle('hidden', !pend);
    $('#myTopics').innerHTML = M.topics.map((t) => '<div class="t-item"><div><div class="t-title">' + esc(t.topicName) + '</div><div class="t-desc">' + esc(t.desc) + ' · 题目 ' + t.questionCount + ' 道</div></div><div class="t-date">答辩时间：' + t.defenseTime + '</div></div>').join('');
  }
  function onEdit() {
    if (!editing) { editing = true; renderProfile(); return; }
    const f = $('#profileForm');
    const phone = f.elements.phone.value.trim(), email = f.elements.email.value.trim();
    if (!f.elements.name.value.trim()) return toast('姓名不能为空');
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return toast('邮箱格式不正确');
    if (phone && !/^[\d*]{11}$/.test(phone)) return toast('手机号需为 11 位');
    Object.assign(M.teacher, { name: f.elements.name.value.trim(), userNumber: f.elements.userNumber.value.trim(), phone: phone, email: email });
    editing = false; renderProfile(); $('#chipName').textContent = M.teacher.name; toast('保存成功（演示环境仅本地生效）');
  }

  // ---------- 路由 ----------
  const TITLES = { stats: ['成绩统计', '成绩统计 · 数据总览'], records: ['答辩记录', '学生答辩记录'], profile: ['我的', '个人信息'] };
  function route() {
    const r = (location.hash || '#stats').slice(1).split('?')[0];
    const name = TITLES[r] ? r : 'stats';
    document.querySelectorAll('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + name));
    document.querySelectorAll('[data-route]').forEach((a) => a.classList.toggle('active', a.dataset.route === name));
    $('#crumb').textContent = '教师端 / ' + TITLES[name][0];
    $('#pageTitle').textContent = TITLES[name][1];
    $('#topicFilter').style.display = name === 'profile' ? 'none' : '';
    if (name === 'stats') renderStats();
    if (name === 'records') renderRecords();
    if (name === 'profile') renderProfile();
    window.scrollTo(0, 0);
  }

  function init() {
    $('#chipName').textContent = M.teacher.name;
    $('#topicFilter').innerHTML = '<option value="all">全部课题</option>' + M.topics.map((t) => '<option value="' + t.topicId + '">' + esc(t.topicName) + '</option>').join('');
    $('#topicFilter').addEventListener('change', (e) => { topicFilter = e.target.value; route(); });
    $('#qName').addEventListener('input', renderRecords);
    $('#qNo').addEventListener('input', renderRecords);
    $('#btnClear').addEventListener('click', () => { $('#qName').value = ''; $('#qNo').value = ''; renderRecords(); });
    $('#btnExport').addEventListener('click', exportRecords);
    $('#btnExportTopic').addEventListener('click', exportTopics);
    $('#cellExport').addEventListener('click', (e) => { e.preventDefault(); exportRecords(); });
    $('#cellExportTopic').addEventListener('click', (e) => { e.preventDefault(); exportTopics(); });
    $('#cellFeedback').addEventListener('click', (e) => { e.preventDefault(); toast('反馈管理：' + M.feedbacks.length + ' 条（演示）'); });
    $('#btnEdit').addEventListener('click', onEdit);
    $('#btnCancel').addEventListener('click', () => { editing = false; renderProfile(); });
    window.addEventListener('hashchange', route);
    route();
    document.body.setAttribute('data-ready', '1');
  }
  init();
})();
