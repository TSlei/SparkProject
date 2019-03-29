package com.spark.dao;

import com.spark.model.Task;

/**
 * 任务管理Dao接口
 *
 */
public interface ITaskDao {
	
	/**
	 * 根据主键查询任务
	 * @param taskid 主键
	 * @return 任务
	 */
	Task findById(long taskid);
	
}
