package com.spark.dao.factory;

import com.spark.dao.IAdBlacklistDao;
import com.spark.dao.IAdClickTrendDao;
import com.spark.dao.IAdProvinceTop3Dao;
import com.spark.dao.IAdStatDao;
import com.spark.dao.IAdUserClickCountDao;
import com.spark.dao.IAreaTop3ProductDao;
import com.spark.dao.IPageSplitConvertRateDao;
import com.spark.dao.ISessionAggrStatDao;
import com.spark.dao.ISessionDetailDao;
import com.spark.dao.ISessionRandomExtractDao;
import com.spark.dao.ITaskDao;
import com.spark.dao.ITop10CategoryDao;
import com.spark.dao.ITop10SessionDao;
import com.spark.dao.impl.AdBlacklistDaoImpl;
import com.spark.dao.impl.AdClickTrendDaoImpl;
import com.spark.dao.impl.AdProvinceTop3DaoImpl;
import com.spark.dao.impl.AdStatDaoImpl;
import com.spark.dao.impl.AdUserClickCountDaoImpl;
import com.spark.dao.impl.AreaTop3ProductDaoImpl;
import com.spark.dao.impl.PageSplitConvertRateDaoImpl;
import com.spark.dao.impl.SessionAggrStatDaoImpl;
import com.spark.dao.impl.SessionDetailDaoImpl;
import com.spark.dao.impl.SessionRandomExtractDaoImpl;
import com.spark.dao.impl.TaskDaoImpl;
import com.spark.dao.impl.Top10CategoryDaoImpl;
import com.spark.dao.impl.Top10SessionDaoImpl;

/**
 * Dao工厂类
 */
public class DaoFactory {

	public static ITaskDao getTaskDao() {
		return new TaskDaoImpl();
	}

	public static ISessionAggrStatDao getSessionAggrStatDao() {
		return new SessionAggrStatDaoImpl();
	}
	
	public static ISessionRandomExtractDao getSessionRandomExtractDao() {
		return new SessionRandomExtractDaoImpl();
	}
	
	public static ISessionDetailDao getSessionDetailDao() {
		return new SessionDetailDaoImpl();
	}
	
	public static ITop10CategoryDao getTop10CategoryDao() {
		return new Top10CategoryDaoImpl();
	}
	
	public static ITop10SessionDao getTop10SessionDao() {
		return new Top10SessionDaoImpl();
	}
	
	public static IPageSplitConvertRateDao getPageSplitConvertRateDao() {
		return new PageSplitConvertRateDaoImpl();
	}
	
	public static IAreaTop3ProductDao getAreaTop3ProductDao() {
		return new AreaTop3ProductDaoImpl();
	}
	
	public static IAdUserClickCountDao getAdUserClickCountDao() {
		return new AdUserClickCountDaoImpl();
	}
	
	public static IAdBlacklistDao getAdBlacklistDao() {
		return new AdBlacklistDaoImpl();
	}
	
	public static IAdStatDao getAdStatDao() {
		return new AdStatDaoImpl();
	}
	
	public static IAdProvinceTop3Dao getAdProvinceTop3Dao() {
		return new AdProvinceTop3DaoImpl();
	}
	
	public static IAdClickTrendDao getAdClickTrendDao() {
		return new AdClickTrendDaoImpl();
	}
	
}
