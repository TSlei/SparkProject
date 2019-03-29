package com.spark.dao.impl;

import com.spark.dao.ITop10SessionDao;
import com.spark.jdbc.JDBCHelper;
import com.spark.model.Top10Session;

/**
 * top10活跃session的DAO实现
 *
 */
public class Top10SessionDaoImpl implements ITop10SessionDao {

	@Override
	public void insert(Top10Session top10Session) {
		String sql = "insert into top10_session values(?,?,?,?)"; 
		
		Object[] params = new Object[]{top10Session.getTaskId(),
				top10Session.getCategoryId(),
				top10Session.getSessionId(),
				top10Session.getClickCount()};
		
		JDBCHelper jdbcHelper = JDBCHelper.getInstance();
		jdbcHelper.executeUpdate(sql, params);
	}

}
