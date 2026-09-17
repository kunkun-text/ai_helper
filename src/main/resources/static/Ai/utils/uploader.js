/**
 * 通用上传模块（视频分片上传 + 附件单次直传）。
 *
 * 设计目标：
 * 1. 上传逻辑集中一处，页面不再各写一套（原先 student.js 里视频约 400 行、报告约 160 行，互不复用）；
 * 2. 视频走「真实字节分片」，每片可单独重试，失败后可带 uploadId 续传；
 * 3. 服务端返回的规则（大小上限、允许格式、分片大小）通过接口拉取，前后端不再各写一份硬编码。
 *
 * 约定：所有方法返回 Promise，成功回调收到的都是后端 Result 里的 data；
 * 失败回调收到 { code, msg }，msg 已是可以直接展示给用户的文案。
 */
const config = require('./config.js');

/** 单个分片的最大重试次数 */
const CHUNK_RETRY = 3;
/** 单分片请求超时（毫秒） */
const CHUNK_TIMEOUT = 120000;
/** 单文件直传超时（毫秒），报告/小视频用 */
const WHOLE_TIMEOUT = 300000;
/** 普通接口超时（毫秒） */
const JSON_TIMEOUT = 60000;

const fileSystem = wx.getFileSystemManager();

/** 统一取服务器地址（含 N26 修复：所有页面共用这一个出口） */
function getBaseUrl() {
  return config.getBaseUrl();
}

/**
 * 把库中存的文件地址转成可直接用于 <video src> / wx.downloadFile 的绝对地址。
 *
 * 兼容三种历史值：
 * - 新的相对路径：/files/videos/xxx.mp4  → 补上服务器地址
 * - 早期阿里云 OSS 绝对地址：http(s)://…  → 原样返回
 */
function resolveFileUrl(value) {
  if (!value) {
    return '';
  }
  const url = String(value).trim();
  if (/^(https?:)?\/\//i.test(url)) {
    return url;
  }
  if (url.charAt(0) === '/') {
    return getBaseUrl() + url;
  }
  return url;
}

/** 统一日志前缀，便于在开发者工具 Console 里过滤 */
function log() {
  const args = Array.prototype.slice.call(arguments);
  console.log.apply(console, ['[uploader]'].concat(args));
}

/**
 * 把任意异常规整为 {code, msg} 结构。
 *
 * 若某个 .then 里抛了 JS 异常（例如对 null 取属性），Promise 会以"裸异常"拒绝，
 * 页面拿不到 msg 只能显示"上传失败"，排查时完全看不出原因。
 */
function normalizeError(err, fallback) {
  if (err && err.msg) {
    return err;
  }
  const detail = (err && (err.errMsg || err.message)) || String(err);
  return { code: -1, msg: (fallback || '上传失败') + '：' + detail, raw: err };
}

function jsonHeader(token) {
  return {
    'Authorization': 'Bearer ' + (token || ''),
    'content-type': 'application/json'
  };
}

function formHeader(token) {
  return {
    'Authorization': 'Bearer ' + (token || ''),
    'content-type': 'application/x-www-form-urlencoded'
  };
}

/** 解析后端统一 Result 结构 */
function parseResult(res) {
  if (res.statusCode === 401) {
    return { error: { code: 401, msg: '登录已过期，请重新登录' } };
  }
  let body = res.data;
  if (typeof body === 'string') {
    try {
      body = JSON.parse(body);
    } catch (e) {
      return { error: { code: -1, msg: '服务端返回格式异常（HTTP ' + res.statusCode + '）' } };
    }
  }
  if (!body || typeof body !== 'object') {
    return { error: { code: -1, msg: '服务端未返回内容（HTTP ' + res.statusCode + '）' } };
  }
  if (body.code === 1) {
    return { data: body.data };
  }
  return { error: { code: body.code, msg: body.msg || '操作失败' } };
}

/** 普通 JSON 接口调用 */
function request(options) {
  return new Promise(function (resolve, reject) {
    wx.request({
      url: options.url,
      method: options.method || 'GET',
      data: options.data,
      header: options.header,
      timeout: options.timeout || JSON_TIMEOUT,
      success: function (res) {
        const parsed = parseResult(res);
        if (parsed.error) {
          reject(parsed.error);
        } else {
          resolve(parsed.data);
        }
      },
      fail: function (err) {
        reject({ code: -1, msg: (err && err.errMsg) || '网络请求失败' });
      }
    });
  });
}

/** 读取本地文件的指定字节区间，返回 ArrayBuffer */
function readChunk(filePath, position, length) {
  return new Promise(function (resolve, reject) {
    fileSystem.readFile({
      filePath: filePath,
      position: position,
      length: length,
      success: function (res) {
        if (!res || !res.data) {
          reject({ code: -1, msg: '读取本地文件内容为空' });
          return;
        }
        resolve(res.data);
      },
      fail: function (err) {
        reject({ code: -1, msg: '读取本地文件失败：' + ((err && err.errMsg) || '未知原因') });
      }
    });
  });
}

/** 拉取某类附件的上传规则（大小上限 / 允许格式 / 分片大小）
 *  注意：/api/video/** 与 /api/report/** 都在登录校验范围内，这里必须带上 token，
 *  否则会固定返回 401（曾因漏传导致报告/视频上传统统卡在第一步）。 */
function fetchPolicy(kind, token) {
  const path = kind === 'video' ? '/api/video/policy' : '/api/report/policy';
  return request({
    url: getBaseUrl() + path,
    method: 'GET',
    header: jsonHeader(token)
  });
}

/** 查询分片上传进度（失败重试时用于续传） */
function queryProgress(uploadId, token) {
  return request({
    url: getBaseUrl() + '/api/video/status?uploadId=' + encodeURIComponent(uploadId),
    method: 'GET',
    header: jsonHeader(token)
  });
}

/** 放弃上传（清理半成品文件与会话） */
function abortUpload(uploadId, token) {
  if (!uploadId) {
    return Promise.resolve();
  }
  return request({
    url: getBaseUrl() + '/api/video/abort?uploadId=' + encodeURIComponent(uploadId),
    method: 'POST',
    header: formHeader(token)
  });
}

/** 取消上传但不影响主流程（失败只记日志） */
function abortQuietly(uploadId, token) {
  return abortUpload(uploadId, token).then(function () { return null; }, function () { return null; });
}

/**
 * 把临时文件路径规整为「可被 FileSystemManager 按字节读取」的本地路径。
 *
 * 背景：wx.chooseMedia / chooseVideo 返回的 tempFilePath 可能是 http://tmp/xxx 这种
 * 模拟地址（微信开发者工具模拟器必定如此，部分真机版本也会）。
 * 这种地址 wx.uploadFile 能接受，但 FileSystemManager.readFile 读不了（报 not found），
 * 而分片上传必须按字节区间读文件。
 *
 * 这里先用 wx.downloadFile 把它落成一个真实临时文件；转换不成也不报错，
 * 由调用方走「整文件直传」降级兜底。
 */
function materializeLocalPath(filePath) {
  if (!filePath || !/^https?:\/\//i.test(filePath)) {
    log('本地路径可直接读取，无需转换：', filePath);
    return Promise.resolve('');
  }
  log('检测到模拟地址（非真实文件路径），尝试转换：', filePath);
  return new Promise(function (resolve, reject) {
    wx.downloadFile({
      url: filePath,
      success: function (res) {
        if (res.statusCode === 200 && res.tempFilePath) {
          log('模拟地址转换成功：', res.tempFilePath);
          resolve(res.tempFilePath);
        } else {
          log('模拟地址转换失败，statusCode =', res.statusCode);
          reject({ code: -1, msg: '模拟器文件转换失败（HTTP ' + res.statusCode + '）' });
        }
      },
      fail: function (err) {
        log('模拟地址转换失败：', (err && err.errMsg) || err);
        reject({ code: -1, msg: '模拟器文件转换失败：' + ((err && err.errMsg) || '未知原因') });
      }
    });
  });
}

/**
 * 视频分片上传。
 *
 * @param options.topicId        所属答辩题目（必填）
 * @param options.filePath       本地临时文件路径
 * @param options.fileName       文件名（用于取扩展名）
 * @param options.fileSize       文件总字节数
 * @param options.token          登录 token
 * @param options.uploadId       传入则续传该次上传（跳过 init，先查已收分片）
 * @param options.onProgress     进度回调 (percent)
 * @param options.onStage        阶段回调 ('init' | 'uploading' | 'merging')
 * @return Promise，成功得到 { videoUrl, processingId, fileSize, totalParts, uploadId }
 */
function uploadVideo(options) {
  const topicId = options.topicId;
  // 注意：filePath 与 fileSize 在流程中会被重新赋值
  //（路径需替换为规整后的真实路径、fileSize 需按服务端返回校正），
  // 所以必须是 let —— 写成 const 会在赋值时抛 TypeError，页面只能显示"上传失败"。
  let filePath = options.filePath;
  const fileName = options.fileName;
  let fileSize = options.fileSize;
  const token = options.token;
  const onProgress = options.onProgress;
  const onStage = options.onStage;

  let uploadId = options.uploadId || '';
  let chunkSize = 0;
  let totalParts = 0;
  const doneParts = {};

  function notify(percent, stage) {
    if (onStage) {
      onStage(stage);
    }
    if (onProgress) {
      onProgress(percent);
    }
  }

  function receivedCount() {
    return Object.keys(doneParts).length;
  }

  function percent() {
    if (totalParts <= 0) {
      return 0;
    }
    return Math.min(99, Math.floor(receivedCount() / totalParts * 100));
  }

  function initOrResume() {
    if (uploadId) {
      notify(0, 'uploading');
      return queryProgress(uploadId, token).then(function (progress) {
        // 防御：接口若返回空 data，直接取属性会抛 TypeError，页面只会显示"上传失败"
        const info = progress || {};
        totalParts = info.totalParts || 0;
        fileSize = info.fileSize || fileSize;
        (info.receivedParts || []).forEach(function (n) { doneParts[n] = true; });
        notify(percent(), 'uploading');
        return null;
      });
    }
    notify(0, 'init');
    return request({
      url: getBaseUrl() + '/api/video/init',
      method: 'POST',
      header: formHeader(token),
      data: { fileName: fileName, fileSize: fileSize, topicId: topicId }
    }).then(function (data) {
      const info = data || {};
      uploadId = info.uploadId;
      chunkSize = info.chunkSize;
      totalParts = info.totalParts || 0;
      (info.receivedParts || []).forEach(function (n) { doneParts[n] = true; });
      notify(percent(), 'uploading');
      return null;
    });
  }

  function sendPart(partNumber, buffer, attempt) {
    return request({
      url: getBaseUrl() + '/api/video/part?uploadId=' + encodeURIComponent(uploadId)
        + '&partNumber=' + partNumber,
      method: 'POST',
      header: {
        'Authorization': 'Bearer ' + (token || ''),
        'content-type': 'application/octet-stream'
      },
      data: buffer,
      timeout: CHUNK_TIMEOUT
    }).catch(function (err) {
      if (attempt + 1 >= CHUNK_RETRY) {
        err.uploadId = uploadId;
        err.msg = '第 ' + partNumber + '/' + totalParts + ' 片上传失败：' + err.msg;
        throw err;
      }
      // 重试前刷新服务端已收分片，跳过一次已成功的分片
      return queryProgress(uploadId, token).then(function (progress) {
        (progress.receivedParts || []).forEach(function (n) { doneParts[n] = true; });
        return sendPart(partNumber, buffer, attempt + 1);
      }, function () {
        return sendPart(partNumber, buffer, attempt + 1);
      });
    });
  }

  function uploadFrom(partNumber) {
    if (partNumber > totalParts) {
      return Promise.resolve();
    }
    if (doneParts[partNumber]) {
      return uploadFrom(partNumber + 1);
    }
    const offset = (partNumber - 1) * chunkSize;
    const length = Math.min(chunkSize, fileSize - offset);
    return readChunk(filePath, offset, length)
      .then(function (buffer) {
        return sendPart(partNumber, buffer, 0);
      })
      .then(function () {
        doneParts[partNumber] = true;
        notify(percent(), 'uploading');
        return uploadFrom(partNumber + 1);
      });
  }

  return materializeLocalPath(filePath)
    .then(function (localPath) {
      if (localPath) {
        filePath = localPath;
      }
      return initOrResume();
    })
    .then(function () {
      return uploadFrom(1);
    })
    .then(function () {
      notify(100, 'merging');
      return request({
        url: getBaseUrl() + '/api/video/complete?uploadId=' + encodeURIComponent(uploadId),
        method: 'POST',
        header: formHeader(token),
        timeout: CHUNK_TIMEOUT
      });
    })
    .then(function (data) {
      const result = data || {};
      result.uploadId = uploadId;
      return result;
    })
    .catch(function (err) {
      const errMsg = (err && (err.msg || err.errMsg || err.message)) || String(err);
      log('上传中断，原始错误：', errMsg, err);

      // 兜底降级：拿不到可按字节读取的本地路径时，改用整文件直传。
      // 代价是失去「失败续传」能力，但保证功能可用，
      // 否则微信开发者工具模拟器里根本传不了视频。
      if (!/读取本地文件失败|模拟器文件转换失败/.test(errMsg)) {
        // 非"读文件失败"的异常，规整成带原因的错误再抛，页面才不会只显示"上传失败"
        throw normalizeError(err, '视频上传失败');
      }

      log('该环境无法按字节读取文件，降级为整文件直传');
      return abortQuietly(uploadId, token).then(function () {
        notify(0, 'uploading');
        return uploadWholeFile({
          kind: 'video',
          topicId: topicId,
          filePath: filePath,
          token: token,
          onProgress: function (p) {
            notify(p, 'uploading');
          }
        });
      }).then(function (data) {
        log('整文件直传成功（降级模式）');
        return {
          videoUrl: (data && data.url) || '',
          processingId: '',
          fileSize: fileSize,
          totalParts: 1,
          degraded: true
        };
      });
    });
}

/**
 * 单文件直传（答辩报告 / 体积较小的视频）。
 *
 * @param options.kind      'video' | 'report'
 * @param options.topicId   所属答辩题目
 * @param options.filePath  本地临时文件路径
 * @param options.token     登录 token
 * @param options.onProgress 进度回调 (percent)，wx.uploadFile 支持真实进度
 * @return Promise，成功得到 { url, fileName, fileSize, kind }
 */
function uploadWholeFile(options) {
  const kind = options.kind === 'video' ? 'video' : 'report';
  const path = kind === 'video' ? '/api/video/upload' : '/api/report/upload';
  const token = options.token;
  const onProgress = options.onProgress;

  return new Promise(function (resolve, reject) {
    const task = wx.uploadFile({
      url: getBaseUrl() + path,
      filePath: options.filePath,
      name: 'file',
      formData: { topicId: options.topicId },
      header: { 'Authorization': 'Bearer ' + (token || '') },
      timeout: WHOLE_TIMEOUT,
      success: function (res) {
        const parsed = parseResult(res);
        if (parsed.error) {
          reject(parsed.error);
        } else {
          resolve(parsed.data);
        }
      },
      fail: function (err) {
        reject({ code: -1, msg: (err && err.errMsg) || '网络请求失败' });
      }
    });
    if (task && task.onProgressUpdate && onProgress) {
      task.onProgressUpdate(function (e) {
        onProgress(e.progress);
      });
    }
  });
}

/** 查询某个题目下已上传的附件地址 */
function getMediaUrl(kind, topicId, token) {
  const path = kind === 'video' ? '/api/video/url' : '/api/report/url';
  return request({
    url: getBaseUrl() + path + '?topicId=' + encodeURIComponent(topicId),
    method: 'GET',
    header: jsonHeader(token)
  });
}

/** 删除某个题目下已上传的附件（同时清理磁盘文件） */
function deleteMedia(kind, topicId, token) {
  const path = kind === 'video' ? '/api/video/delete' : '/api/report/delete';
  return request({
    url: getBaseUrl() + path + '?topicId=' + encodeURIComponent(topicId),
    method: 'POST',
    header: formHeader(token)
  });
}

/** 查询视频后处理状态 */
function getProcessingStatus(processingId, token) {
  return request({
    url: getBaseUrl() + '/api/video/processing-status?processingId=' + encodeURIComponent(processingId),
    method: 'GET',
    header: jsonHeader(token)
  });
}

/** 从文件名取小写扩展名（不含点） */
function extensionOf(fileName) {
  if (!fileName) {
    return '';
  }
  const dot = fileName.lastIndexOf('.');
  if (dot <= 0 || dot >= fileName.length - 1) {
    return '';
  }
  return fileName.substring(dot + 1).toLowerCase();
}

/** 可读文件大小 */
function readableSize(size) {
  if (size < 1024) {
    return size + ' B';
  }
  if (size < 1024 * 1024) {
    return (size / 1024).toFixed(1) + ' KB';
  }
  if (size < 1024 * 1024 * 1024) {
    return (size / (1024 * 1024)).toFixed(1) + ' MB';
  }
  return (size / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

module.exports = {
  getBaseUrl: getBaseUrl,
  resolveFileUrl: resolveFileUrl,
  fetchPolicy: fetchPolicy,
  uploadVideo: uploadVideo,
  uploadWholeFile: uploadWholeFile,
  abortUpload: abortUpload,
  queryProgress: queryProgress,
  getMediaUrl: getMediaUrl,
  deleteMedia: deleteMedia,
  getProcessingStatus: getProcessingStatus,
  extensionOf: extensionOf,
  readableSize: readableSize
};
