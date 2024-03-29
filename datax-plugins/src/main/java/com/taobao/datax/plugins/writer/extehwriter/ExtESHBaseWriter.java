package com.taobao.datax.plugins.writer.extehwriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.log4j.Logger;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.codehaus.jackson.map.ObjectMapper;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.node.Node;

import com.taobao.datax.common.constants.Constants;
import com.taobao.datax.common.exception.DataExchangeException;
import com.taobao.datax.common.plugin.Line;
import com.taobao.datax.common.plugin.LineReceiver;
import com.taobao.datax.common.plugin.PluginParam;
import com.taobao.datax.common.plugin.PluginStatus;
import com.taobao.datax.common.plugin.Writer;
import com.taobao.datax.common.util.ETLDateUtils;
import com.taobao.datax.common.util.ETLStringUtils;

public class ExtESHBaseWriter extends Writer {
	private static final int DEFAULT_BUFFER_SIZE = 16 * 1024 * 1024;

	private String tablename;
	
	private String exttablename;

	private String encode = "utf-8";

	private ExtESHBaseProxy proxy;

	private int delMode;

	private boolean autoflush;

	private String hbase_conf;

	private String[] columnNames;

	private int bufferSize;

	private String[] families = null;

	private String[] qualifiers = null;

	private int[] columnIndexes;

	private int rowkeyIndex;

	private String rowKeyRule = "0";

	
	// rowid设置方式
	private int rowKeyRuleValue = 0;
	// 求余数方式计算rowId的参数
	private Integer fieldvalueLen = 15;
	private Integer remainder = 200;
	private Integer remainderLen = 3;
	private String rowIdPattern = "yyyy-MM-dd";
	// 多个字段组合方式
	private int[] rowIdIdx;// 字段index值，数字集合
	private Logger logger = Logger.getLogger(ExtESHBaseWriter.class);

	private String oneObjFamilyName = "cf";

	// elasticsearch 参数 群组名称
	private String clustername;
	// 索引名称
	private String indexname;
	
	private String extindexname;
	// 分片数目
	private int number_of_shards;
	// 索引副本数目
	private int number_of_replicas;
	// 类型名称(类似表名)
	private String typename;
	
	private String exttypename;
	// 批量提交的记录数
	private int bulksize;
	// 结构mapping定义，json字符串文件
	private String mapping_xml;
	// 删除模式；由用户进行选择，0 写入前不删除，如果不存在，建立新表，覆盖文件 1 上传数据之前truncate原type;2
	// 上传数据之前delete原type
	private int esdelMode;

	// 要写的字段数组
	private String[] escolumnNames;

	private String hosts;
	// 要读的字段id数组
	private int[] escolumnIndexes;
	//要读的字段id转义集合,','间隔
	private String column_escape_index;
	//是否转义的定义
	private int[] columnEscapeIndexes;
	// _id指定的字段
	private int uniquekey = -1;

	ObjectMapper mapper = new ObjectMapper();

	private Node node;

	private Client client;

	@Override
	public List<PluginParam> split(PluginParam param) {
		ExtESHBaseWriterSplitter spliter = new ExtESHBaseWriterSplitter();
		spliter.setParam(param);
		spliter.init();
		return spliter.split();
	}

	@Override
	public int prepare(PluginParam param) {
		this.logger.info("DataX ESHBaseWriter do prepare work .");

		try {
			switch (this.delMode) {
			case 0:
				this.logger.info("ESHBaseWriter reserves old data .");
				break;
			case 1:
				truncateTable();
				break;
			case 2:
				deleteTables();
				break;
			default:
				String msg = "ESHBaseWriter delmode is not correct .";
				this.logger.error(msg);
				throw new IllegalArgumentException(msg);
			}
		} catch (IOException e) {
			try {
				proxy.close();
			} catch (IOException e1) {
			}
		}
		
		this.logger.info("DataX ElasticSearchWriter do prepare work .");
		//根据模式删除清理已有表
		try {
			switch (this.esdelMode) {
			case 0:
				createESTable();
				this.logger.info("ElasticSearchWriter reserves old data .");
				break;
			case 1:
				truncateESTable();
				break;
			case 2:
				deleteESTables();
				break;
			case 3:
				break;
			default:
				String msg = "ElasticSearchWriter delmode is not correct .";
				this.logger.error(msg);
				throw new IllegalArgumentException(msg);
			}
		} catch (Exception e) {
			e.printStackTrace();
			try {
				proxy.close();
			} catch (Exception e1) {
			}
		}
		return this.finish();
	}

	@Override
	public int post(PluginParam param) {
		return PluginStatus.SUCCESS.value();
	}

	@Override
	public int init() {
		tablename = param.getValue(ParamKey.htable);
		hbase_conf = param.getValue(ParamKey.hbase_conf);
		encode = param.getValue(ParamKey.encoding, "UTF-8");
		delMode = param.getIntValue(ParamKey.delMode, 0);
		autoflush = param.getBoolValue(ParamKey.autoFlush, false);
		bufferSize = param.getIntValue(ParamKey.bufferSize, DEFAULT_BUFFER_SIZE);
		rowKeyRule = param.getValue(ParamKey.rowKeyRule, this.rowKeyRule);
		exttablename=param.getValue(ParamKey.exthtable);
		
		// 分解参数规则
		if ("0".equalsIgnoreCase(rowKeyRule)) {// 直接使用指定字段作为rowId
			this.rowKeyRuleValue = 0;
		} else if (rowKeyRule.startsWith("1")) {// 使用求余数方式计算rowId
			this.rowKeyRuleValue = 1;
			String[] rules = rowKeyRule.split(",");
			fieldvalueLen = NumberUtils.toInt(rules[1]);
			remainder = NumberUtils.toInt(rules[2]);
			remainderLen = NumberUtils.toInt(rules[3]);
		} else if (rowKeyRule.startsWith("2")) {// 使用组合字段方式计算rowId
			this.rowKeyRuleValue = 2;
			String[] rules = rowKeyRule.split(",");
			int id = 0;
			String[] rids=rules[1].split("/");
			this.rowIdIdx=new int[rids.length];
			for (String idx : rids) {
				this.rowIdIdx[id] = NumberUtils.toInt(idx);
				id++;
			}
		} else if (rowKeyRule.startsWith("3")) {
			this.rowKeyRuleValue = 3;
			String[] rules = rowKeyRule.split(",");
			rowIdPattern = rules[1];
		}
		/* if user does not set rowkey index, use 0 for default. */
		rowkeyIndex = param.getIntValue(ParamKey.rowkey_index, 0, 0, Integer.MAX_VALUE);

		if (bufferSize < 0 || bufferSize >= 32 * 1024 * 1024) {
			throw new IllegalArgumentException("buffer size must be [0M-32M] .");
		}
		// 获取要写入hbase的字段，使用cf:fieldName方式写，","间隔
		columnNames = param.getValue(ParamKey.column_name).split(",");
		families = new String[columnNames.length];
		qualifiers = new String[columnNames.length];
		for (int i = 0; i < columnNames.length; i++) {
			if (!columnNames[i].contains(":")) {
				throw new IllegalArgumentException(String.format("Column %s must be like 'family:qualifier'", columnNames[i]));
			}
			String[] tmps = columnNames[i].split(":");
			families[i] = tmps[0].trim();
			qualifiers[i] = tmps[1].trim();
		}
		/*
		 * test if user has set column_value_index(读取的字段集合，按照数字集合，从0开始，使用","间隔)
		 * . if set, use the value set by user if not set, use the default
		 * value, [1, length of column_name]
		 */
		if (param.hasValue(ParamKey.column_value_index)) {
			String[] indexes = param.getValue(ParamKey.column_value_index).split(",");
			if (indexes.length != columnNames.length) {
					String msg = String.format("HBase column index is different form column name: \nColumnName %s\nColumnIndex %s\n", param.getValue(ParamKey.column_name),
							param.getValue(ParamKey.column_value_index));
					logger.error(msg);
					throw new IllegalArgumentException(msg);
			}
			columnIndexes = new int[indexes.length];
			for (int i = 0; i < indexes.length; i++) {
				columnIndexes[i] = Integer.valueOf(indexes[i]);
			}
		} else {// 默认从1到字段长度
			columnIndexes = new int[columnNames.length];
			for (int i = 0; i < rowkeyIndex; i++) {
				columnIndexes[i] = i;
			}
			for (int i = rowkeyIndex; i < columnIndexes.length; i++) {
				columnIndexes[i] = i + 1;
			}
		}
		// elasticsearch参数
		this.clustername = param.getValue(ParamKey.clustername);
		this.hosts = param.getValue(ParamKey.hosts);
		this.indexname = param.getValue(ParamKey.indexname);
		this.extindexname = param.getValue(ParamKey.extindexname);

		this.number_of_shards = param.getIntValue(ParamKey.number_of_shards, 5);
		this.number_of_replicas = param.getIntValue(ParamKey.number_of_replicas, 1);
		this.typename = param.getValue(ParamKey.typename);
		this.exttypename = param.getValue(ParamKey.exttypename);
		
		this.bulksize = param.getIntValue(ParamKey.bulksize, 500);
		this.mapping_xml = param.getValue(ParamKey.mapping_xml);
		this.uniquekey = param.getIntValue(ParamKey.uniquekey, -1);
		if (this.mapping_xml != null) {
			FileInputStream is = null;
			try {
				File file = new File(mapping_xml);
				is = new FileInputStream(file);
				int size = (int) file.length();
				byte[] bytes = new byte[size];
				is.read(bytes);
				String content = new String(bytes, "UTF-8");
				this.mapping_xml = content;
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				if (is != null) {
					try {
						is.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
		this.esdelMode = param.getIntValue(ParamKey.es_delMode, 0);
		this.escolumnNames = param.getValue(ParamKey.es_column_name).split(",");
		if (param.hasValue(ParamKey.es_column_value_index)) {
			String[] indexes = param.getValue(ParamKey.es_column_value_index).split(",");
			if (indexes.length != escolumnNames.length) {
				String msg = String.format("elasticsearch column index is different form column name: \nColumnName %s\nColumnIndex %s\n", param.getValue(ParamKey.es_column_name),
						param.getValue(ParamKey.es_column_value_index));
				logger.error(msg);
				throw new IllegalArgumentException(msg);
			}
			this.escolumnIndexes = new int[indexes.length];
			for (int i = 0; i < indexes.length; i++) {
				escolumnIndexes[i] = Integer.valueOf(indexes[i]);
			}
		} else {// 默认从1到字段长度
			this.escolumnIndexes = new int[escolumnNames.length];
		}
		try{
			this.column_escape_index=param.getValue(ParamKey.column_escape_index);
			if (param.hasValue(ParamKey.column_escape_index)) {
				String[] indexes = column_escape_index.split(",");
				if (indexes.length != columnNames.length) {
						String msg = String.format("elasticsearch column escape index is different form column name: \nColumnName %s\nColumnIndex %s\n",
										param.getValue(ParamKey.column_name),
										param.getValue(ParamKey.column_escape_index));
						logger.error(msg);
						throw new IllegalArgumentException(msg);
				}
				this.columnEscapeIndexes = new int[indexes.length];
				for (int i = 0; i < indexes.length; i++) {
					columnEscapeIndexes[i] = Integer.valueOf(indexes[i]);
				}
			} else {//默认从1到字段长度
				this.columnEscapeIndexes = new int[columnNames.length];
			}
		}catch(Exception ex){
			this.columnEscapeIndexes = new int[columnNames.length];
		}
		String[] hosts = ETLStringUtils.split(this.hosts, ";");
		int id = 0;
		InetSocketTransportAddress[] transportAddress = new InetSocketTransportAddress[hosts.length];
		for (String host : hosts) {
			String[] hp = ETLStringUtils.split(host, ":");
			transportAddress[id] = new InetSocketTransportAddress(hp[0], NumberUtils.toInt(hp[1], 9300));
			id++;
		}
		Settings settings = ImmutableSettings.settingsBuilder().put("cluster.name", this.clustername).put("client.transport.sniff", true).build();
		client = new TransportClient(settings).addTransportAddresses(transportAddress);
	
		try {
			this.proxy = ExtESHBaseProxy.newProxy(hbase_conf, tablename,exttablename,client,this.bulksize,this.indexname,this.extindexname,this.typename,this.exttypename);
			this.proxy.setAutoFlush(autoflush);
			this.proxy.setBufferSize(bufferSize);
			if (null == this.proxy || !this.proxy.check()) {
				throw new DataExchangeException("HBase Client initilize failed .");
			}
			return PluginStatus.SUCCESS.value();
		} catch (IOException e) {
			if (null != proxy) {
				try {
					proxy.close();
				} catch (IOException e1) {
				}
			}
			throw new DataExchangeException(e.getCause());
		}
	}

	@Override
	public int connect() {
		return PluginStatus.SUCCESS.value();
	}

	@Override
	public int startWrite(LineReceiver receiver) {
		Line line;
		int fieldNum;

		/*
		 * NOTE: field numbers in each line may be different
		 */
		String lineValue=null;
		proxy.prepareEs();
		String currRowid=null;
		while ((line = receiver.getFromReader()) != null) {
			try {
				fieldNum = line.getFieldNum();
				if (null == line.checkAndGetField(rowkeyIndex)) {
					throw new IOException("rowkey is missing .");
				}

				if (0 == fieldNum || 1 == fieldNum) {
					logger.warn("ESHBaseWriter meets an empty line, ignore it .");
					continue;
				}
				// rowKey计算方式，可以自己定义增加多种方式
				switch (rowKeyRuleValue) {
				case 0:// 直接使用指定字段作为rowId
				{
					currRowid=line.getField(rowkeyIndex);
					proxy.prepare(currRowid.getBytes(encode));
					break;
				}
				case 1:// 使用求余数方式计算rowId
				{
					String rowId = line.getField(rowkeyIndex);
					if (!NumberUtils.isDigits(rowId)) {
						logger.warn("rowid指定字段不是数字，不能求余数:" + rowId);
						continue;
					}
					// if (ETLStringUtils.getRemainderRowId(Long.valueOf(rowId),
					// fieldvalueLen, remainder, remainderLen)=="000000000000"){
					// System.out.println(rowId);
					// }
					currRowid=ETLStringUtils.getRemainderRowId(Long.valueOf(rowId), fieldvalueLen, remainder, remainderLen);
					proxy.prepare(currRowid.getBytes(encode));
					break;
				}
				case 2:// 使用组合字段方式计算rowId
				{
					List<String> fieldValues = new ArrayList<String>();
					for (int rowIdx : this.rowIdIdx) {
						fieldValues.add(line.getField(rowIdx));
					}
					currRowid=ETLStringUtils.getHbaseRowId(fieldValues);
					proxy.prepare(currRowid.getBytes(encode));
					break;
				}
				case 3:// 日期+自增长方式
				{
					String rowId = line.getField(rowkeyIndex);
					currRowid=ETLStringUtils.getDateIncrementRowId(this.tablename, rowId, rowIdPattern);
					proxy.prepare(currRowid.getBytes(encode));
					break;
				}
				}
				for (int i = 0; i < columnIndexes.length; i++) {
					if (null == line.checkAndGetField(columnIndexes[i])) {
						continue;
					}
					if (StringUtils.isNotEmpty(line.getField(columnIndexes[i]))) {
						proxy.add(families[i].getBytes(encode), qualifiers[i].getBytes(encode), line.getField(columnIndexes[i]).getBytes(encode));
					}
				}
				Map esmap=new ConcurrentHashMap();
				//Map tepMap=new ConcurrentHashMap();
				for (int i = 0; i < this.escolumnIndexes.length; i++) {
					if (ETLStringUtils.isEmpty(line.checkAndGetField(escolumnIndexes[i]))) {
						continue;
					}
					if (StringUtils.isNotEmpty(line.getField(escolumnIndexes[i]))){
						lineValue=ETLStringUtils.trim(line.getField(escolumnIndexes[i]));
						if (ETLStringUtils.getJSONType(lineValue)==ETLStringUtils.JSON_TYPE.JSON_TYPE_STRING){
							if (1==columnEscapeIndexes[i]){//是否转义
								esmap.put(this.escolumnNames[i],QueryParser.escape(lineValue));
							}else{
								esmap.put(this.escolumnNames[i],lineValue);
							}
						}else if (ETLStringUtils.getJSONType(lineValue)==ETLStringUtils.JSON_TYPE.JSON_TYPE_ARRAY){
							if (1==columnEscapeIndexes[i]){//是否转义
								esmap.put(this.escolumnNames[i],escapeList(mapper.readValue(lineValue, List.class)));
							}else{
								esmap.put(this.escolumnNames[i],mapper.readValue(lineValue, List.class));
							}
						}else if (ETLStringUtils.getJSONType(lineValue)==ETLStringUtils.JSON_TYPE.JSON_TYPE_OBJECT){
							if (1==columnEscapeIndexes[i]){//是否转义
								esmap.put(this.escolumnNames[i],escapeMap(mapper.readValue(lineValue, Map.class)));
							}else{
								esmap.put(this.escolumnNames[i],mapper.readValue(lineValue, Map.class));
							}
						}
					}
				}
				
				if (this.uniquekey>=0){
					//System.out.println("id:--"+line.getField(this.uniquekey));
					if (StringUtils.isNotEmpty(currRowid)) {
						proxy.insert(currRowid,esmap);
					}else{
						proxy.insert(null,esmap);
					}
				}else{
					proxy.insert(null,esmap);
				}
				this.monitor.lineSuccess();
			} catch (IOException e) {
				this.getMonitor().lineFail(e.getMessage());
			}
		}

		return PluginStatus.SUCCESS.value();
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Map escapeMap(Map mapStr){
		Iterator<Map.Entry> iter=mapStr.entrySet().iterator();
		while(iter.hasNext()){
			Map.Entry entry=iter.next();
			if (entry.getValue() instanceof String){
				entry.setValue(QueryParser.escape((String)entry.getValue()));
			}else{
				break;
			}
		}
		return mapStr;
	}

	@SuppressWarnings("rawtypes")
	private List escapeList(List arrayStr){
		for(int i=0;i<arrayStr.size();i++){
			Object v=arrayStr.get(i);
			if (v instanceof String){
				arrayStr.remove(i);
				arrayStr.add(i, QueryParser.escape((String)v));
			}else{
				break;
			}
		}
		return arrayStr;
	}

	public String getDateStr(String dateStr) {
		Date day = ETLDateUtils.parseDate(dateStr, "yyyy-MM-dd HH:mm:ss SSS");
		if (day == null) {
			return dateStr;
		}
		return ETLDateUtils.formatDate(day, Constants.DATE_FORMAT_SSS);
	}

	public static int daysBetween(String smdate, String bdate) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		Calendar cal = Calendar.getInstance();
		try {
			cal.setTime(sdf.parse(smdate));
		} catch (ParseException e) {
			e.printStackTrace();
		}
		long time1 = cal.getTimeInMillis();
		try {
			cal.setTime(sdf.parse(bdate));
		} catch (ParseException e) {
			e.printStackTrace();
		}
		long time2 = cal.getTimeInMillis();
		long between_days = (time2 - time1) / (1000 * 3600 * 24);

		return Integer.parseInt(String.valueOf(between_days));
	}

	@Override
	public int commit() {
		this.logger.info("ESHBaseWriter starts to commit records .");
		try {
			proxy.flush();
			return PluginStatus.SUCCESS.value();
		} catch (IOException e) {
			try {
				proxy.close();
			} catch (IOException e1) {
			}
			throw new DataExchangeException(e.getCause());
		}
	}

	@Override
	public int finish() {
		try {
			proxy.close();
			client.close();
			//node.close();
			client=null;
			node=null;
			return PluginStatus.SUCCESS.value();
		} catch (IOException e) {
			throw new DataExchangeException(e.getCause());
		}
	}
	
	/**
	 * 新建定义的es type作为表
	 */
	private void createESTable() throws Exception{
		this.logger.info(String.format(
				"ElasticSearchWriter begins to create table %s .", this.indexname,this.typename));
		proxy.createESTable(this.indexname, number_of_shards, number_of_replicas, 
				 typename,  bulksize,  mapping_xml);
	}
	
	private void deleteESTables() throws Exception {
		this.logger.info(String.format(
				"ElasticSearchWriter begins to delete table %s .", this.indexname,this.typename));
		proxy.deleteESTable(this.indexname,typename);
	}

	private void truncateESTable() throws Exception {
		this.logger.info(String.format(
				"ElasticSearchWriter begins to truncate table %s .",this.indexname,this.typename));
		proxy.truncateESTable(indexname,number_of_shards, number_of_replicas, typename,this.mapping_xml);
	}

	private void deleteTables() throws IOException {
		this.logger.info(String.format("HBasWriter begins to delete table %s .", this.tablename));
		proxy.deleteTable();
	}

	private void truncateTable() throws IOException {
		this.logger.info(String.format("HBasWriter begins to truncate table %s .", this.tablename));
		proxy.truncateTable();
	}

}
