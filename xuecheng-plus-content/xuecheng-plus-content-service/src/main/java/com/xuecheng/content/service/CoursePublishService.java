package com.xuecheng.content.service;

import com.xuecheng.content.model.dto.CoursePreviewDto;

/**
 * @author Yang Hui
 * @version 1.0
 * @description 课程预览、发布接口
 * @date 2023/5/9 16:48
 */

public interface CoursePublishService {
    /**
     * @description 课程预览
     * @param courseId
     * @return com.xuecheng.content.model.dto.CoursePreviewDto
     */
    public CoursePreviewDto getCoursePreviewInfo(Long courseId);
}
