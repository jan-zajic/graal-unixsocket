package net.jzajic.graalvm.posix;

import java.io.IOException;

import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.posix.PosixUtils;
import com.oracle.svm.core.posix.headers.Errno;
import com.oracle.svm.core.posix.headers.Fcntl;
import com.oracle.svm.core.posix.headers.Unistd;

public class Native {

	public static int read(int fd, int remaining, CCharPointer pointer) throws IOException {
		int n;
		int readed = 0;
		do {
			n = (int) Unistd.read(fd, pointer, WordFactory.unsigned(remaining-readed)).rawValue();
			if(n >= 0) {
				readed += n;
			}
		} while (readed < remaining && n < 0 && Errno.EINTR() == getLastError());

		return readed;
	}

	public static int write(int fd, int size, CCharPointer pointer) throws IOException {
		int n;
		int written = 0;
		do {
			n = (int) Unistd.write(fd, pointer, WordFactory.unsigned(size-written)).rawValue();
			if(n >= 0) {
				written += n;
			}
		} while (written < size && n < 0 && Errno.EINTR() == getLastError());

		return written;
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
