package com.spark.dao;

import java.util.List;

import com.spark.model.SessionDetail;

/**
 * Session明细Dao接口
 *
 */
public interface ISessionDetailDao {

	/**
	 * 插入一条session明细数据
	 * @param sessionDetail 
	 */
	void insert(SessionDetail sessionDetail);
	
	/**
	 * 批量插入session明细数据
	 * @param sessionDetails
	 */
	void insertBatch(List<SessionDetail> sessionDetails);
	
}
