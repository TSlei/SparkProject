package com.spark.dao;

import com.spark.model.Top10Session;

/**
 * top10活跃session的DAO接口
 *
 */
public interface ITop10SessionDao {

	void insert(Top10Session top10Session);
	
}
