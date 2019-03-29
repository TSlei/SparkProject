package com.spark.service.session;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.spark.Accumulator;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.hive.HiveContext;
import org.apache.spark.storage.StorageLevel;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Optional;
import com.spark.conf.ConfigurationManager;
import com.spark.constant.Constants;
import com.spark.dao.ISessionAggrStatDao;
import com.spark.dao.ISessionDetailDao;
import com.spark.dao.ISessionRandomExtractDao;
import com.spark.dao.ITaskDao;
import com.spark.dao.ITop10CategoryDao;
import com.spark.dao.ITop10SessionDao;
import com.spark.dao.factory.DaoFactory;
import com.spark.model.SessionAggrStat;
import com.spark.model.SessionDetail;
import com.spark.model.SessionRandomExtract;
import com.spark.model.Task;
import com.spark.model.Top10Category;
import com.spark.model.Top10Session;
import com.spark.utils.DateUtils;
import com.spark.utils.NumberUtils;
import com.spark.utils.ParamUtils;
import com.spark.utils.SparkUtils;
import com.spark.utils.StringUtils;
import com.spark.utils.ValidUtils;

import scala.Tuple2;

/**
 * 用户访问session分析Spark作业
 * 
 * 接收用户创建的分析任务，用户可能指定的条件如下：
 * 
 * 1、时间范围：起始日期~结束日期 2、性别：男或女 3、年龄范围 4、职业：多选 5、城市：多选
 * 6、搜索词：多个搜索词，只要某个session中的任何一个action搜索过指定的关键词，那么session就符合条件
 * 7、点击品类：多个品类，只要某个session中的任何一个action点击过某个品类，那么session就符合条件
 * 
 * 我们的spark作业如何接受用户创建的任务？
 * 
 * J2EE平台在接收用户创建任务的请求之后，会将任务信息插入MySQL的task表中，任务参数以JSON格式封装在task_param 字段中
 * 
 * 接着J2EE平台会执行我们的spark-submit shell脚本，并将taskid作为参数传递给spark-submit shell脚本
 * spark-submit shell脚本，在执行时，是可以接收参数的，并且会将接收的参数，传递给Spark作业的main函数
 * 参数就封装在main函数的args数组中
 * 
 * 这是spark本身提供的特性
 * 
 */
public class UserVisitSessionAnalyzeSpark {

	public static void main(String[] args) {

		// 构建Spark上下文
		SparkConf conf = new SparkConf()
				.setAppName(Constants.SPARK_APP_NAME_SESSION)
//				.set("spark.default.parallelism", "100")
				.set("spark.storage.memoryFraction", "0.5")  
				.set("spark.shuffle.consolidateFiles", "true")
				.set("spark.shuffle.file.buffer", "64")  
				.set("spark.shuffle.memoryFraction", "0.3")    
				.set("spark.reducer.maxSizeInFlight", "24")  
				.set("spark.shuffle.io.maxRetries", "60")  
				.set("spark.shuffle.io.retryWait", "60")   
				.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
				.registerKryoClasses(new Class[]{
						CategorySortKey.class
//						,IntList.class
						});
		SparkUtils.setMaster(conf); 
		/**
		 * 比如，获取top10热门品类功能中，二次排序，自定义了一个key
		 * 这个key是需要在进行shuffle的时候，进行网络传输的，因此也是需要实现序列化的
		 * 启用Kryo机制以后，就会用Kryo去序列化和反序列化CategorySortKey
		 * 所以这里要求，为了获取最佳性能，需要注册我们自定义的类
		 */
		
		JavaSparkContext sc = new JavaSparkContext(conf);
//		sc.checkpointFile("hdfs://");
		SQLContext sqlContext = getSQLContext(sc.sc());
		
		// 生成模拟测试数据
		SparkUtils.mockData(sc, sqlContext);

		// 创建需要使用的DAO组件
		ITaskDao taskDao = DaoFactory.getTaskDao();

		// 首先从user_visit_action表中查询出数据
		long taskId = ParamUtils.getTaskIdFromArgs(args, Constants.SPARK_LOCAL_TASKID_SESSION);
		Task task = taskDao.findById(taskId);

		if (task == null) {
			System.out.println(new Date() + ": cannot find this task with id [" + taskId + "].");
			return;
		}

		JSONObject taskParam = JSONObject.parseObject(task.getTaskParam());

		/**
		 * actionRDD,就是一个公共RDD
		 */
		JavaRDD<Row> actionRDD = SparkUtils.getActionRDDByDateRange(sqlContext, taskParam);
		
		// sessionId2ActionRDD = <sessionId,row>
		JavaPairRDD<String, Row> sessionId2ActionRDD = getSessionId2ActionRDD(actionRDD);
		
		/**
		 * 持久化,就是对RDD调用persist()方法,并传入一个持久化级别
		 * 如果是StorageLevel.MEMORY_ONLY(),纯内存，无序列号，那么就可以用cache()方法替代
		 * StorageLevel.MEMORY_ONLY_SER(),第二选择,序列化
		 * StorageLevel.MEMORY_AND_DISK(),第三选择,无序列化
		 * StorageLevel.MEMORY_AND_DISK_SER(),第四选择,序列化
		 * StorageLevel.MEMORY_DISK_ONLY(),第五选择,纯磁盘
		 * 
		 * 如果内存充足，要使用双副本高可靠性机制
		 * 选择后缀带_2的策略,数据会保存2分
		 * StorageLevel.MEMORY_ONLY_2()
		 * 
		 */
		sessionId2ActionRDD = sessionId2ActionRDD.persist(StorageLevel.MEMORY_ONLY());
//		sessionId2ActionRDD.checkpoint();
		
		// 将行为数据按照session_id进行groupByKey分组,并将session粒度的数据与用户信息数据进行join
		// 这样就可以获取到session粒度的数据，同时里面还包含了session对应的user的信息
		JavaPairRDD<String, String> sessionId2AggrInfoRDD = aggregateBySession(sc, sqlContext, sessionId2ActionRDD);

		System.out.println("filterSessionAndAggrStat--start-----");
		for (Tuple2<String, String> tuple : sessionId2AggrInfoRDD.take(10)) {
			System.out.println(tuple._2);
		}
		System.out.println("filterSessionAndAggrStat--end-----");

		// 重构,同时进行过滤和统计
		Accumulator<String> sessionAggrStatAccumulator = sc.accumulator("", new SessionAggrStatAccumulator());

		// 针对session粒度的聚合数据,根据指定条件进行筛选
		//filteredSessionId2AggrInfoRDD = <sessionId, fullInfo>
		JavaPairRDD<String, String> filteredSessionId2AggrInfoRDD = filterSessionAndAggrStat(sessionId2AggrInfoRDD,
				taskParam, sessionAggrStatAccumulator);
		
		filteredSessionId2AggrInfoRDD = filteredSessionId2AggrInfoRDD.persist(StorageLevel.MEMORY_ONLY());
		
		// 生成公共的RDD：通过筛选条件的session的访问明细数据
		
		/**
		 * 重构：sessionid2detailRDD，就是代表了通过筛选的session对应的访问明细数据
		 */
		JavaPairRDD<String, Row> sessionId2DetailRDD = getSessionId2DetailRDD(
				filteredSessionId2AggrInfoRDD, sessionId2ActionRDD);
		
		sessionId2DetailRDD = sessionId2DetailRDD.persist(StorageLevel.MEMORY_ONLY());
		
		/**
		 * 对于Accumulator这种分布式累加计算的变量的使用，有一个重要说明
		 * 
		 * 从Accumulator中，获取数据，插入数据库的时候，一定要在有某一个action操作以后再进行。。。
		 * 
		 * 如果没有action的话，那么整个程序根本不会运行。。。
		 * 
		 * 是不是在calculateAndPersisitAggrStat方法之后，运行一个action操作，比如count、take
		 * 不对！！！
		 * 
		 * 必须把能够触发job执行的操作，放在最终写入MySQL方法之前
		 * 
		 * 计算出来的结果，在J2EE中，是怎么显示的，是用两张柱状图显示
		 */
		
		System.out.println(filteredSessionId2AggrInfoRDD.count());
		
		randomExtractSession(sc, task.getTaskId(), filteredSessionId2AggrInfoRDD, sessionId2DetailRDD);
		
		// 计算出各个范围的session占比，写入mysql
		calculateAndPersistAggrStat(sessionAggrStatAccumulator.value(), task.getTaskId());

		/**
		 * session聚合统计(统计出访问时长和访问步长，各个区间的session数量占总session数量的比例)
		 * 
		 * 如果不进行重构，直接来实现： 1、actionRDD，映射成<sessionid,Row>的格式
		 * 2、按sessionid聚合，计算出每个session的访问时长和访问步长，生成一个新的RDD
		 * 3、遍历新生成的RDD，将每个session的访问时长和访问步长，去更新自定义Accumulator中的对应的值
		 * 4、使用自定义Accumulator中的统计值，去计算各个区间的比例 5、将最后计算出来的结果，写入MySQL对应的表中
		 * 
		 * 普通实现思路的问题： 1、为什么还要用actionRDD，去映射？其实我们之前在session聚合的时候，映射已经做过了。多此一举
		 * 2、是不是一定要，为了session的聚合这个功能，单独去遍历一遍session？其实没有必要，已经有session数据
		 * 之前过滤session的时候，其实，就相当于，是在遍历session，那么这里就没有必要再过滤一遍了
		 * 
		 * 重构实现思路： 1、不要去生成任何新的RDD（处理上亿的数据） 2、不要去单独遍历一遍session的数据（处理上千万的数据）
		 * 3、可以在进行session聚合的时候，就直接计算出来每个session的访问时长和访问步长
		 * 4、在进行过滤的时候，本来就要遍历所有的聚合session信息，此时，就可以在某个session通过筛选条件后
		 * 将其访问时长和访问步长，累加到自定义的Accumulator上面去
		 * 5、就是两种截然不同的思考方式，和实现方式，在面对上亿，上千万数据的时候，甚至可以节省时间长达 半个小时，或者数个小时
		 * 
		 * 我们如果采用第一种实现方案，那么其实就是代码划分（解耦合、可维护）优先，设计优先。 如果采用第二种方案，那么其实就是性能优先。
		 * 
		 */

//		for (Tuple2<String, String> tuple : filteredSessionId2AggrInfoRDD.take(10)) {
//			System.out.println(tuple._2);
//		}
		
		// 获取top10热门品类
		List<Tuple2<CategorySortKey, String>> top10CategoryList = getTop10Category(task.getTaskId(), sessionId2DetailRDD);
		
		// 获取top10活跃session
		getTop10Session(sc, task.getTaskId(), top10CategoryList, sessionId2DetailRDD);

		// 关闭Spark上下文
		sc.close();
	}

	/**
	 * 获取SQLContext 如果是在本地测试环境的话，那么就生成SQLContext对象
	 * 如果是在生产环境运行的话，那么就生成HiveContext对象
	 * 
	 * @param sc
	 *            SparkContext
	 * @return SQLContext
	 */
	private static SQLContext getSQLContext(SparkContext sc) {
		boolean local = ConfigurationManager.getBoolean(Constants.SPARK_LOCAL);
		if (local) {
			return new SQLContext(sc);
		} else {
			return new HiveContext(sc);
		}
	}

	/**
	 * 获取sessionid到访问行为数据的映射的RDD
	 * @param actionRDD 
	 * @return
	 */
	public static JavaPairRDD<String, Row> getSessionId2ActionRDD(JavaRDD<Row> actionRDD) {
//		return actionRDD.mapToPair(new PairFunction<Row, String, Row>() {
//
//			private static final long serialVersionUID = 1L;
//			
//			@Override
//			public Tuple2<String, Row> call(Row row) throws Exception {
//				return new Tuple2<String, Row>(row.getString(2), row);  
//			}
//			
//		});
		
		// 根据每个partition去处理
		return actionRDD.mapPartitionsToPair(new PairFlatMapFunction<Iterator<Row>, String, Row>() {

			private static final long serialVersionUID = 1L;

			@Override
			public Iterable<Tuple2<String, Row>> call(Iterator<Row> iterator)
					throws Exception {
				List<Tuple2<String, Row>> list = new ArrayList<Tuple2<String, Row>>();
				
				while(iterator.hasNext()) {
					Row row = iterator.next();
					list.add(new Tuple2<String, Row>(row.getString(2), row));  
				}
				System.out.println("row = " + list.get(1)._2);
				return list;
			}
			
		});
	}

	/**
	 * 对行为数据按session粒度进行聚合
	 * 
	 * @param actionRDD
	 * @return session粒度聚合数据
	 */
	private static JavaPairRDD<String, String> aggregateBySession(
			JavaSparkContext sc, SQLContext sqlContext, JavaPairRDD<String, Row> sessionId2ActionRDD) {
		
		// 对行为数据按session粒度进行分组
		JavaPairRDD<String, Iterable<Row>> sessionId2ActionsRDD = sessionId2ActionRDD.groupByKey();

		// 对每一个session分组进行聚合，将session中所有的搜索词和点击品类都聚合起来
		JavaPairRDD<Long, String> userId2PartAggrInfoRDD = sessionId2ActionsRDD
				.mapToPair(new PairFunction<Tuple2<String, Iterable<Row>>, Long, String>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Tuple2<Long, String> call(Tuple2<String, Iterable<Row>> tuple) throws Exception {
						String sessionId = tuple._1;
						Iterator<Row> iterable = tuple._2.iterator();

						StringBuffer searchKeywordsBuffer = new StringBuffer("");
						StringBuffer clickCategoryIdsBuffer = new StringBuffer("");

						Long userId = null;

						Date startTime = null;
						Date endTime = null;
						// session的访问步长
						int stepLength = 0;

						// 遍历session所有的访问行为
						while (iterable.hasNext()) {
							// 提取每个访问行为的搜索词字段和点击品类字段
							Row row = iterable.next();
							if (userId == null) {
								userId = row.getLong(1);
							}
							String searchKeyword = row.getString(5);
							Long clickCategoryId = row.getLong(6);

							if (StringUtils.isNotEmpty(searchKeyword)) {
								if (!searchKeywordsBuffer.toString().contains(searchKeyword)) {
									searchKeywordsBuffer.append(searchKeyword + ",");
								}
							}
							if (clickCategoryId != null) {
								if (!clickCategoryIdsBuffer.toString().contains(String.valueOf(clickCategoryId))) {
									clickCategoryIdsBuffer.append(clickCategoryId + ",");
								}
							}

							// 计算session开始和结束时间
							Date actionTime = DateUtils.parseTime(row.getString(4));
							if (startTime == null) {
								startTime = actionTime;
							}
							if (endTime == null) {
								endTime = actionTime;
							}
							if (actionTime.before(startTime)) {
								startTime = actionTime;
							}
							if (actionTime.after(endTime)) {
								endTime = actionTime;
							}

							// 计算session访问步长
							stepLength++;
						}

						String searchKeywords = StringUtils.trimComma(searchKeywordsBuffer.toString());
						String clickCategoryIds = StringUtils.trimComma(clickCategoryIdsBuffer.toString());

						// 计算session访问时长(s)
						long visitLength = (endTime.getTime() - startTime.getTime()) / 1000;

						String partAggrInfo = Constants.FIELD_SESSION_ID + "=" + sessionId + "|"
								+ Constants.FIELD_SEARCH_KEYWORDS + "=" + searchKeywords + "|"
								+ Constants.FIELD_CLICK_CATEGORY_IDS + "=" + clickCategoryIds + "|"
								+ Constants.FIELD_VISIT_LENGTH + "=" + visitLength + "|"
								+ Constants.FIELD_STEP_LENGTH + "=" + stepLength + "|"
								+ Constants.FIELD_START_TIME + "=" + DateUtils.formatTime(startTime); 

						// 返回<userId,Row>数据格式方便与用户信息进行聚合
						return new Tuple2<Long, String>(userId, partAggrInfo);
					}
				});

		// 查询所有用户数据,并映射成<userId,Row>的格式
		String sql = "select * from user_info";
		JavaRDD<Row> userInfoRDD = sqlContext.sql(sql).javaRDD();
		JavaPairRDD<Long, Row> userId2InfoRDD = userInfoRDD.mapToPair(
				new PairFunction<Row, Long, Row>() {

					private static final long serialVersionUID = 1L;
		
					@Override
					public Tuple2<Long, Row> call(Row row) throws Exception {
		
						return new Tuple2<Long, Row>(row.getLong(0), row);
					}
		
				});
				
		/**
		 * 这里比较适合采用reduce join转换为map join的方式
		 * 可能userId2PartAggrInfoRDD的数据量比较大,user2InfoRDD数据量比较小
		 * 
		 */

		// 将session粒度聚合数据,与用户信息进行join
		JavaPairRDD<Long, Tuple2<String, Row>> userId2FullInfoRDD = userId2PartAggrInfoRDD.join(userId2InfoRDD);

		// 对join起来的数据进行拼接，并且返回<sessionId,fullAggrInfo>格式的数据
		JavaPairRDD<String, String> sessionId2FullAggInfoRDD = userId2FullInfoRDD.mapToPair(
				new PairFunction<Tuple2<Long, Tuple2<String, Row>>, String, String>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Tuple2<String, String> call(Tuple2<Long, Tuple2<String, Row>> tuple) throws Exception {

						String partAggrInfo = tuple._2._1;
						Row userInfoRow = tuple._2._2;

						String sessionId = StringUtils.getFieldFromConcatString(partAggrInfo, "\\|",
								Constants.FIELD_SESSION_ID);

						int age = userInfoRow.getInt(3);
						String professional = userInfoRow.getString(4);
						String city = userInfoRow.getString(5);
						String sex = userInfoRow.getString(6);

						String fullAggrInfo = partAggrInfo + "|"
								+ Constants.FIELD_AGE + "=" + age + "|"
								+ Constants.FIELD_PROFESSIONAL + "=" + professional + "|"
								+ Constants.FIELD_CITY + "=" + city + "|"
								+ Constants.FIELD_SEX + "=" + sex + "|";

						return new Tuple2<String, String>(sessionId, fullAggrInfo);
					}
				});
		
		/**
		 *  reduce join 转换为 map join
		 */
//		List<Tuple2<Long, Row>> userInfos = userId2InfoRDD.collect();		// 调用collect操作拿到一个list
//		Broadcast<List<Tuple2<Long, Row>>> userInfosBroadcast = sc.broadcast(userInfos);
//		
//		JavaPairRDD<String, String> tunedRDD = userId2PartAggrInfoRDD.mapToPair(new PairFunction<Tuple2<Long, String>, String, String>() {
//
//			private static final long serialVersionUID = 1L;
//
//			@Override
//			public Tuple2<String, String> call(Tuple2<Long, String> tuple) throws Exception {
//
//				List<Tuple2<Long, Row>> userInfos = userInfosBroadcast.value();
//				
//				Map<Long, Row> userInfoMap = new HashMap<>();
//				for(Tuple2<Long, Row> userInfo : userInfos){
//					userInfoMap.put(userInfo._1, userInfo._2);
//				}
//				
//				//获取到当前用户对应的信息
//				String partAggrInfo = tuple._2;
//				Row userInfoRow = userInfoMap.get(tuple._1);
//				
//				String sessionId = StringUtils.getFieldFromConcatString(partAggrInfo, "\\|",
//						Constants.FIELD_SESSION_ID);
//
//				int age = userInfoRow.getInt(3);
//				String professional = userInfoRow.getString(4);
//				String city = userInfoRow.getString(5);
//				String sex = userInfoRow.getString(6);
//
//				String fullAggrInfo = partAggrInfo + "|" + Constants.FIELD_AGE + "=" + age + "|"
//						+ Constants.FIELD_PROFESSIONAL + "=" + professional + "|" + Constants.FIELD_CITY
//						+ "=" + city + "|" + Constants.FIELD_SEX + "=" + sex + "|";
//
//				return new Tuple2<String, String>(sessionId, fullAggrInfo);
//			}
//		});
		
		
		/**
		 * sample采用倾斜key单独进行join
		 * 
		 */
//		JavaPairRDD<Long, String> sampleRDD = userId2PartAggrInfoRDD.sample(false, 0.1, 9);
//		
//		//将每个sample中数据都转换成 <userId,1>的格式
//		JavaPairRDD<Long, Long> mappedSampledRDD = sampleRDD.mapToPair(
//				new PairFunction<Tuple2<Long, String>, Long, Long>() {
//
//					private static final long serialVersionUID = 1L;
//		
//					@Override
//					public Tuple2<Long, Long> call(Tuple2<Long, String> tuple) throws Exception {
//						
//						return new Tuple2<Long, Long>(tuple._1, 1L);
//					}
//				});
//		
//		//计算出每个userId出现的次数
//		JavaPairRDD<Long, Long> computeSampleddRDD = mappedSampledRDD.reduceByKey(
//				new Function2<Long, Long, Long>() {
//
//					private static final long serialVersionUID = 1L;
//		
//					@Override
//					public Long call(Long v1, Long v2) throws Exception {
//		
//						return v1 + v2;
//					}
//				});
//		
//		//将出现次数和userId进行反转  <userId, count> -> <count, userId>
//		JavaPairRDD<Long, Long> reversedSampledRDD = computeSampleddRDD.mapToPair(
//				new PairFunction<Tuple2<Long, Long>, Long, Long>() {
//
//					private static final long serialVersionUID = 1L;
//		
//					@Override
//					public Tuple2<Long, Long> call(Tuple2<Long, Long> tuple) throws Exception {
//		
//						return new Tuple2<Long, Long>(tuple._2, tuple._1);
//					}
//				});
//		
//		// 将反转结果排序,然后取出排第一的userId
//		long skewedUserId = reversedSampledRDD.sortByKey(false).take(1).get(0)._2;
//		
//		//拿到排第一的userId的所有数据,也就是数据倾斜的那个RDD
//		JavaPairRDD<Long, String> skewedRDD = userId2PartAggrInfoRDD.filter(
//				new Function<Tuple2<Long,String>, Boolean>() {
//
//					private static final long serialVersionUID = 1L;
//		
//					@Override
//					public Boolean call(Tuple2<Long, String> tuple) throws Exception {
//
//						return tuple._1.equals(skewedUserId);
//					}
//				});
//		
//		//拿到普通的RDD
//		JavaPairRDD<Long, String> commonRDD = userId2PartAggrInfoRDD.filter(
//				new Function<Tuple2<Long,String>, Boolean>() {
//
//					private static final long serialVersionUID = 1L;
//		
//					@Override
//					public Boolean call(Tuple2<Long, String> tuple) throws Exception {
//
//						return !tuple._1.equals(skewedUserId);
//					}
//				});
//		
//
//		JavaPairRDD<String, Row> skewedUserId2InfoRDD = userId2InfoRDD.filter(
//				new Function<Tuple2<Long,Row>, Boolean>() {
//
//					private static final long serialVersionUID = 1L;
//		
//					@Override
//					public Boolean call(Tuple2<Long, Row> tuple) throws Exception {
//
//						return tuple._1.equals(skewedUserId);
//					}
//				
//				}).flatMapToPair(new PairFlatMapFunction<Tuple2<Long, Row>, String, Row>() {
//
//					private static final long serialVersionUID = 1L;
//
//					@Override
//					public Iterable<Tuple2<String, Row>> call(Tuple2<Long, Row> tuple) throws Exception {
//
//						List<Tuple2<String, Row>> list = new ArrayList<>();
//						
//						for(int i = 0; i < 100; i++) {
//							list.add(new Tuple2<String, Row>(i + "_" + tuple._1, tuple._2));
//						}
//						
//						return list;
//					}
//				});
//		
//		JavaPairRDD<Long, Tuple2<String, Row>> joinedRDD1 = skewedRDD.mapToPair(
//				new PairFunction<Tuple2<Long, String>, String, String>() {
//
//					private static final long serialVersionUID = 1L;
//
//					@Override
//					public Tuple2<String, String> call(Tuple2<Long, String> tuple) throws Exception {
//						
//						Random random = new Random();
//						int prefix = random.nextInt(100);
//						return new Tuple2<String, String>(prefix + "_" + tuple._1, tuple._2);
//					}
//				}).join(skewedUserId2InfoRDD)
//				.mapToPair(new PairFunction<Tuple2<String,Tuple2<String,Row>>, Long, Tuple2<String, Row>>() {
//
//					private static final long serialVersionUID = 1L;
//
//					@Override
//					public Tuple2<Long, Tuple2<String, Row>> call(Tuple2<String, Tuple2<String, Row>> tuple) throws Exception {
//
//						long userId = Long.valueOf(tuple._1.split("_")[1]);
//						return new Tuple2<Long, Tuple2<String,Row>>(userId, tuple._2);
//					}
//				});
//		
////		JavaPairRDD<Long, Tuple2<String, Row>> joinedRDD1 = skewedRDD.join(userId2InfoRDD);
//		
//		JavaPairRDD<Long, Tuple2<String, Row>> joinedRDD2 = commonRDD.join(userId2InfoRDD);
//		JavaPairRDD<Long, Tuple2<String, Row>> joinedRDD = joinedRDD1.union(joinedRDD2);
//
//		// 得到最终的结果
//		JavaPairRDD<String, String> finalRDD = joinedRDD.mapToPair(
//				new PairFunction<Tuple2<Long, Tuple2<String, Row>>, String, String>() {
//
//					private static final long serialVersionUID = 1L;
//
//					@Override
//					public Tuple2<String, String> call(Tuple2<Long, Tuple2<String, Row>> tuple) throws Exception {
//
//						String partAggrInfo = tuple._2._1;
//						Row userInfoRow = tuple._2._2;
//
//						String sessionId = StringUtils.getFieldFromConcatString(partAggrInfo, "\\|",
//								Constants.FIELD_SESSION_ID);
//
//						int age = userInfoRow.getInt(3);
//						String professional = userInfoRow.getString(4);
//						String city = userInfoRow.getString(5);
//						String sex = userInfoRow.getString(6);
//
//						String fullAggrInfo = partAggrInfo + "|"
//								+ Constants.FIELD_AGE + "=" + age + "|"
//								+ Constants.FIELD_PROFESSIONAL + "=" + professional + "|"
//								+ Constants.FIELD_CITY + "=" + city + "|"
//								+ Constants.FIELD_SEX + "=" + sex + "|";
//
//						return new Tuple2<String, String>(sessionId, fullAggrInfo);
//					}
//				});
		
		
		/**
		 * 使用随机数和扩容表进行join
		 */
//		JavaPairRDD<String, Row> expandedRDD = userId2InfoRDD.flatMapToPair(
//				
//				new PairFlatMapFunction<Tuple2<Long,Row>, String, Row>() {
//
//					private static final long serialVersionUID = 1L;
//
//					@Override
//					public Iterable<Tuple2<String, Row>> call(Tuple2<Long, Row> tuple)
//							throws Exception {
//						List<Tuple2<String, Row>> list = new ArrayList<Tuple2<String, Row>>();
//						
//						for(int i = 0; i < 10; i++) {
//							list.add(new Tuple2<String, Row>(i + "_" + tuple._1, tuple._2));
//						}
//						
//						return list;
//					}
//					
//				});
//		
//		JavaPairRDD<String, String> mappedRDD = userId2PartAggrInfoRDD.mapToPair(
//				
//				new PairFunction<Tuple2<Long,String>, String, String>() {
//
//					private static final long serialVersionUID = 1L;
//
//					@Override
//					public Tuple2<String, String> call(Tuple2<Long, String> tuple)
//							throws Exception {
//						Random random = new Random();
//						int prefix = random.nextInt(10);
//						return new Tuple2<String, String>(prefix + "_" + tuple._1, tuple._2);  
//					}
//					
//				});
//		
//		JavaPairRDD<String, Tuple2<String, Row>> joinedRDD = mappedRDD.join(expandedRDD);
//		
//		JavaPairRDD<String, String> finalRDD = joinedRDD.mapToPair(
//				
//				new PairFunction<Tuple2<String,Tuple2<String,Row>>, String, String>() {
//
//					private static final long serialVersionUID = 1L;
//
//					@Override
//					public Tuple2<String, String> call(
//							Tuple2<String, Tuple2<String, Row>> tuple)
//							throws Exception {
//						String partAggrInfo = tuple._2._1;
//						Row userInfoRow = tuple._2._2;
//						
//						String sessionId = StringUtils.getFieldFromConcatString(
//								partAggrInfo, "\\|", Constants.FIELD_SESSION_ID);
//						
//						int age = userInfoRow.getInt(3);
//						String professional = userInfoRow.getString(4);
//						String city = userInfoRow.getString(5);
//						String sex = userInfoRow.getString(6);
//						
//						String fullAggrInfo = partAggrInfo + "|"
//								+ Constants.FIELD_AGE + "=" + age + "|"
//								+ Constants.FIELD_PROFESSIONAL + "=" + professional + "|"
//								+ Constants.FIELD_CITY + "=" + city + "|"
//								+ Constants.FIELD_SEX + "=" + sex;
//						
//						return new Tuple2<String, String>(sessionId, fullAggrInfo);
//					}
//					
//				});
		
		return sessionId2FullAggInfoRDD;
	}

	/**
	 * 过滤session数据
	 * 
	 * @param sessionId2AggrInfoRDD
	 * @param taskParam
	 * @return
	 */
	private static JavaPairRDD<String, String> filterSessionAndAggrStat(
			JavaPairRDD<String, String> sessionId2AggrInfoRDD, final JSONObject taskParam,
			final Accumulator<String> sessionAggrStatAccumulator) {

		String startAge = ParamUtils.getParam(taskParam, Constants.PARAM_START_AGE);
		String endAge = ParamUtils.getParam(taskParam, Constants.PARAM_END_AGE);
		String professionals = ParamUtils.getParam(taskParam, Constants.PARAM_PROFESSIONALS);
		String cities = ParamUtils.getParam(taskParam, Constants.PARAM_CITIES);
		String sex = ParamUtils.getParam(taskParam, Constants.PARAM_SEX);
		String keywords = ParamUtils.getParam(taskParam, Constants.PARAM_KEYWORDS);
		String categoryIds = ParamUtils.getParam(taskParam, Constants.PARAM_CATEGORY_IDS);

		String _parameter = (startAge != null ? Constants.PARAM_START_AGE + "=" + startAge + "|" : "")
				+ (endAge != null ? Constants.PARAM_END_AGE + "=" + endAge + "|" : "")
				+ (professionals != null ? Constants.PARAM_PROFESSIONALS + "=" + professionals + "|" : "")
				+ (cities != null ? Constants.PARAM_CITIES + "=" + cities + "|" : "")
				+ (sex != null ? Constants.PARAM_SEX + "=" + sex + "|" : "")
				+ (keywords != null ? Constants.PARAM_KEYWORDS + "=" + keywords + "|" : "")
				+ (categoryIds != null ? Constants.PARAM_CATEGORY_IDS + "=" + categoryIds : "");

		if (_parameter.endsWith("\\|")) {
			_parameter = _parameter.substring(0, _parameter.length() - 1);
		}

		final String parameter = _parameter;

		JavaPairRDD<String, String> filteredSessionId2AggrInfoRDD = sessionId2AggrInfoRDD.filter(

				new Function<Tuple2<String, String>, Boolean>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Boolean call(Tuple2<String, String> tuple) throws Exception {

						// 从tuple中获取聚合数据
						String aggrInfo = tuple._2;
						
						//parameter = startAge=10|endAge=50|
						// 按年龄进行过滤(startAge, endAge)
						if (!ValidUtils.between(aggrInfo, Constants.FIELD_AGE, parameter, Constants.PARAM_START_AGE,
								Constants.PARAM_END_AGE)) {
							return false;
						}
						
						// 按照职业范围进行过滤（professionals）
						if (!ValidUtils.in(aggrInfo, Constants.FIELD_PROFESSIONAL, parameter,
								Constants.PARAM_PROFESSIONALS)) {
							return false;
						}

						// 按照城市范围进行过滤（cities）
						if (!ValidUtils.in(aggrInfo, Constants.FIELD_CITY, parameter, Constants.PARAM_CITIES)) {
							return false;
						}

						// 按照性别进行过滤
						if (!ValidUtils.equal(aggrInfo, Constants.FIELD_SEX, parameter, Constants.PARAM_SEX)) {
							return false;
						}

						// 按照搜索词进行过滤
						// 那么，in这个校验方法，主要判定session搜索的词中，有任何一个，与筛选条件中
						// 任何一个搜索词相当，即通过
						if (!ValidUtils.in(aggrInfo, Constants.FIELD_SEARCH_KEYWORDS, parameter,
								Constants.PARAM_KEYWORDS)) {
							return false;
						}

						// 按照点击品类id进行过滤
						if (!ValidUtils.in(aggrInfo, Constants.FIELD_CLICK_CATEGORY_IDS, parameter,
								Constants.PARAM_CATEGORY_IDS)) {
							return false;
						}
						
						// 对session的访问步长和访问时长进行统计
						sessionAggrStatAccumulator.add(Constants.SESSION_COUNT);
						
					    long visitLength = Long.valueOf(StringUtils.getFieldFromConcatString(aggrInfo, "\\|", Constants.FIELD_VISIT_LENGTH));
					    long stepLength = Long.valueOf(StringUtils.getFieldFromConcatString(aggrInfo, "\\|", Constants.FIELD_STEP_LENGTH));
					    
					    calculateVisitLength(visitLength);
					    calculateStepLength(stepLength);
					    
						return true;
					}
					
					private void calculateVisitLength(long visitLength){
						if(visitLength >=1 && visitLength <= 3) {
							sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_1s_3s);  
						} else if(visitLength >=4 && visitLength <= 6) {
							sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_4s_6s);  
						} else if(visitLength >=7 && visitLength <= 9) {
							sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_7s_9s);  
						} else if(visitLength >=10 && visitLength <= 30) {
							sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_10s_30s);  
						} else if(visitLength > 30 && visitLength <= 60) {
							sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_30s_60s);  
						} else if(visitLength > 60 && visitLength <= 180) {
							sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_1m_3m);  
						} else if(visitLength > 180 && visitLength <= 600) {
							sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_3m_10m);  
						} else if(visitLength > 600 && visitLength <= 1800) {  
							sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_10m_30m);  
						} else if(visitLength > 1800) {
							sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_30m);  
						}
					}
					
					/**
					 * 计算访问步长范围
					 * 
					 */
					private void calculateStepLength(long stepLength){
						if(stepLength >= 1 && stepLength <= 3) {
							sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_1_3);
						} else if(stepLength >= 4 && stepLength <= 6) {
							sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_4_6);
						} else if(stepLength >= 7 && stepLength <= 9) {
							sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_7_9);
						} else if(stepLength >= 10 && stepLength <= 30) {
							sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_10_30);
						} else if(stepLength > 30 && stepLength <= 60) {
							sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_30_60);
						} else if(stepLength > 60) {
							sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_60);
						}
					}
					
				});

		return filteredSessionId2AggrInfoRDD;
	}
	
	/**
	 * 获取通过筛选条件的session的访问明细数据RDD
	 * @param sessionid2aggrInfoRDD
	 * @param sessionid2actionRDD
	 * @return
	 */
	private static JavaPairRDD<String, Row> getSessionId2DetailRDD(
			JavaPairRDD<String, String> sessionId2AggrInfoRDD,
			JavaPairRDD<String, Row> sessionId2ActionRDD) {
		JavaPairRDD<String, Row> sessionId2DetailRDD = sessionId2AggrInfoRDD
				.join(sessionId2ActionRDD)
				.mapToPair(new PairFunction<Tuple2<String,Tuple2<String,Row>>, String, Row>() {
		
					private static final long serialVersionUID = 1L;

					@Override
					public Tuple2<String, Row> call(
							Tuple2<String, Tuple2<String, Row>> tuple) throws Exception {
						return new Tuple2<String, Row>(tuple._1, tuple._2._2);
					}
					
				});
		return sessionId2DetailRDD;
	}
	
	/**
	 * 随机抽取session
	 * @param sessionid2AggrInfoRDD  
	 */
	private static void randomExtractSession(
			JavaSparkContext sc,
			final long taskid,
			JavaPairRDD<String, String> sessionid2AggrInfoRDD,
			JavaPairRDD<String, Row> sessionId2actionRDD) { 
		/**
		 * 第一步，计算出每天每小时的session数量
		 */
		
		// 获取<yyyy-MM-dd_HH,aggrInfo>格式的RDD
		JavaPairRDD<String, String> time2sessionIdRDD = sessionid2AggrInfoRDD.mapToPair(
				
				new PairFunction<Tuple2<String,String>, String, String>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Tuple2<String, String> call(
							Tuple2<String, String> tuple) throws Exception {
						String aggrInfo = tuple._2;
						
						String startTime = StringUtils.getFieldFromConcatString(
								aggrInfo, "\\|", Constants.FIELD_START_TIME);
						String dateHour = DateUtils.getDateHour(startTime);
						
						return new Tuple2<String, String>(dateHour, aggrInfo);  
					}
					
				});
		
		/**
		 * 
		 * 每天每小时的session数量，然后计算出每天每小时的session抽取索引，遍历每天每小时session
		 * 首先抽取出的session的聚合数据，写入session_random_extract表
		 * 所以第一个RDD的value，应该是session聚合数据
		 * 
		 */
		
		/**
		 * 每天每小时的session数量的计算是有可能出现数据倾斜的
		 * 比如说大部分小时，一般访问量也就10万；中午12点的时候，高峰期，一个小时1000万
		 * 这个时候，就会发生数据倾斜
		 * 
		 */
		// 得到每天每小时的session数量
		Map<String, Object> countMap = time2sessionIdRDD.countByKey();
		
		/**
		 * 第二步，使用按时间比例随机抽取算法，计算出每天每小时要抽取session的索引
		 */
		
		// 将<yyyy-MM-dd_HH,count>格式的map，转换成<yyyy-MM-dd,<HH,count>>的格式
		Map<String, Map<String, Long>> dateHourCountMap = 
				new HashMap<String, Map<String, Long>>();
		
		for(Map.Entry<String, Object> countEntry : countMap.entrySet()) {
			String dateHour = countEntry.getKey();
			String date = dateHour.split("_")[0];
			String hour = dateHour.split("_")[1];  
			
			long count = Long.valueOf(String.valueOf(countEntry.getValue()));  
			
			Map<String, Long> hourCountMap = dateHourCountMap.get(date);
			if(hourCountMap == null) {
				hourCountMap = new HashMap<String, Long>();
				dateHourCountMap.put(date, hourCountMap);
			}
			
			hourCountMap.put(hour, count);
		}
		
		// 开始实现我们的按时间比例随机抽取算法
		
		// 总共要抽取100个session，先按照天数，进行平分
		int extractNumberPerDay = 100 / dateHourCountMap.size();
		
		/**
		 * session随机抽取功能
		 * 
		 * 用到了一个比较大的变量，随机抽取索引map
		 * 之前是直接在算子里面使用了这个map，那么根据我们刚才讲的这个原理，每个task都会拷贝一份map副本
		 * 还是比较消耗内存和网络传输性能的
		 * 
		 * 将map做成广播变量
		 * 
		 */
		// <date,<hour,(3,5,20,102)>>
		final Map<String, Map<String, List<Integer>>> dateHourExtractMap = 
				new HashMap<String, Map<String, List<Integer>>>();
		
		Random random = new Random();
		
		for(Map.Entry<String, Map<String, Long>> dateHourCountEntry : dateHourCountMap.entrySet()) {
			String date = dateHourCountEntry.getKey();
			Map<String, Long> hourCountMap = dateHourCountEntry.getValue();
			
			// 计算出这一天的session总数
			long sessionCount = 0L;
			for(long hourCount : hourCountMap.values()) {
				sessionCount += hourCount;
			}
			
			Map<String, List<Integer>> hourExtractMap = dateHourExtractMap.get(date);
			if(hourExtractMap == null) {
				hourExtractMap = new HashMap<String, List<Integer>>();
				dateHourExtractMap.put(date, hourExtractMap);
			}
			
			// 遍历每个小时
			for(Map.Entry<String, Long> hourCountEntry : hourCountMap.entrySet()) {
				String hour = hourCountEntry.getKey();
				long count = hourCountEntry.getValue();
				
				// 计算每个小时的session数量，占据当天总session数量的比例，直接乘以每天要抽取的数量
				// 就可以计算出，当前小时需要抽取的session数量
				int hourExtractNumber = (int)(((double)count / (double)sessionCount) 
						* extractNumberPerDay);
				if(hourExtractNumber > count) {
					hourExtractNumber = (int) count;
				}
				
				// 先获取当前小时的存放随机数的list
				List<Integer> extractIndexList = hourExtractMap.get(hour);
				if(extractIndexList == null) {
					extractIndexList = new ArrayList<Integer>();
					hourExtractMap.put(hour, extractIndexList);
				}
				
				// 生成上面计算出来的数量的随机数
				for(int i = 0; i < hourExtractNumber; i++) {
					int extractIndex = random.nextInt((int) count);
					while(extractIndexList.contains(extractIndex)) {
						extractIndex = random.nextInt((int) count);
					}
					extractIndexList.add(extractIndex);
				}
			}
		}
		
		/**
		 * fastutil的使用，很简单，比如List<Integer>的list，对应到fastutil，就是IntList
		 */
//		Map<String, Map<String, IntList>> fastutilDateHourExtractMap = 
//				new HashMap<String, Map<String, IntList>>();
//		
//		
//		
//		for(Map.Entry<String, Map<String, List<Integer>>> dateHourExtractEntry : 
//				dateHourExtractMap.entrySet()) {
//			String date = dateHourExtractEntry.getKey();
//			Map<String, List<Integer>> hourExtractMap = dateHourExtractEntry.getValue();
//			
//			Map<String, IntList> fastutilHourExtractMap = new HashMap<String, IntList>();
//			
//			for(Map.Entry<String, List<Integer>> hourExtractEntry : hourExtractMap.entrySet()) {
//				String hour = hourExtractEntry.getKey();
//				List<Integer> extractList = hourExtractEntry.getValue();
//				
//				IntList fastutilExtractList = new IntArrayList();
//				
//				for(int i = 0; i < extractList.size(); i++) {
//					fastutilExtractList.add(extractList.get(i));  
//				}
//				
//				fastutilHourExtractMap.put(hour, fastutilExtractList);
//			}
//			
//			fastutilDateHourExtractMap.put(date, fastutilHourExtractMap);
//		}
		
		/**
		 * 广播变量,
		 * 其实就是用SparkContext的broadcast()方法，传入你要广播的变量，即可
		 */		
		final Broadcast<Map<String, Map<String, List<Integer>>>> dateHourExtractMapBroadcast = 
				sc.broadcast(dateHourExtractMap);
		
		/**
		 * 第三步：遍历每天每小时的session，然后根据随机索引进行抽取
		 */
		
		// 执行groupByKey算子，得到<dateHour,(session aggrInfo)>  
		JavaPairRDD<String, Iterable<String>> time2sessionsRDD = time2sessionIdRDD.groupByKey();
		
		// 我们用flatMap算子，遍历所有的<dateHour,(session aggrInfo)>格式的数据
		// 然后遍历每天每小时的session
		// 如果发现某个session恰巧在我们指定的这天这小时的随机抽取索引上
		// 那么抽取该session，直接写入MySQL的random_extract_session表
		// 将抽取出来的session id返回回来，形成一个新的JavaRDD<String>
		// 然后最后一步，是用抽取出来的sessionid，去join它们的访问行为明细数据，写入session表
		JavaPairRDD<String, String> extractSessionidsRDD = time2sessionsRDD.flatMapToPair(
				
				new PairFlatMapFunction<Tuple2<String,Iterable<String>>, String, String>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Iterable<Tuple2<String, String>> call(Tuple2<String, Iterable<String>> tuple)
							throws Exception {
						
						List<Tuple2<String, String>> extractSessionIds = new ArrayList<Tuple2<String, String>>();
						
						String dateHour = tuple._1;
						String date = dateHour.split("_")[0];
						String hour = dateHour.split("_")[1];
						Iterator<String> iterator = tuple._2.iterator();
						
						/**
						 * 使用广播变量的时候
						 * 直接调用广播变量（Broadcast类型）的value() / getValue() 
						 * 可以获取到之前封装的广播变量
						 */
						Map<String, Map<String, List<Integer>>> dateHourExtractMap = 
								dateHourExtractMapBroadcast.value();
						List<Integer> extractIndexList = dateHourExtractMap.get(date).get(hour);  
						
						ISessionRandomExtractDao sessionRandomExtractDao = 
								DaoFactory.getSessionRandomExtractDao();
						
						int index = 0;
						while(iterator.hasNext()) {
							String sessionAggrInfo = iterator.next();
							
							if(extractIndexList.contains(index)) {
								String sessionid = StringUtils.getFieldFromConcatString(
										sessionAggrInfo, "\\|", Constants.FIELD_SESSION_ID);
								
								// 将数据写入MySQL
								SessionRandomExtract sessionRandomExtract = new SessionRandomExtract();
								sessionRandomExtract.setTaskid(taskid);  
								sessionRandomExtract.setSessionid(sessionid);  
								sessionRandomExtract.setStartTime(StringUtils.getFieldFromConcatString(
										sessionAggrInfo, "\\|", Constants.FIELD_START_TIME));  
								sessionRandomExtract.setSearchKeywords(StringUtils.getFieldFromConcatString(
										sessionAggrInfo, "\\|", Constants.FIELD_SEARCH_KEYWORDS));
								sessionRandomExtract.setClickCategoryIds(StringUtils.getFieldFromConcatString(
										sessionAggrInfo, "\\|", Constants.FIELD_CLICK_CATEGORY_IDS));
								
								sessionRandomExtractDao.insert(sessionRandomExtract);  
								
								// 将sessionid加入list
								extractSessionIds.add(new Tuple2<String, String>(sessionid, sessionid));  
							}
							
							index++;
						}
						
						return extractSessionIds;
					}
					
				});
		
		/**
		 * 第四步：获取抽取出来的session的明细数据
		 */
		JavaPairRDD<String, Tuple2<String, Row>> extractSessionDetailRDD =
				extractSessionidsRDD.join(sessionId2actionRDD);
		
//		extractSessionDetailRDD.foreach(new VoidFunction<Tuple2<String,Tuple2<String,Row>>>() {  
//			
//			private static final long serialVersionUID = 1L;
//			
//			@Override
//			public void call(Tuple2<String, Tuple2<String, Row>> tuple) throws Exception {
//				Row row = tuple._2._2;
//				
//				SessionDetail sessionDetail = new SessionDetail();
//				sessionDetail.setTaskid(taskid);  
//				sessionDetail.setUserid(row.getLong(1));  
//				sessionDetail.setSessionid(row.getString(2));  
//				sessionDetail.setPageid(row.getLong(3));  
//				sessionDetail.setActionTime(row.getString(4));
//				sessionDetail.setSearchKeyword(row.getString(5));  
//				sessionDetail.setClickCategoryId(row.getLong(6));  
//				sessionDetail.setClickProductId(row.getLong(7));   
//				sessionDetail.setOrderCategoryIds(row.getString(8));  
//				sessionDetail.setOrderProductIds(row.getString(9));  
//				sessionDetail.setPayCategoryIds(row.getString(10)); 
//				sessionDetail.setPayProductIds(row.getString(11));  
//				
//				ISessionDetailDao sessionDetailDAO = DaoFactory.getSessionDetailDao();
//				sessionDetailDAO.insert(sessionDetail);  
//			}
//		});
		
		extractSessionDetailRDD.foreachPartition(
				
				new VoidFunction<Iterator<Tuple2<String,Tuple2<String,Row>>>>() {

					private static final long serialVersionUID = 1L;

					@Override
					public void call(
							Iterator<Tuple2<String, Tuple2<String, Row>>> iterator) 
							throws Exception {
						List<SessionDetail> sessionDetails = new ArrayList<SessionDetail>();
						
						while(iterator.hasNext()) {
							Tuple2<String, Tuple2<String, Row>> tuple = iterator.next();
							
							Row row = tuple._2._2;
							
							SessionDetail sessionDetail = new SessionDetail();
							sessionDetail.setTaskid(taskid);  
							sessionDetail.setUserid(row.getLong(1));  
							sessionDetail.setSessionid(row.getString(2));  
							sessionDetail.setPageid(row.getLong(3));  
							sessionDetail.setActionTime(row.getString(4));
							sessionDetail.setSearchKeyword(row.getString(5));  
							sessionDetail.setClickCategoryId(row.getLong(6));  
							sessionDetail.setClickProductId(row.getLong(7));   
							sessionDetail.setOrderCategoryIds(row.getString(8));  
							sessionDetail.setOrderProductIds(row.getString(9));  
							sessionDetail.setPayCategoryIds(row.getString(10)); 
							sessionDetail.setPayProductIds(row.getString(11));  
							
							sessionDetails.add(sessionDetail);
						}
						
						ISessionDetailDao sessionDetailDao = DaoFactory.getSessionDetailDao();
						sessionDetailDao.insertBatch(sessionDetails);
					}
					
				});
	}

	/**
	 * 计算各session范围占比，并写入MySQL
	 * @param value
	 */
	private static void calculateAndPersistAggrStat(String value, long taskid) {
		// 从Accumulator统计串中获取值
		System.out.println(value);
		long session_count = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.SESSION_COUNT));  
		
		long visit_length_1s_3s = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.TIME_PERIOD_1s_3s));  
		long visit_length_4s_6s = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.TIME_PERIOD_4s_6s));
		long visit_length_7s_9s = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.TIME_PERIOD_7s_9s));
		long visit_length_10s_30s = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.TIME_PERIOD_10s_30s));
		long visit_length_30s_60s = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.TIME_PERIOD_30s_60s));
		long visit_length_1m_3m = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.TIME_PERIOD_1m_3m));
		long visit_length_3m_10m = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.TIME_PERIOD_3m_10m));
		long visit_length_10m_30m = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.TIME_PERIOD_10m_30m));
		long visit_length_30m = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.TIME_PERIOD_30m));
		
		long step_length_1_3 = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.STEP_PERIOD_1_3));
		long step_length_4_6 = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.STEP_PERIOD_4_6));
		long step_length_7_9 = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.STEP_PERIOD_7_9));
		long step_length_10_30 = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.STEP_PERIOD_10_30));
		long step_length_30_60 = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.STEP_PERIOD_30_60));
		long step_length_60 = Long.valueOf(StringUtils.getFieldFromConcatString(
				value, "\\|", Constants.STEP_PERIOD_60));
		
		// 计算各个访问时长和访问步长的范围
		double visit_length_1s_3s_ratio = NumberUtils.formatDouble(
				(double)visit_length_1s_3s / (double) session_count, 2);  
		double visit_length_4s_6s_ratio = NumberUtils.formatDouble(
				(double)visit_length_4s_6s / (double)session_count, 2);  
		double visit_length_7s_9s_ratio = NumberUtils.formatDouble(
				(double)visit_length_7s_9s / (double)session_count, 2);  
		double visit_length_10s_30s_ratio = NumberUtils.formatDouble(
				(double)visit_length_10s_30s / (double)session_count, 2);  
		double visit_length_30s_60s_ratio = NumberUtils.formatDouble(
				(double)visit_length_30s_60s / (double)session_count, 2);  
		double visit_length_1m_3m_ratio = NumberUtils.formatDouble(
				(double)visit_length_1m_3m / (double)session_count, 2);
		double visit_length_3m_10m_ratio = NumberUtils.formatDouble(
				(double)visit_length_3m_10m / (double)session_count, 2);  
		double visit_length_10m_30m_ratio = NumberUtils.formatDouble(
				(double)visit_length_10m_30m / (double)session_count, 2);
		double visit_length_30m_ratio = NumberUtils.formatDouble(
				(double)visit_length_30m / (double)session_count, 2);  
		
		double step_length_1_3_ratio = NumberUtils.formatDouble(
				(double)step_length_1_3 / (double)session_count, 2);  
		double step_length_4_6_ratio = NumberUtils.formatDouble(
				(double)step_length_4_6 / (double)session_count, 2);  
		double step_length_7_9_ratio = NumberUtils.formatDouble(
				(double)step_length_7_9 / (double)session_count, 2);  
		double step_length_10_30_ratio = NumberUtils.formatDouble(
				(double)step_length_10_30 / (double)session_count, 2);  
		double step_length_30_60_ratio = NumberUtils.formatDouble(
				(double)step_length_30_60 / (double)session_count, 2);  
		double step_length_60_ratio = NumberUtils.formatDouble(
				(double)step_length_60 / (double)session_count, 2);  
		
		// 将统计结果封装为Domain对象
		SessionAggrStat sessionAggrStat = new SessionAggrStat();
		sessionAggrStat.setTaskid(taskid);
		sessionAggrStat.setSession_count(session_count);  
		sessionAggrStat.setVisit_length_1s_3s_ratio(visit_length_1s_3s_ratio);  
		sessionAggrStat.setVisit_length_4s_6s_ratio(visit_length_4s_6s_ratio);  
		sessionAggrStat.setVisit_length_7s_9s_ratio(visit_length_7s_9s_ratio);  
		sessionAggrStat.setVisit_length_10s_30s_ratio(visit_length_10s_30s_ratio);  
		sessionAggrStat.setVisit_length_30s_60s_ratio(visit_length_30s_60s_ratio);  
		sessionAggrStat.setVisit_length_1m_3m_ratio(visit_length_1m_3m_ratio); 
		sessionAggrStat.setVisit_length_3m_10m_ratio(visit_length_3m_10m_ratio);  
		sessionAggrStat.setVisit_length_10m_30m_ratio(visit_length_10m_30m_ratio); 
		sessionAggrStat.setVisit_length_30m_ratio(visit_length_30m_ratio);  
		sessionAggrStat.setStep_length_1_3_ratio(step_length_1_3_ratio);  
		sessionAggrStat.setStep_length_4_6_ratio(step_length_4_6_ratio);  
		sessionAggrStat.setStep_length_7_9_ratio(step_length_7_9_ratio);  
		sessionAggrStat.setStep_length_10_30_ratio(step_length_10_30_ratio);  
		sessionAggrStat.setStep_length_30_60_ratio(step_length_30_60_ratio);  
		sessionAggrStat.setStep_length_60_ratio(step_length_60_ratio);  
		
		// 调用对应的Dao插入统计结果
		ISessionAggrStatDao sessionAggrStatDAO = DaoFactory.getSessionAggrStatDao();
		sessionAggrStatDAO.insert(sessionAggrStat);  
	}
	
	/**
	 * 获取top10热门品类
	 * @param filteredSessionid2AggrInfoRDD
	 * @param sessionid2actionRDD
	 */
	private static List<Tuple2<CategorySortKey, String>> getTop10Category(long taskId, 
			JavaPairRDD<String, Row> sessionId2DetailRDD) {
		
		/**
		 * 第一步：获取符合条件的session访问过的所有品类
		 */
		// 获取session访问过的所有品类id
		// 访问过：指的是，点击过或下单过或支付过的品类
		JavaPairRDD<Long, Long> categoryIdRDD = sessionId2DetailRDD.flatMapToPair(
				
				new PairFlatMapFunction<Tuple2<String,Row>, Long, Long>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Iterable<Tuple2<Long, Long>> call(
							Tuple2<String, Row> tuple) throws Exception {
						Row row = tuple._2;
						
						List<Tuple2<Long, Long>> list = new ArrayList<Tuple2<Long, Long>>();
						
						Long clickCategoryId = row.getLong(6);
						if(clickCategoryId != null) {
							list.add(new Tuple2<Long, Long>(clickCategoryId, clickCategoryId));   
						}
						
						String orderCategoryIds = row.getString(8);
						if(orderCategoryIds != null) {
							String[] orderCategoryIdsSplited = orderCategoryIds.split(",");  
							for(String orderCategoryId : orderCategoryIdsSplited) {
								list.add(new Tuple2<Long, Long>(Long.valueOf(orderCategoryId),
										Long.valueOf(orderCategoryId)));
							}
						}
						
						String payCategoryIds = row.getString(10);
						if(payCategoryIds != null) {
							String[] payCategoryIdsSplited = payCategoryIds.split(",");  
							for(String payCategoryId : payCategoryIdsSplited) {
								list.add(new Tuple2<Long, Long>(Long.valueOf(payCategoryId),
										Long.valueOf(payCategoryId)));
							}
						}
						
						return list;
					}
					
				});
		
		/**
		 * 必须要进行去重
		 */
		categoryIdRDD = categoryIdRDD.distinct();
		
		/**
		 * 第二步：计算各品类的点击、下单和支付的次数
		 */
		
		// 访问明细中，其中三种访问行为是：点击、下单和支付
		// 分别来计算各品类点击、下单和支付的次数，可以先对访问明细数据进行过滤
		// 分别过滤出点击、下单和支付行为，然后通过map、reduceByKey等算子来进行计算
		
		// 计算各个品类的点击次数
		JavaPairRDD<Long, Long> clickCategoryId2CountRDD = getClickCategoryId2CountRDD(sessionId2DetailRDD);
		
		// 计算各个品类的下单次数
		JavaPairRDD<Long, Long> orderCategoryId2CountRDD = getOrderCategoryId2CountRDD(sessionId2DetailRDD);
		
		// 计算各个品类的支付次数
		JavaPairRDD<Long, Long> payCategoryId2CountRDD = getPayCategoryId2CountRDD(sessionId2DetailRDD);
		
		/**
		 * 第三步：join各品类与它的点击、下单和支付的次数
		 * 
		 * categoryidRDD中，是包含了所有的符合条件的session，访问过的品类id
		 * 
		 * 上面分别计算出来的三份，各品类的点击、下单和支付的次数，可能不是包含所有品类的
		 * 比如，有的品类，就只是被点击过，但是没有人下单和支付
		 * 
		 * 所以，这里就不能使用join操作，要使用leftOuterJoin操作，就是说，如果categoryidRDD不能
		 * join到自己的某个数据，比如点击、或下单、或支付次数，那么该categoryidRDD还是要保留下来的
		 * 只不过，没有join到的那个数据是0
		 * 
		 */
		JavaPairRDD<Long, String> categoryid2countRDD = joinCategoryAndData(
				categoryIdRDD, clickCategoryId2CountRDD, orderCategoryId2CountRDD, 
				payCategoryId2CountRDD);
		
		/**
		 * 第四步：自定义二次排序key
		 */
		
		/**
		 * 第五步：将数据映射成<CategorySortKey,info>格式的RDD，然后进行二次排序（降序）
		 */
		JavaPairRDD<CategorySortKey, String> sortKey2countRDD = categoryid2countRDD.mapToPair(
				
				new PairFunction<Tuple2<Long,String>, CategorySortKey, String>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Tuple2<CategorySortKey, String> call(
							Tuple2<Long, String> tuple) throws Exception {
						String countInfo = tuple._2;
						long clickCount = Long.valueOf(StringUtils.getFieldFromConcatString(
								countInfo, "\\|", Constants.FIELD_CLICK_COUNT));  
						long orderCount = Long.valueOf(StringUtils.getFieldFromConcatString(
								countInfo, "\\|", Constants.FIELD_ORDER_COUNT));  
						long payCount = Long.valueOf(StringUtils.getFieldFromConcatString(
								countInfo, "\\|", Constants.FIELD_PAY_COUNT));  
						
						CategorySortKey sortKey = new CategorySortKey(clickCount,
								orderCount, payCount);
						
						return new Tuple2<CategorySortKey, String>(sortKey, countInfo);  
					}
					
				});
		
		JavaPairRDD<CategorySortKey, String> sortedCategoryCountRDD = 
				sortKey2countRDD.sortByKey(false);
		
		/**
		 * 第六步：用take(10)取出top10热门品类，并写入MySQL
		 */
		ITop10CategoryDao top10CategoryDao = DaoFactory.getTop10CategoryDao();
		
		List<Tuple2<CategorySortKey, String>> top10CategoryList = 
				sortedCategoryCountRDD.take(10);
		
		for(Tuple2<CategorySortKey, String> tuple: top10CategoryList) {
			String countInfo = tuple._2;
			long categoryId = Long.valueOf(StringUtils.getFieldFromConcatString(
					countInfo, "\\|", Constants.FIELD_CATEGORY_ID));  
			long clickCount = Long.valueOf(StringUtils.getFieldFromConcatString(
					countInfo, "\\|", Constants.FIELD_CLICK_COUNT));  
			long orderCount = Long.valueOf(StringUtils.getFieldFromConcatString(
					countInfo, "\\|", Constants.FIELD_ORDER_COUNT));  
			long payCount = Long.valueOf(StringUtils.getFieldFromConcatString(
					countInfo, "\\|", Constants.FIELD_PAY_COUNT));  
			
			Top10Category category = new Top10Category();
			category.setTaskid(taskId); 
			category.setCategoryid(categoryId); 
			category.setClickCount(clickCount);  
			category.setOrderCount(orderCount);
			category.setPayCount(payCount);
			
			top10CategoryDao.insert(category);  
		}
		
		return top10CategoryList;
		
	}
	
	/**
	 * 获取各品类点击次数RDD
	 * @param sessionid2detailRDD
	 * @return
	 */
	private static JavaPairRDD<Long, Long> getClickCategoryId2CountRDD(
			JavaPairRDD<String, Row> sessionid2detailRDD) {
		
		/**
		 * 过滤出点击行为的数据，每个partition的数据量可能很不均匀
		 * 
		 * 所以可以使用coalesce算子，在filter过后减少partition的数量
		 */
		JavaPairRDD<String,Row> clickActionRDD = sessionid2detailRDD.filter(new Function<Tuple2<String,Row>, Boolean>() {

			private static final long serialVersionUID = 1L;

			@Override
			public Boolean call(Tuple2<String, Row> tuple) throws Exception {
				Row row = tuple._2;
				//过滤掉点击次数为空的数据
				return row.get(6) != null ? true : false;
			}
			
		});
//		.coalesce(100);		//将过滤后的RDD的partition数量压缩到100
		
		/**
		 *	local模式下，不用去设置分区和并行度的数量,
		 *	local模式下自己本身就是进程内模拟的集群来执行，本身性能就很高,
		 *	而且对并行度、partition数量都有一定的内部的优化。自己再进行设置就有点画蛇添足。
		 */
		
		JavaPairRDD<Long, Long> clickCategoryIdRDD = clickActionRDD.mapToPair(
				new PairFunction<Tuple2<String,Row>, Long, Long>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Tuple2<Long, Long> call(Tuple2<String, Row> tuple) throws Exception {
						long clickCategoryId = tuple._2.getLong(6);
						return new Tuple2<Long, Long>(clickCategoryId, 1L);
					}
		});
		
		
		/**
		 * 如果某个品类点击了1000万次,其他品类都是10万次,可能发生数据倾斜
		 * 
		 */
		//计算每一个品类被点击的次数
		JavaPairRDD<Long, Long> clickCategoryId2CountRDD = clickCategoryIdRDD.reduceByKey(
				new Function2<Long, Long, Long>() {
			
					private static final long serialVersionUID = 1L;

					@Override
					public Long call(Long v1, Long v2) throws Exception {

						return v1 + v2;
					}
		});
		
		/**
		 * 提升shuffle ruduce端并行度
		 * 
		 */
//		JavaPairRDD<Long, Long> clickCategoryId2CountRDD = clickCategoryIdRDD.reduceByKey(
//				new Function2<Long, Long, Long>() {
//			
//					private static final long serialVersionUID = 1L;
//
//					@Override
//					public Long call(Long v1, Long v2) throws Exception {
//
//						return v1 + v2;
//					}
//		},
//		1000);
		
//		/**
//		 * 第一步,给每一个key打上一个随机数
//		 * 
//		 */
//		JavaPairRDD<String, Long> mappedClickCategoryIdRDD = clickCategoryIdRDD.mapToPair(new PairFunction<Tuple2<Long,Long>, String, Long>() {
//
//			private static final long serialVersionUID = 1L;
//
//			@Override
//			public Tuple2<String, Long> call(Tuple2<Long, Long> tuple) throws Exception {
//				Random random = new Random();
//				int prefix = random.nextInt(10);
//				return new Tuple2<String, Long>(prefix + "_" + tuple._1, tuple._2);
//			}
//			
//		});
//		
//		/**
//		 * 第二步,执行第一轮局部聚合
//		 * 
//		 */
//		JavaPairRDD<String, Long> firstAggrRDD = mappedClickCategoryIdRDD.reduceByKey(new Function2<Long, Long, Long>() {
//
//			private static final long serialVersionUID = 2909019238935769687L;
//
//			@Override
//			public Long call(Long v1, Long v2) throws Exception {
//				
//				return v1 + v2;
//			}
//		});
//		
//		/**
//		 * 第三步，去除掉每个key的前缀
//		 * 
//		 */
//		JavaPairRDD<Long, Long> restoredRDD = firstAggrRDD.mapToPair(new PairFunction<Tuple2<String,Long>, Long, Long>() {
//
//			private static final long serialVersionUID = 1L;
//
//			@Override
//			public Tuple2<Long, Long> call(Tuple2<String, Long> tuple) throws Exception {
//				long categoryId = Long.valueOf(tuple._1.split("_")[1]);
//				return new Tuple2<Long, Long>(categoryId, tuple._2);
//			}
//		});
//		
//		/**
//		 * 第四步：做第二轮全局的聚合
//		 * 
//		 */
//		JavaPairRDD<Long, Long> globalAggrRDD = restoredRDD.reduceByKey(new Function2<Long, Long, Long>() {
//
//			private static final long serialVersionUID = 2909019238935769687L;
//
//			@Override
//			public Long call(Long v1, Long v2) throws Exception {
//				
//				return v1 + v2;
//			}
//		});
		
		return clickCategoryId2CountRDD;
	}
	
	/**
	 * 获取各品类的下单次数RDD
	 * @param sessionid2detailRDD
	 * @return
	 */
	private static JavaPairRDD<Long, Long> getOrderCategoryId2CountRDD(
			JavaPairRDD<String, Row> sessionid2detailRDD) {
		
		JavaPairRDD<String, Row> orderActionRDD = sessionid2detailRDD.filter(
				
				new Function<Tuple2<String,Row>, Boolean>() {

					private static final long serialVersionUID = 1L;
		
					@Override
					public Boolean call(Tuple2<String, Row> tuple) throws Exception {
						Row row = tuple._2;  
						return row.getString(8) != null ? true : false;
					}
					
				});
		
		JavaPairRDD<Long, Long> orderCategoryIdRDD = orderActionRDD.flatMapToPair(
				
				new PairFlatMapFunction<Tuple2<String,Row>, Long, Long>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Iterable<Tuple2<Long, Long>> call(
							Tuple2<String, Row> tuple) throws Exception {
						Row row = tuple._2;
						String orderCategoryIds = row.getString(8);
						String[] orderCategoryIdsSplited = orderCategoryIds.split(",");  
						
						List<Tuple2<Long, Long>> list = new ArrayList<Tuple2<Long, Long>>();
						
						for(String orderCategoryId : orderCategoryIdsSplited) {
							list.add(new Tuple2<Long, Long>(Long.valueOf(orderCategoryId), 1L));  
						}
						
						return list;
					}
					
				});
		
		JavaPairRDD<Long, Long> orderCategoryId2CountRDD = orderCategoryIdRDD.reduceByKey(
				
				new Function2<Long, Long, Long>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Long call(Long v1, Long v2) throws Exception {
						return v1 + v2;
					}
					
				});
		
		return orderCategoryId2CountRDD;
	}
	
	/**
	 * 获取各个品类的支付次数RDD
	 * @param sessionid2detailRDD
	 * @return
	 */
	private static JavaPairRDD<Long, Long> getPayCategoryId2CountRDD(
			JavaPairRDD<String, Row> sessionid2detailRDD) {
		JavaPairRDD<String, Row> payActionRDD = sessionid2detailRDD.filter(
				
				new Function<Tuple2<String,Row>, Boolean>() {

					private static final long serialVersionUID = 1L;
		
					@Override
					public Boolean call(Tuple2<String, Row> tuple) throws Exception {
						Row row = tuple._2;  
						// 这里报了一个 java.lang.NumberFormatException: For input string: "null"
						// 是由于文件导入hive时,文件中的null被当成了null字符串处理了,代码这里不仅需要过null还要过滤null字符串
						return row.getString(10) != null ? true : false;
					}
					
				});
		
		JavaPairRDD<Long, Long> payCategoryIdRDD = payActionRDD.flatMapToPair(
				
				new PairFlatMapFunction<Tuple2<String,Row>, Long, Long>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Iterable<Tuple2<Long, Long>> call(
							Tuple2<String, Row> tuple) throws Exception {
						Row row = tuple._2;
						String payCategoryIds = row.getString(10);
						String[] payCategoryIdsSplited = payCategoryIds.split(",");  
						
						List<Tuple2<Long, Long>> list = new ArrayList<Tuple2<Long, Long>>();
						
						for(String payCategoryId : payCategoryIdsSplited) {
							list.add(new Tuple2<Long, Long>(Long.valueOf(payCategoryId), 1L));  
						}
						
						return list;
					}
					
				});
		
		JavaPairRDD<Long, Long> payCategoryId2CountRDD = payCategoryIdRDD.reduceByKey(
				
				new Function2<Long, Long, Long>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Long call(Long v1, Long v2) throws Exception {
						return v1 + v2;
					}
					
				});
		
		return payCategoryId2CountRDD;
	}
	
	/**
	 * 连接品类RDD与数据RDD
	 * @param categoryidRDD
	 * @param clickCategoryId2CountRDD
	 * @param orderCategoryId2CountRDD
	 * @param payCategoryId2CountRDD
	 * @return
	 */
	private static JavaPairRDD<Long, String> joinCategoryAndData(
			JavaPairRDD<Long, Long> categoryIdRDD,
			JavaPairRDD<Long, Long> clickCategoryId2CountRDD,
			JavaPairRDD<Long, Long> orderCategoryId2CountRDD,
			JavaPairRDD<Long, Long> payCategoryId2CountRDD) {
		// 解释一下，如果用leftOuterJoin，就可能出现，右边那个RDD中，join过来时，没有值
		// 所以Tuple中的第二个值用Optional<Long>类型，就代表，可能有值，可能没有值
		JavaPairRDD<Long, Tuple2<Long, Optional<Long>>> tmpJoinRDD = categoryIdRDD.leftOuterJoin(clickCategoryId2CountRDD);
		
		JavaPairRDD<Long, String> tmpMapRDD = tmpJoinRDD.mapToPair(
				
				new PairFunction<Tuple2<Long,Tuple2<Long,Optional<Long>>>, Long, String>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Tuple2<Long, String> call(
							Tuple2<Long, Tuple2<Long, Optional<Long>>> tuple)
							throws Exception {
						long categoryid = tuple._1;
						Optional<Long> optional = tuple._2._2;
						long clickCount = 0L;
						
						if(optional.isPresent()) {
							clickCount = optional.get();
						}
						
						String value = Constants.FIELD_CATEGORY_ID + "=" + categoryid + "|" + 
								Constants.FIELD_CLICK_COUNT + "=" + clickCount;
						
						return new Tuple2<Long, String>(categoryid, value);  
					}
					
				});
		
		tmpMapRDD = tmpMapRDD.leftOuterJoin(orderCategoryId2CountRDD).mapToPair(
				
				new PairFunction<Tuple2<Long,Tuple2<String,Optional<Long>>>, Long, String>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Tuple2<Long, String> call(
							Tuple2<Long, Tuple2<String, Optional<Long>>> tuple)
							throws Exception {
						long categoryid = tuple._1;
						String value = tuple._2._1;
						
						Optional<Long> optional = tuple._2._2;
						long orderCount = 0L;
						
						if(optional.isPresent()) {
							orderCount = optional.get();
						}
						
						value = value + "|" + Constants.FIELD_ORDER_COUNT + "=" + orderCount;  
						
						return new Tuple2<Long, String>(categoryid, value);  
					}
				
				});
		
		tmpMapRDD = tmpMapRDD.leftOuterJoin(payCategoryId2CountRDD).mapToPair(
				
				new PairFunction<Tuple2<Long,Tuple2<String,Optional<Long>>>, Long, String>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Tuple2<Long, String> call(
							Tuple2<Long, Tuple2<String, Optional<Long>>> tuple)
							throws Exception {
						long categoryid = tuple._1;
						String value = tuple._2._1;
						
						Optional<Long> optional = tuple._2._2;
						long payCount = 0L;
						
						if(optional.isPresent()) {
							payCount = optional.get();
						}
						
						value = value + "|" + Constants.FIELD_PAY_COUNT + "=" + payCount;  
						
						return new Tuple2<Long, String>(categoryid, value);  
					}
				
				});
		
		return tmpMapRDD;
	}
	
	/**
	 * 获取top10活跃session
	 * @param taskid
	 * @param sessionid2detailRDD
	 */
	private static void getTop10Session(
			JavaSparkContext sc,
			final long taskId,
			List<Tuple2<CategorySortKey, String>> top10CategoryList,
			JavaPairRDD<String, Row> sessionId2DetailRDD) {
		/**
		 * 第一步：将top10热门品类的id，生成一份RDD
		 */
		List<Tuple2<Long, Long>> top10CategoryIdList = 
				new ArrayList<Tuple2<Long, Long>>();
		
		for(Tuple2<CategorySortKey, String> category : top10CategoryList) {
			long categoryId = Long.valueOf(StringUtils.getFieldFromConcatString(
					category._2, "\\|", Constants.FIELD_CATEGORY_ID));
			top10CategoryIdList.add(new Tuple2<Long, Long>(categoryId, categoryId));  
		}
		
		JavaPairRDD<Long, Long> top10CategoryIdRDD = 
				sc.parallelizePairs(top10CategoryIdList);
		
		/**
		 * 第二步：计算top10品类被各session点击的次数
		 */
		JavaPairRDD<String, Iterable<Row>> sessionId2DetailsRDD =
				sessionId2DetailRDD.groupByKey();
		
		JavaPairRDD<Long, String> categoryId2SessionCountRDD = sessionId2DetailsRDD.flatMapToPair(
				
				new PairFlatMapFunction<Tuple2<String,Iterable<Row>>, Long, String>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Iterable<Tuple2<Long, String>> call(
							Tuple2<String, Iterable<Row>> tuple) throws Exception {
						String sessionId = tuple._1;
						Iterator<Row> iterator = tuple._2.iterator();
						
						Map<Long, Long> categoryCountMap = new HashMap<Long, Long>();
						
						// 计算出该session，对每个品类的点击次数
						while(iterator.hasNext()) {
							Row row = iterator.next();
							
							if(row.get(6) != null) {
								long categoryId = row.getLong(6);
								
								Long count = categoryCountMap.get(categoryId);
								if(count == null) {
									count = 0L;
								}
								
								count++;
								
								categoryCountMap.put(categoryId, count);
							}
						}
						
						// 返回结果，<categoryid,sessionid,count>格式
						List<Tuple2<Long, String>> list = new ArrayList<Tuple2<Long, String>>();
						
						for(Map.Entry<Long, Long> categoryCountEntry : categoryCountMap.entrySet()) {
							long categoryId = categoryCountEntry.getKey();
							long count = categoryCountEntry.getValue();
							String value = sessionId + "," + count;
							list.add(new Tuple2<Long, String>(categoryId, value));  
						}
						
						return list;
					}
					
				}) ;
		
		// 获取到to10热门品类，被各个session点击的次数
		JavaPairRDD<Long, String> top10CategorySessionCountRDD = top10CategoryIdRDD
				.join(categoryId2SessionCountRDD)
				.mapToPair(new PairFunction<Tuple2<Long,Tuple2<Long,String>>, Long, String>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Tuple2<Long, String> call(
							Tuple2<Long, Tuple2<Long, String>> tuple)
							throws Exception {
						return new Tuple2<Long, String>(tuple._1, tuple._2._2);
					}
					
				});
		
		/**
		 * 第三步：分组取TopN算法实现，获取每个品类的top10活跃用户
		 */
		JavaPairRDD<Long, Iterable<String>> top10CategorySessionCountsRDD =
				top10CategorySessionCountRDD.groupByKey();
		
		JavaPairRDD<String, String> top10SessionRDD = top10CategorySessionCountsRDD.flatMapToPair(
				
				new PairFlatMapFunction<Tuple2<Long,Iterable<String>>, String, String>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Iterable<Tuple2<String, String>> call(
							Tuple2<Long, Iterable<String>> tuple)
							throws Exception {
						long categoryId = tuple._1;
						Iterator<String> iterator = tuple._2.iterator();
						
						// 定义取topn的排序数组
						String[] top10Sessions = new String[10];   
						
						while(iterator.hasNext()) {
							String sessionCount = iterator.next();
							long count = Long.valueOf(sessionCount.split(",")[1]);  
							
							// 遍历排序数组
							for(int i = 0; i < top10Sessions.length; i++) {
								// 如果当前i位，没有数据，那么直接将i位数据赋值为当前sessionCount
								if(top10Sessions[i] == null) {
									top10Sessions[i] = sessionCount;
									break;
								} else {
									long _count = Long.valueOf(top10Sessions[i].split(",")[1]);  
									
									// 如果sessionCount比i位的sessionCount要大
									if(count > _count) {
										// 从排序数组最后一位开始，到i位，所有数据往后挪一位
										for(int j = 9; j > i; j--) {
											top10Sessions[j] = top10Sessions[j - 1];
										}
										// 将i位赋值为sessionCount
										top10Sessions[i] = sessionCount;
										break;
									}
									
									// 比较小，继续外层for循环
								}
							}
						}
						
						// 将数据写入MySQL表
						List<Tuple2<String, String>> list = new ArrayList<Tuple2<String, String>>();
						
						for(String sessionCount : top10Sessions) {
							if(sessionCount != null) {
								String sessionId = sessionCount.split(",")[0];
								long count = Long.valueOf(sessionCount.split(",")[1]);  
								
								// 将top10 session插入MySQL表
								Top10Session top10Session = new Top10Session();
								top10Session.setTaskId(taskId);  
								top10Session.setCategoryId(categoryId);  
								top10Session.setSessionId(sessionId);  
								top10Session.setClickCount(count);  
								
								ITop10SessionDao top10SessionDao = DaoFactory.getTop10SessionDao();
								top10SessionDao.insert(top10Session);  
								
								// 放入list
								list.add(new Tuple2<String, String>(sessionId, sessionId));
							}
						}
						
						return list;
					}
					
				});
		
		/**
		 * 第四步：获取top10活跃session的明细数据，并写入MySQL
		 */
		JavaPairRDD<String, Tuple2<String, Row>> sessionDetailRDD =
				top10SessionRDD.join(sessionId2DetailRDD);  
		sessionDetailRDD.foreach(new VoidFunction<Tuple2<String,Tuple2<String,Row>>>() {  
			
			private static final long serialVersionUID = 1L;

			@Override
			public void call(Tuple2<String, Tuple2<String, Row>> tuple) throws Exception {
				Row row = tuple._2._2;
				
				SessionDetail sessionDetail = new SessionDetail();
				sessionDetail.setTaskid(taskId);  
				sessionDetail.setUserid(row.getLong(1));  
				sessionDetail.setSessionid(row.getString(2));  
				sessionDetail.setPageid(row.getLong(3));  
				sessionDetail.setActionTime(row.getString(4));
				sessionDetail.setSearchKeyword(row.getString(5));  
				sessionDetail.setClickCategoryId(row.getLong(6));  
				sessionDetail.setClickProductId(row.getLong(7));   
				sessionDetail.setOrderCategoryIds(row.getString(8));  
				sessionDetail.setOrderProductIds(row.getString(9));  
				sessionDetail.setPayCategoryIds(row.getString(10)); 
				sessionDetail.setPayProductIds(row.getString(11));  
				
				ISessionDetailDao sessionDetailDao = DaoFactory.getSessionDetailDao();
				sessionDetailDao.insert(sessionDetail);  
			}
		});
	}
	
}
