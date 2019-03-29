package com.spark.dao;

import java.util.List;

import com.spark.model.AreaTop3Product;

/**
 * 各区域top3热门商品Dao接口
 *
 */
public interface IAreaTop3ProductDao {

	void insertBatch(List<AreaTop3Product> areaTopsProducts);
	
}
