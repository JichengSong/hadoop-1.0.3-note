package com.renren;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.BZip2Codec;
import org.apache.hadoop.io.compress.CompressionInputStream;
import org.apache.hadoop.io.compress.CompressionOutputStream;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.LineRecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.LongWritable;

public class Bz2Test {

	int total = 0;
	Path inPath;
	Configuration conf;
	JobConf jobConf;

	public Bz2Test(String bz2FilePath) {
		// 1.conf
		Configuration conf = new Configuration();
		conf.set("fs.default.name", "hdfs://YZSJHL19-62.opi.com");
		this.jobConf = new JobConf(conf);
		// 2.path
		this.inPath = new Path(bz2FilePath);
	}

	public void execCount() throws IOException {
		
		  // Start worker thread pool
		  ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
		    1, 128, 600, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
		
		// 1.InputFormat
		TextInputFormat.setInputPaths(this.jobConf, this.inPath);
		final TextInputFormat fmt = new TextInputFormat();
		fmt.configure(jobConf);
		// 2.splits
		FileSplit[] splits = (FileSplit[]) fmt.getSplits(jobConf, 0);
		Thread[] threads = new Thread[splits.length];
		for (int i = 0; i < splits.length; i++) {
			final FileSplit split = splits[i];

			Thread t = new Thread("Thread-split-" + split.getStart()) {

				public void run() {
					System.out.println(this.getName()+" begin");
					// 1.reader of split
					LineRecordReader reader;
					try {
						
						
						TextInputFormat fmt = new TextInputFormat();
						fmt.configure(jobConf);
						
						reader = (LineRecordReader) fmt.getRecordReader(split,
								jobConf, Reporter.NULL);
						// 2.count
						int count = 0;
						LongWritable key = reader.createKey();
						Text value = reader.createValue();
						boolean finished = false;
						while (finished = reader.next(key, value)) {
//							if (count == 0)
//								System.out.println(count + " line="
//										+ value.toString());
							count++;
						}
						add(count);
						System.out.println(this.getName()+",total="+total);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}

				}
			};
			threads[i] = t;
			//t.start();
		}
		for (int i = 0; i < splits.length; i++) {
			
				//threads[i].join();
				threadPool.execute(threads[i]);
		}
		//threadPool.shutdown();
	}

	synchronized void add(int count) {
		this.total += count;
	}

	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		// TODO Auto-generated method stub

		System.out.println("begin");
		Path inputPath = new Path("hdfs://10.4.19.62/user/spark/bz2/c.bz2");
		Bz2Test  bz2Test =new Bz2Test("hdfs://10.4.19.62/user/spark/bz2/c.bz2");
		bz2Test.execCount();
		
		System.out.println("end");

		
//		Configuration conf = new Configuration();
//
//		conf.set("fs.default.name", "hdfs://YZSJHL19-62.opi.com");
//		JobConf jobConf = new JobConf(conf);
//
//		// jobConf.addResource(new FileInputStream(new
//		// File("conf/core-site.xml")));
//		// Path inputPath = new Path(
//		// "hdfs://YZSJHL19-62.opi.com/warehouse/test_iplog/log_date=5kw.dp.bz2");
//		Path inputPath = new Path("hdfs://10.4.19.62/user/spark/bz2/c.bz2");
//
//		// 1.InputFormat
//		TextInputFormat.setInputPaths(jobConf, inputPath);
//		TextInputFormat fmt = new TextInputFormat();
//		fmt.configure(jobConf);
//
//		FileSplit[] splits = (FileSplit[]) fmt.getSplits(jobConf, 0);
//		System.out.println("splits.length=" + splits.length);
//		int total = 0;
//		for (FileSplit split : splits) {
//			System.out.println("split=" + split.toString());
//
//			// 1.reader of split
//			LineRecordReader reader = (LineRecordReader) fmt.getRecordReader(
//					split, jobConf, Reporter.NULL);
//			// read
//			int count = 0;
//			LongWritable key = reader.createKey();
//			Text value = reader.createValue();
//
//			boolean finished = false;
//			while (finished = reader.next(key, value)) {
//				if (count == 0)
//					System.out.println(count + " line=" + value.toString());
//				count++;
//			}
//			System.out.println("count=" + count);
//			total += count;
//		}
//		System.out.println("_________________________________________________");
//		System.out.println("total=" + total);
//		System.out.println("the end.");
		
	}

}
