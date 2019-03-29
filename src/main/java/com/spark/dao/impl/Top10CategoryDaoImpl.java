package com.spark.dao.impl;

import com.spark.dao.ITop10CategoryDao;
import com.spark.jdbc.JDBCHelper;
import com.spark.model.Top10Category;

/**
 * top10品类DAO实现
 *
 */
public class Top10CategoryDaoImpl implements ITop10CategoryDao {

	@Override
	public void insert(Top10Category category) {
		String sql = "insert into top10_category values(?,?,?,?,?)";  
		
		Object[] params = new Object[]{category.getTaskid(),
				category.getCategoryid(),
				category.getClickCount(),
				category.getOrderCount(),
				category.getPayCount()};  
		
		JDBCHelper jdbcHelper = JDBCHelper.getInstance();
		jdbcHelper.executeUpdate(sql, params);
	}

}
