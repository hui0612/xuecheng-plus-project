package com.xuecheng.media.service.jobhandler;

import com.xuecheng.base.utils.Mp4VideoUtil;
import com.xuecheng.media.model.po.MediaProcess;
import com.xuecheng.media.service.MediaFileProcessService;
import com.xuecheng.media.service.MediaFileService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @description: 任务处理类
 */
@Component
@Slf4j
public class VideoTask {

    @Autowired
    private MediaFileProcessService mediaFileProcessService;
    @Autowired
    private MediaFileService mediaFileService;

    @Value("${videoprocess.ffmpegpath}")
    private String ffmpegpath;

    /**
     * 视频处理任务
     */
    @XxlJob("videoJobHander")
    public void shardingJobHandler() throws Exception {
        // 分片参数
        int shardIndex = XxlJobHelper.getShardIndex(); //执行器的序号从0开始
        int shardTotal = XxlJobHelper.getShardTotal();//执行器的总数
        //确定cpu的核心数
        int cpuCore = Runtime.getRuntime().availableProcessors();
        //1查询业务
        List<MediaProcess> mediaProcessList = mediaFileProcessService.getMediaProcessList(shardIndex, shardTotal, cpuCore);
        //确定任务数量
        int taskCount = mediaProcessList.size();
        if (taskCount == 0) {
            log.info("没有任务需要执行");
            return;
        }
        //使用计数器
        CountDownLatch countDownLatch = new CountDownLatch(taskCount);
        //创建线程池
        ExecutorService executorService = Executors.newFixedThreadPool(taskCount);
        for (MediaProcess mediaProcess : mediaProcessList) {
            executorService.execute(() -> {
                try {
                    //获取文件的id
                    String fileId = mediaProcess.getFileId();

                    Long taskId = mediaProcess.getId();
                    //2开启任务
                    boolean b = mediaFileProcessService.starTask(taskId);
                    if (!b) {
                        log.error("任务开启失败，taskId:{}", taskId);
                        return;
                    }
                    //3执行任务转码

                    //下载视频到本地
                    //获取桶
                    String bucket = mediaProcess.getBucket();
                    //获取文件名称
                    String filePath = mediaProcess.getFilePath();
                    File file = null;
                    try {
                       file = mediaFileService.downloadFileFromMinIO(bucket, filePath);
                    }catch (Exception e){
                        log.error("下载文件失败，taskId:{},bucket:{},filePath:{}", taskId, bucket, filePath);
                        mediaFileProcessService.saveProcessFinishStatus(taskId, "3", fileId, null, "下载文件失败");
                        return;
                    }
                    if (file == null) {
                        log.error("下载文件失败，taskId:{},bucket:{},filePath:{}", taskId, bucket, filePath);
                        mediaFileProcessService.saveProcessFinishStatus(taskId, "3", fileId, null, "下载文件失败");
                        return;
                    }

                    //源avi视频的路径
                    String video_path = file.getAbsolutePath();
                    //转换后mp4文件的名称
                    String mp4_name = fileId + ".mp4";

                    //url
                    String filePath1 = getFilePath(fileId, ".mp4");
                    //先创建一个临时文件，用于存放转换后的mp4文件
                    File mp4File = null;
                    try {
                        mp4File = File.createTempFile("minio", ".mp4");
                    } catch (IOException e) {
                        log.error("创建临时文件失败:{}", e.getMessage());
                        mediaFileProcessService.saveProcessFinishStatus(taskId, "3", fileId, null, "创建临时文件失败");
                        return;
                    }
                    //转换后mp4文件的路径
                    String mp4_path = mp4File.getAbsolutePath();
                    //创建工具类对象
                    Mp4VideoUtil videoUtil = new Mp4VideoUtil(ffmpegpath, video_path, mp4_name, mp4_path);
                    //开始视频转换，成功将返回success
                    String result = videoUtil.generateMp4();
                    if (!"success".equals(result)) {
                        log.error("视频转换失败，taskId:{}", taskId);
                        mediaFileProcessService.saveProcessFinishStatus(taskId, "3", fileId, null, result);
                        return;
                    }
                    //4上传minio
                    boolean b1 = mediaFileService.addMediaFilesToMinIO(mp4File.getAbsolutePath(), "video/mp4", bucket, filePath1);
                    if (!b1) {
                        log.error("上传minio失败，taskId:{}", taskId);
                        mediaFileProcessService.saveProcessFinishStatus(taskId, "3", fileId, null, "上传minio失败");
                        return;
                    }

                    //5更新任务状态
                    mediaFileProcessService.saveProcessFinishStatus(taskId, "2", fileId, filePath1, null);

                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        //阻塞等待任务执行完成
        countDownLatch.await(30, TimeUnit.MINUTES);
    }
    private String getFilePath(String fileMd5, String fileExt) {
        return fileMd5.charAt(0) + "/" + fileMd5.charAt(1) + "/" + fileMd5 + "/" + fileMd5 + fileExt;
    }



}
