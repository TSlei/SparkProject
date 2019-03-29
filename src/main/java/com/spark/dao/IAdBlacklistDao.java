package com.spark.dao;

import java.util.List;

import com.spark.model.AdBlacklist;

/**
 * 广告黑名单Dao接口
 *
 */
public interface IAdBlacklistDao {

	/**
	 * 批量插入广告黑名单用户
	 * @param adBlacklists
	 */
	void insertBatch(List<AdBlacklist> adBlacklists);
	
	/**
	 * 查询所有广告黑名单用户
	 * @return
	 */
	List<AdBlacklist> findAll();
	
}
