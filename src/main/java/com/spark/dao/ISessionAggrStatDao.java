package com.spark.dao;

import com.spark.model.SessionAggrStat;

/**
 * session聚合统计模块Dao接口
 *
 */
public interface ISessionAggrStatDao {

	/**
	 * 插入session聚合统计结果
	 * @param sessionAggrStat 
	 */
	void insert(SessionAggrStat sessionAggrStat);
	
}
