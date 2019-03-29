package com.spark.dao;

import com.spark.model.SessionRandomExtract;

/**
 * session随机抽取模块Dao接口
 *
 */
public interface ISessionRandomExtractDao {

	/**
	 * 插入session随机抽取
	 * @param sessionAggrStat 
	 */
	void insert(SessionRandomExtract sessionRandomExtract);
	
}
