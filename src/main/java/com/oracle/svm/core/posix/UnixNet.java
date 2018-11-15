package com.oracle.svm.core.posix;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnixProtocolFamily;

import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CIntPointer;

import com.oracle.svm.core.posix.PosixJavaNIOSubstitutions.Target_sun_nio_ch_IOStatus;
import com.oracle.svm.core.posix.PosixJavaNIOSubstitutions.Util_sun_nio_ch_Net;
import com.oracle.svm.core.posix.headers.Errno;
import com.oracle.svm.core.posix.headers.Socket;
import com.oracle.svm.core.posix.headers.Socket.sockaddr;

import net.jzajic.graalvm.headers.FdUtils.Util_java_io_FileDescriptor;
import net.jzajic.graalvm.headers.Un;

public class UnixNet {

	public static FileDescriptor socket(UnixProtocolFamily unix, boolean stream) throws IOException {
		int fd = socketU(stream, false);
		FileDescriptor javaFileDescriptor = new FileDescriptor();
		Util_java_io_FileDescriptor.setFD(javaFileDescriptor, fd);
		return javaFileDescriptor;
	}

	static int socketU(boolean stream, boolean reuse) throws IOException {
		int fd;
		int type = (stream ? Socket.SOCK_STREAM() : Socket.SOCK_DGRAM());
		fd = Socket.socket(Socket.AF_UNIX(), type, 0);
		if (fd < 0) {
			return Util_sun_nio_ch_Net.handleSocketError(Errno.errno());
		}
		return fd;
	}

	public static int connect(FileDescriptor fdo, InetAddress iao) throws IOException {
		int rv;
		sockaddr sa_Pointer = StackValue.get(SizeOf.get(Un.sockaddr_un.class));
		CIntPointer sa_len_Pointer = StackValue.get(CIntPointer.class);
		sa_len_Pointer.write(SizeOf.get(Un.sockaddr_un.class));
		if (JavaNetNetUtilMD.NET_InetAddressToSockaddr(iao, 0, sa_Pointer, sa_len_Pointer, false) != 0) {
			return Target_sun_nio_ch_IOStatus.IOS_THROWN;
		}
		rv = Socket.connect(PosixJavaNIOSubstitutions.fdval(fdo), sa_Pointer, sa_len_Pointer.read());
		if (rv != 0) {
			if (Errno.errno() == Errno.EINPROGRESS()) {
				return Target_sun_nio_ch_IOStatus.IOS_UNAVAILABLE;
			} else if (Errno.errno() == Errno.EINTR()) {
				return Target_sun_nio_ch_IOStatus.IOS_INTERRUPTED;
			}
			return Util_sun_nio_ch_Net.handleSocketError(Errno.errno());
		}
		return 1;
	}
	
	public static int fdval(FileDescriptor fdo) {
		return PosixUtils.getFD(fdo);
	}
	
}
