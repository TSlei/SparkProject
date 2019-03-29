package com.spark.dao.impl;

import java.sql.ResultSet;

import com.spark.dao.ITaskDao;
import com.spark.jdbc.JDBCHelper;
import com.spark.model.Task;

/**
 * 任务管理Dao实现类
 *
 */
public class TaskDaoImpl implements ITaskDao {

	/**
	 * 根据主键查询任务
	 * @param taskId 主键
	 * @return 任务
	 */
	public Task findById(long taskId) {
		final Task task = new Task();
		
		String sql = "select * from task where task_id=?";
		Object[] params = new Object[]{taskId};
		
		JDBCHelper jdbcHelper = JDBCHelper.getInstance();
		jdbcHelper.executeQuery(sql, params, new JDBCHelper.QueryCallback() {
			
			@Override
			public void process(ResultSet rs) throws Exception {
				if(rs.next()) {
					long taskId = rs.getLong(1);
					String taskName = rs.getString(2);
					String createTime = rs.getString(3);
					String startTime = rs.getString(4);
					String finishTime = rs.getString(5);
					String taskType = rs.getString(6);
					String taskStatus = rs.getString(7);
					String taskParam = rs.getString(8);
					
					task.setTaskId(taskId);
					task.setTaskName(taskName); 
					task.setCreateTime(createTime); 
					task.setStartTime(startTime);
					task.setFinishTime(finishTime);
					task.setTaskType(taskType);  
					task.setTaskStatus(taskStatus);
					task.setTaskParam(taskParam);  
				}
			}
			
		});
		
		return task;
	}
	
}
