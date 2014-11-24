package com.renren;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.compress.BlockCompressorStream;
import org.apache.hadoop.io.compress.BlockDecompressorStream;
import org.apache.hadoop.io.compress.Compressor;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.io.compress.snappy.SnappyDecompressor;
import org.junit.Test;

import static org.junit.Assert.*;

public class TestBlockDecompressorStream {

	private byte[] buf;
	private ByteArrayInputStream bytesIn;
	private ByteArrayOutputStream bytesOut;

	@Test
	public void testRead() throws IOException {
		// compress empty stream
		bytesOut = new ByteArrayOutputStream();
		BlockCompressorStream blockCompressorStream = new BlockCompressorStream(
				bytesOut, new FakeCompressor(), 1024, 0);
		// close without any write
		blockCompressorStream.close();

		// check compressed output
		buf = bytesOut.toByteArray();
		assertEquals("empty file compressed output size is not 4", 4,
				buf.length);
		System.out.println("buf.length="+buf.length);

		// use compressed output as input for decompression
		bytesIn = new ByteArrayInputStream(buf);

		// get decompression stream
		BlockDecompressorStream blockDecompressorStream = new BlockDecompressorStream(
				bytesIn, new SnappyDecompressor(), 1024);
		try {
			assertEquals("return value is not -1", -1,
					blockDecompressorStream.read());
		} catch (IOException e) {
			System.out.println("Exception occurred when execute blockDecompressorStream.read()");
			e.printStackTrace();
		}
	}
}

/**
 * A fake compressor Its input and output is the same.
 */
class FakeCompressor implements Compressor {

	private boolean finish;
	private boolean finished;
	int nread;
	int nwrite;

	byte[] userBuf;
	int userBufOff;
	int userBufLen;

	@Override
	public int compress(byte[] b, int off, int len) throws IOException {
		int n = Math.min(len, userBufLen);
		if (userBuf != null && b != null)
			System.arraycopy(userBuf, userBufOff, b, off, n);
		userBufOff = n;
		userBufLen -= n;
		nwrite = n;

		if (finish && userBufLen <= 0)
			finished = true;

		return n;
	}

	@Override
	public void end() {
		// nop
	}

	@Override
	public void finish() {
		finish = true;
	}

	@Override
	public boolean finished() {
		return finished;
	}

	@Override
	public long getBytesRead() {
		return nread;
	}

	@Override
	public long getBytesWritten() {
		return nwrite;
	}

	@Override
	public boolean needsInput() {
		return userBufLen <= 0;
	}

	@Override
	public void reset() {
		finish = false;
		finished = false;
		nread = 0;
		nwrite = 0;
		userBuf = null;
		userBufOff = 0;
		userBufLen = 0;
	}

	@Override
	public void setDictionary(byte[] b, int off, int len) {
		// nop
	}

	@Override
	public void setInput(byte[] b, int off, int len) {
		nread = len;
		userBuf = b;
		userBufOff = off;
		userBufLen = len;
	}

	@Override
	public void reinit(Configuration conf) {
		// nop
	}

}

/**
 * A fake decompressor, just like FakeCompressor Its input and output is the
 * same.
 */
class FakeDecompressor implements Decompressor {

	private boolean finish;
	private boolean finished;
	int nread;
	int nwrite;

	byte[] userBuf;
	int userBufOff;
	int userBufLen;

	@Override
	public int decompress(byte[] b, int off, int len) throws IOException {
		int n = Math.min(len, userBufLen);
		if (userBuf != null && b != null)
			System.arraycopy(userBuf, userBufOff, b, off, n);
		userBufOff = n;
		userBufLen -= n;
		nwrite = n;

		if (finish && userBufLen <= 0)
			finished = true;

		return n;
	}

	@Override
	public void end() {
		// nop
	}

	@Override
	public boolean finished() {
		return finished;
	}

	@Override
	public boolean needsDictionary() {
		return false;
	}

	@Override
	public boolean needsInput() {
		return userBufLen <= 0;
	}

	@Override
	public void reset() {
		finish = false;
		finished = false;
		nread = 0;
		nwrite = 0;
		userBuf = null;
		userBufOff = 0;
		userBufLen = 0;
	}

	@Override
	public void setDictionary(byte[] b, int off, int len) {
		// nop
	}

	@Override
	public void setInput(byte[] b, int off, int len) {
		nread = len;
		userBuf = b;
		userBufOff = off;
		userBufLen = len;
	}

	@Override
	public int getRemaining() {
		// TODO Auto-generated method stub
		return 0;
	}

}
