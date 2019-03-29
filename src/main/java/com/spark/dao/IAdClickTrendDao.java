package com.spark.dao;

import java.util.List;

import com.spark.model.AdClickTrend;

/**
 * 广告点击趋势Dao接口
 *
 */
public interface IAdClickTrendDao {

	void updateBatch(List<AdClickTrend> adClickTrends);
	
}
