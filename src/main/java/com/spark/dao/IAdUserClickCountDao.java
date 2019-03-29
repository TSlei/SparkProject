package com.spark.dao;

import java.util.List;

import com.spark.model.AdUserClickCount;

/**
 * 用户广告点击量Dao接口
 *
 */
public interface IAdUserClickCountDao {

	/**
	 * 批量更新用户广告点击量
	 * @param adUserClickCounts
	 */
	void updateBatch(List<AdUserClickCount> adUserClickCounts);
	
	/**
	 * 根据多个key查询用户广告点击量
	 * @param date 日期
	 * @param userId 用户id
	 * @param adId 广告id
	 * @return
	 */
	int findClickCountByMultiKey(String date, long userId, long adId);
	
}
