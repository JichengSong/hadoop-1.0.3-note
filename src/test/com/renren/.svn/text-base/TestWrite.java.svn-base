package com.renren;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.BlockDecompressorStream;
import org.apache.hadoop.io.compress.SnappyCodec;
import org.apache.hadoop.io.compress.bzip2.BZip2DummyDecompressor;

public class TestWrite {

	public static void main(String[] args) throws IOException {
		// TODO Auto-generated method stub

		System.out.println("begin .........");
		Configuration conf = new Configuration();
		
//		conf.addResource("file:///data/spark/workspace/hadoop-1.0.3.note/conf/core-site.xml");
//		conf.addResource(new FileInputStream(new File("conf/core-site.xml")));
//		conf.addResource(new FileInputStream(new File("conf/hdfs-site.xml")));
//		conf.addResource(new FileInputStream(new File("conf/mapred-site.xml")));

		//
		Path outPath = new Path("hdfs://10.4.19.62:8020/user/spark/bz2/f2.txt");

		// FileSystem
		FileSystem fs = outPath.getFileSystem(conf);

		// 创建输出流
		FSDataOutputStream outStream = fs.create(outPath, true);
		String line;
		

		for (int i = 0; i < 10; i++) {
			line = "hello world" + i+"\n";
			outStream.writeBytes(line);
			System.out.print("write line:"+line);
			outStream.flush();	
		}
		
		fs.close();
		

		// // get decompression stream
		// BlockDecompressorStream blockDecompressorStream = new
		// BlockDecompressorStream(
		// inStream, new BZip2DummyDecompressor(), 1024);
		// try {
		// assertEquals("return value is not -1", -1,
		// blockDecompressorStream.read());
		// } catch (IOException e) {
		// System.out
		// .println("Exception occurred when execute blockDecompressorStream.read()");
		// e.printStackTrace();
		// }

		/*
		 * // 创建CompressionOutputStream compression输出流 FSDataOutputStream
		 * outStream = fs.create(outputPath); CompressionOutputStream coutStream
		 * = codec.createOutputStream(outStream); // 将输入流写入coutStream byte[]
		 * b=new byte[1024*3]; int length; while((length=inStream.read(b))!=-1){
		 * coutStream.write(b, 0, length); } coutStream.flush();
		 * coutStream.close();
		 */
		System.out.println("end ........................................");
	}

}
