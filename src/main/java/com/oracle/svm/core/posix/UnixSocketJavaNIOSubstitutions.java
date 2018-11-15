package com.oracle.svm.core.posix;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ProtocolFamily;
import java.net.SocketException;
import java.net.UnixProtocolFamily;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.posix.PosixJavaNIOSubstitutions.Target_sun_nio_ch_IOStatus;
import com.oracle.svm.core.posix.PosixJavaNIOSubstitutions.Util_sun_nio_ch_Net;
import com.oracle.svm.core.posix.headers.Errno;
import com.oracle.svm.core.posix.headers.LibC;
import com.oracle.svm.core.posix.headers.Socket;
import com.oracle.svm.core.posix.headers.Socket.sockaddr;

import net.jzajic.graalvm.headers.FdUtils.Util_java_io_FileDescriptor;
import net.jzajic.graalvm.headers.Un;

/**
 * override PosixJavaNIOSubstitutions to handle unix sockets
 * 
 * @author jzajic
 *
 */
public class UnixSocketJavaNIOSubstitutions {

	@TargetClass(className = "com.oracle.svm.core.posix.JavaNetNetUtilMD")
	@Platforms({ Platform.LINUX.class, Platform.DARWIN.class })
	static final class Target_JavaNetNetUtilMD {

		@Substitute
		static int NET_InetAddressToSockaddr(InetAddress iaObj, int port, Socket.sockaddr him, CIntPointer len, boolean v4MappedAddress) throws SocketException {
			int family = JavaNetNetUtil.getInetAddress_family(iaObj);
			if (family == 3) {
				Un.sockaddr_un himU = (Un.sockaddr_un) him;
				himU.set_sun_family(Socket.AF_UNIX());
				try (CCharPointerHolder filePath = CTypeConversion.toCString(iaObj.getHostAddress())) {
					LibC.strncpy(himU.sun_path(), filePath.get(), WordFactory.unsigned(Un.SUN_PATH_SIZE - 1));
					len.write(SizeOf.get(Un.sockaddr_un.class));
				}
				return 0;
			} else {
				return NET_InetAddressToSockaddr(iaObj, port, him, len, v4MappedAddress);
			}
		}

	}

	@TargetClass(className = "sun.nio.ch.Net")
	@Platforms({ Platform.LINUX.class, Platform.DARWIN.class })
	static final class Target_sun_nio_ch_Net {

		@Substitute
		static int connect0(boolean preferIPv6, FileDescriptor fdo, InetAddress iao, int port) throws IOException {
			int family = JavaNetNetUtil.getInetAddress_family(iao);
			if (family == 3) {
				int rv;
				sockaddr sa_Pointer = StackValue.get(SizeOf.get(Un.sockaddr_un.class));
				CIntPointer sa_len_Pointer = StackValue.get(CIntPointer.class);
				sa_len_Pointer.write(SizeOf.get(Un.sockaddr_un.class));
				if (JavaNetNetUtilMD.NET_InetAddressToSockaddr(iao, port, sa_Pointer, sa_len_Pointer, preferIPv6) != 0) {
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
			} else {
				return connect0(preferIPv6, fdo, iao, port);
			}
		}

		@Substitute
		static FileDescriptor socket(ProtocolFamily family, boolean stream) throws IOException {
			if(family == UnixProtocolFamily.UNIX) {
				int fd = socketU(stream, false);
				FileDescriptor javaFileDescriptor = new FileDescriptor();
				Util_java_io_FileDescriptor.setFD(javaFileDescriptor, fd);
				return javaFileDescriptor;
			} else {
				return socket(family, stream);
			}
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
		
		/* Do not re-format commented-out code: @formatter:off */
		/* Allow methods with non-standard names: Checkstyle: stop */

		@SuppressWarnings("finally")
		@Substitute
		static int socket0(boolean preferIPv6, boolean stream, boolean reuse, @SuppressWarnings("unused") boolean fastLoopback) throws IOException {
			return socket0(preferIPv6, stream, reuse, fastLoopback);
		}

	}

	protected static int fdval(FileDescriptor fdo) {
		return PosixUtils.getFD(fdo);
	}

}
