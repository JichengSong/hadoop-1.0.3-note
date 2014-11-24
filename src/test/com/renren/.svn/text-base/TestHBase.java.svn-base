package com.renren;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.mortbay.log.Log;

public class TestHBase {

	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		// TODO Auto-generated method stub
		System.out.println("Begin Test HBase");
		Configuration cfg = HBaseConfiguration
				.create();
		HTable table = new HTable(cfg, "test_1");

		table.setAutoFlush(false);

		Scan scan = new Scan();
		// 执行Scan操作，打印结果
		ResultScanner scanner = table.getScanner(scan);

		for (Result res : scanner) {
			System.out.println(res.toString());
		}

		table.close();
		Log.getLogger("HELLO").info("a", "a", "a");

	}

}
