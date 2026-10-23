package com.uav.inspection.identity.persistence;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Task membership is independent of organization administration. */
public interface TaskScopeMapper {
    @Select("SELECT COUNT(*) FROM task_member WHERE task_id = #{taskId} AND user_id = #{userId}")
    int countMembership(@Param("taskId") String taskId, @Param("userId") String userId);
}
