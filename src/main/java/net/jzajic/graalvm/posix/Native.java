package net.jzajic.graalvm.posix;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.posix.PosixUtils;
import com.oracle.svm.core.posix.headers.Errno;
import com.oracle.svm.core.posix.headers.Fcntl;
import com.oracle.svm.core.posix.headers.Unistd;

public class Native {

	public static int read(int fd, CCharPointer pointer, ByteBuffer dst) throws IOException {
		if (dst == null) {
			throw new NullPointerException("Destination buffer cannot be null");
		}
		
		int n;
		do {
			n = (int) Unistd.read(fd, pointer, WordFactory.unsigned(dst.remaining())).rawValue();
		} while (n < 0 && Errno.EINTR() == getLastError());

		if (n > 0) {
			dst.position(dst.position() + n);
		}

		return n;
	}

	public static int write(int fd, CCharPointer pointer, ByteBuffer src) throws IOException {
		if (src == null) {
			throw new NullPointerException("Source buffer cannot be null");
		}

		int n;
		do {
			n = (int) Unistd.write(fd, pointer, WordFactory.unsigned(src.remaining())).rawValue();
		} while (n < 0 && Errno.EINTR() == getLastError());

		if (n > 0) {
			src.position(src.position() + n);
		}

		return n;
	}

	public static String getLastErrorString() {
		return PosixUtils.lastErrorString("Unknown error");
	}

	static int getLastError() {
		return Errno.errno();
	}

	public static void setBlocking(int fd, boolean block) {
		int flags = Fcntl.fcntl(fd, Fcntl.F_GETFL(), 0);
		if (block) {
			flags &= ~Fcntl.O_NONBLOCK();

		} else {
			flags |= Fcntl.O_NONBLOCK();
		}
		
		Fcntl.fcntl(fd, Fcntl.F_SETFL(), flags);
	}

	public static int close(int fdVal) {
		int rc;
		do {
			rc = Unistd.close(fdVal);
		} while (rc < 0 && Errno.EINTR() == getLastError());

		if (rc < 0) {
			String message = String.format("Error closing fd %d: %s", fdVal, getLastErrorString());
			throw new RuntimeException(message);
		} else {
			return rc;
		}
	}

}
