package com.oracle.svm.core.posix;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.SocketException;

import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.posix.PosixJavaNIOSubstitutions.Target_sun_nio_ch_IOStatus;
import com.oracle.svm.core.posix.PosixJavaNIOSubstitutions.Util_sun_nio_ch_Net;
import com.oracle.svm.core.posix.headers.Errno;
import com.oracle.svm.core.posix.headers.LibC;
import com.oracle.svm.core.posix.headers.Socket;
import com.oracle.svm.core.posix.headers.Socket.sockaddr;

import net.jzajic.graalvm.headers.FdUtils.Util_java_io_FileDescriptor;
import net.jzajic.graalvm.headers.Un;
import net.jzajic.graalvm.socket.UnixProtocolFamily;
import net.jzajic.graalvm.socket.UnixSocketAddress;

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

	public static int connect(FileDescriptor fdo, UnixSocketAddress iao) throws IOException {
		int rv;
		sockaddr sa_Pointer = StackValue.get(SizeOf.get(Un.sockaddr_un.class));
		CIntPointer sa_len_Pointer = StackValue.get(CIntPointer.class);
		sa_len_Pointer.write(SizeOf.get(Un.sockaddr_un.class));
		if (NET_InetAddressToSockaddr(iao, sa_Pointer, sa_len_Pointer) != 0) {
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

	public static void bind(FileDescriptor fd, UnixSocketAddress usa) throws IOException {
		Socket.sockaddr sa = StackValue.get(JavaNetNetUtilMD.SOCKADDR_LEN());
		// 270     int sa_len = SOCKADDR_LEN;
		CIntPointer sa_len_Pointer = StackValue.get(CIntPointer.class);
		sa_len_Pointer.write(JavaNetNetUtilMD.SOCKADDR_LEN());
		// 271     int rv = 0;
		int rv = 0;
		// 272
		// 273     if (NET_InetAddressToSockaddr(env, iao, port, (struct sockaddr *)&sa, &sa_len, preferIPv6) != 0) {
		if (NET_InetAddressToSockaddr(usa, sa, sa_len_Pointer) != 0) {
			// 274       return;
			return;
		}
		// 276
		// 277     rv = NET_Bind(fdval(env, fdo), (struct sockaddr *)&sa, sa_len);
		rv = JavaNetNetUtilMD.NET_Bind(PosixUtils.getFD(fd), sa, sa_len_Pointer.read());
		// 278     if (rv != 0) {
		if (rv != 0) {
			// 279         handleSocketError(env, errno);
			Util_sun_nio_ch_Net.handleSocketError(Errno.errno());
		}
	}

	static int NET_InetAddressToSockaddr(UnixSocketAddress iaObj, Socket.sockaddr him, CIntPointer len) throws SocketException {
		Un.sockaddr_un himU = (Un.sockaddr_un) him;
		himU.set_sun_family(Socket.AF_UNIX());
		try (CCharPointerHolder filePath = CTypeConversion.toCString(iaObj.getPath())) {
			LibC.strncpy(himU.sun_path(), filePath.get(), WordFactory.unsigned(Un.SUN_PATH_SIZE - 1));
			len.write(SizeOf.get(Un.sockaddr_un.class));
		}
		return 0;
	}
	
}
