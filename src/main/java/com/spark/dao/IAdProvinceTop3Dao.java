package com.spark.dao;

import java.util.List;

import com.spark.model.AdProvinceTop3;

/**
 * 各省份top3热门广告Dao接口
 *
 */
public interface IAdProvinceTop3Dao {

	void updateBatch(List<AdProvinceTop3> adProvinceTop3s);
	
}
