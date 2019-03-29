package com.spark.dao.impl;

import com.spark.dao.IPageSplitConvertRateDao;
import com.spark.jdbc.JDBCHelper;
import com.spark.model.PageSplitConvertRate;

/**
 * 页面切片转化率Dao实现类
 *
 */
public class PageSplitConvertRateDaoImpl implements IPageSplitConvertRateDao {

	@Override
	public void insert(PageSplitConvertRate pageSplitConvertRate) {
		String sql = "insert into page_split_convert_rate values(?,?)";  
		Object[] params = new Object[]{pageSplitConvertRate.getTaskId(), 
				pageSplitConvertRate.getConvertRate()};
		
		JDBCHelper jdbcHelper = JDBCHelper.getInstance();
		jdbcHelper.executeUpdate(sql, params);
	}

}
