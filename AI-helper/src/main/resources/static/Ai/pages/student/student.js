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
        'Authorization': 'Bearer ' + this.data.token
      },
      success: function(res) {
        console.log('初始化上传响应:', res);
        
        if (res.statusCode === 200) {
          // 解析uploadId和fileName
          const responseText = res.data;
          const uploadIdMatch = responseText.match(/uploadId=([^&]+)/);
          const fileNameMatch = responseText.match(/fileName=([^&]*)/);
          
          if (uploadIdMatch && fileNameMatch) {
            const uploadId = uploadIdMatch[1];
            const uniqueFileName = fileNameMatch[1];
            
            that.setData({
              currentUploadId: uploadId,
              currentFileName: uniqueFileName
            });
            
            // 计算分片数量（每片20MB）
            const PART_SIZE = 20 * 1024 * 1024; // 20MB
            const totalChunks = Math.ceil(fileSize / PART_SIZE);
            
            that.setData({
              currentTotalChunks: totalChunks,
              uploadStatus: 'uploading'
            });
            
            // 开始上传第一个分片
            that.uploadChunk(videoPath, uploadId, uniqueFileName, 1, totalChunks);
          } else {
            that.handleUploadError('初始化失败：无法解析uploadId');
          }
        } else {
          // 增强错误处理，显示具体的错误信息
          let errorMessage = '初始化失败：';
          if (res.data && res.data.msg) {
            errorMessage += res.data.msg;
          } else if (res.data && typeof res.data === 'string') {
            errorMessage += res.data;
          } else {
            errorMessage += '服务器返回错误，状态码：' + res.statusCode;
          }
          
          that.handleUploadError(errorMessage);
        }
      },
      fail: function(err) {
        console.error('初始化上传失败:', err);
        // 显示更详细的错误信息
        let errorMessage = '初始化失败：网络错误';
        if (err.errMsg) {
          errorMessage = '初始化失败：' + err.errMsg;
        }
        that.handleUploadError(errorMessage);
      }
    });
  },