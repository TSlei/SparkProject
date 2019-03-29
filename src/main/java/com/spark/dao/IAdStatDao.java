package com.spark.dao;

import java.util.List;

import com.spark.model.AdStat;

/**
 * 广告实时统计Dao接口
 *
 */
public interface IAdStatDao {

	void updateBatch(List<AdStat> adStats);
	
}
