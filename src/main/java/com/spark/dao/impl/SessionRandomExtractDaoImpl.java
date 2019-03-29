package com.spark.dao.impl;

import com.spark.dao.ISessionRandomExtractDao;
import com.spark.jdbc.JDBCHelper;
import com.spark.model.SessionRandomExtract;

/**
 * 随机抽取session的Dao实现
 *
 */
public class SessionRandomExtractDaoImpl implements ISessionRandomExtractDao {

	/**
	 * 插入session随机抽取
	 * @param sessionAggrStat 
	 */
	public void insert(SessionRandomExtract sessionRandomExtract) {
		String sql = "insert into session_random_extract values(?,?,?,?,?)";
		
		Object[] params = new Object[]{sessionRandomExtract.getTaskid(),
				sessionRandomExtract.getSessionid(),
				sessionRandomExtract.getStartTime(),
				sessionRandomExtract.getSearchKeywords(),
				sessionRandomExtract.getClickCategoryIds()};
		
		JDBCHelper jdbcHelper = JDBCHelper.getInstance();
		jdbcHelper.executeUpdate(sql, params);
	}
	
}
