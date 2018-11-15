package com.oracle.svm.core.posix;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.ProtocolFamily;
import java.net.SocketException;
import java.net.SocketOption;
import java.net.StandardSocketOptions;

import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import org.graalvm.nativeimage.c.type.VoidPointer;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.posix.headers.Errno;
import com.oracle.svm.core.posix.headers.LibC;
import com.oracle.svm.core.posix.headers.NetinetIn;
import com.oracle.svm.core.posix.headers.Poll;
import com.oracle.svm.core.posix.headers.Socket;
import com.oracle.svm.core.posix.headers.Socket.sockaddr;

import net.jzajic.graalvm.headers.FdUtils.Util_java_io_FileDescriptor;
import net.jzajic.graalvm.headers.Un;
import net.jzajic.graalvm.socket.UnixProtocolFamily;
import net.jzajic.graalvm.socket.UnixSocketAddress;
import net.jzajic.graalvm.socket.channel.IOStatus;

public class UnixNet {

	public final static int SHUT_RD = 0;
	public final static int SHUT_WR = 1;
	public final static int SHUT_RDWR = 2;

	//unspecified protocol family
	public static final ProtocolFamily UNSPEC = new ProtocolFamily() {
		public String name() {
			return "UNSPEC";
		}
	};

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
			return UnixNet.handleSocketError(Errno.errno());
		}
		return fd;
	}

	public static int connect(FileDescriptor fdo, UnixSocketAddress iao) throws IOException {
		int rv;
		sockaddr sa_Pointer = StackValue.get(SizeOf.get(Un.sockaddr_un.class));
		CIntPointer sa_len_Pointer = StackValue.get(CIntPointer.class);
		sa_len_Pointer.write(SizeOf.get(Un.sockaddr_un.class));
		if (NET_InetAddressToSockaddr(iao, sa_Pointer, sa_len_Pointer) != 0) {
			return IOStatus.THROWN;
		}
		rv = Socket.connect(PosixJavaNIOSubstitutions.fdval(fdo), sa_Pointer, sa_len_Pointer.read());
		if (rv != 0) {
			if (Errno.errno() == Errno.EINPROGRESS()) {
				return IOStatus.UNAVAILABLE;
			} else if (Errno.errno() == Errno.EINTR()) {
				return IOStatus.INTERRUPTED;
			}
			return UnixNet.handleSocketError(Errno.errno());
		}
		return 1;
	}

	public static int fdval(FileDescriptor fdo) {
		return PosixUtils.getFD(fdo);
	}

	public static void bind(FileDescriptor fd, UnixSocketAddress usa) throws IOException {
		Socket.sockaddr sa = StackValue.get(JavaNetNetUtilMD.SOCKADDR_LEN());
		CIntPointer sa_len_Pointer = StackValue.get(CIntPointer.class);
		sa_len_Pointer.write(JavaNetNetUtilMD.SOCKADDR_LEN());
		int rv = 0;
		if (NET_InetAddressToSockaddr(usa, sa, sa_len_Pointer) != 0) {
			return;
		}
		rv = JavaNetNetUtilMD.NET_Bind(PosixUtils.getFD(fd), sa, sa_len_Pointer.read());
		if (rv != 0) {
			UnixNet.handleSocketError(Errno.errno());
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

	public static Object getSocketOption(FileDescriptor fd, ProtocolFamily family, SocketOption<?> name)
			throws IOException {
		Class<?> type = name.type();

		// only simple values supported by this method
		if (type != Integer.class && type != Boolean.class)
			throw new AssertionError("Should not reach here");

		// map option name to platform level/name
		OptionKey key = SocketOptionRegistry.findOption(name, family);
		if (key == null)
			throw new AssertionError("Option not found");

		boolean mayNeedConversion = (family == UNSPEC);
		int value = getIntOption0(fd, mayNeedConversion, key.level(), key.name());

		if (type == Integer.class) {
			return Integer.valueOf(value);
		} else {
			return (value == 0) ? Boolean.FALSE : Boolean.TRUE;
		}
	}

	private static int getIntOption0(FileDescriptor fdo, boolean mayNeedConversion, int level, int opt) throws IOException {
		CIntPointer result_Pointer = StackValue.get(CIntPointer.class);
		Socket.linger linger = StackValue.get(Socket.linger.class);
		CCharPointer carg_Pointer = StackValue.get(CCharPointer.class);
		VoidPointer arg;
		CIntPointer arglen_Pointer = StackValue.get(CIntPointer.class);
		int n;
		arg = (VoidPointer) result_Pointer;
		arglen_Pointer.write(SizeOf.get(CIntPointer.class));
		if (level == NetinetIn.IPPROTO_IP() &&
				(opt == NetinetIn.IP_MULTICAST_TTL() || opt == NetinetIn.IP_MULTICAST_LOOP())) {
			arg = (VoidPointer) carg_Pointer;
			arglen_Pointer.write(SizeOf.get(CCharPointer.class));
		}
		if (level == Socket.SOL_SOCKET() && opt == Socket.SO_LINGER()) {
			arg = (VoidPointer) linger;
			arglen_Pointer.write(SizeOf.get(Socket.linger.class));
		}
		if (mayNeedConversion) {
			n = JavaNetNetUtilMD.NET_GetSockOpt(fdval(fdo), level, opt, arg, arglen_Pointer);
		} else {
			n = Socket.getsockopt(fdval(fdo), level, opt, arg, arglen_Pointer);
		}
		if (n < 0) {
			throw new SocketException(PosixUtils.lastErrorString("sun.nio.ch.Net.getIntOption"));
		}
		if (level == NetinetIn.IPPROTO_IP() &&
				(opt == NetinetIn.IP_MULTICAST_TTL() || opt == NetinetIn.IP_MULTICAST_LOOP())) {
			return carg_Pointer.read();
		}
		if (level == Socket.SOL_SOCKET() && opt == Socket.SO_LINGER()) {
			return CTypeConversion.toBoolean(linger.l_onoff()) ? linger.l_linger() : -1;
		}
		return result_Pointer.read();
	}

	private static void setIntOption0(FileDescriptor fdo,
			boolean mayNeedConversion,
			int level,
			int opt,
			int arg) throws IOException {
		/* Make a local copy of arg so I can get the address of it. */
		CIntPointer local_arg = StackValue.get(CIntPointer.class);
		local_arg.write(arg);
		Socket.linger linger = StackValue.get(Socket.linger.class);
		CCharPointer carg_Pointer = StackValue.get(CCharPointer.class);
		WordPointer parg;
		long arglen;
		int n;
		parg = (WordPointer) local_arg;
		arglen = SizeOf.get(CIntPointer.class);
		if (level == NetinetIn.IPPROTO_IP() &&
				(opt == NetinetIn.IP_MULTICAST_TTL() || opt == NetinetIn.IP_MULTICAST_LOOP())) {
			parg = (WordPointer) carg_Pointer;
			arglen = SizeOf.get(CCharPointer.class);
			carg_Pointer.write((byte) arg);
		}
		if (level == Socket.SOL_SOCKET() && opt == Socket.SO_LINGER()) {
			parg = (WordPointer) linger;
			arglen = SizeOf.get(Socket.linger.class);
			if (arg >= 0) {
				linger.set_l_onoff(1);
				linger.set_l_linger(arg);
			} else {
				linger.set_l_onoff(0);
				linger.set_l_linger(0);
			}
		}
		if (mayNeedConversion) {
			n = JavaNetNetUtilMD.NET_SetSockOpt(fdval(fdo), level, opt, parg, (int) arglen);
		} else {
			n = Socket.setsockopt(fdval(fdo), level, opt, parg, (int) arglen);
		}
		if (n < 0) {
			throw new SocketException(PosixUtils.lastErrorString("sun.nio.ch.Net.setIntOption"));
		}
	}

	public static void setSocketOption(FileDescriptor fd, ProtocolFamily family, SocketOption<?> name, Object value) throws IOException {
		if (value == null)
			throw new IllegalArgumentException("Invalid option value");

		// only simple values supported by this method
		Class<?> type = name.type();
		if (type != Integer.class && type != Boolean.class)
			throw new AssertionError("Should not reach here");

		// special handling
		if (name == StandardSocketOptions.SO_RCVBUF ||
				name == StandardSocketOptions.SO_SNDBUF) {
			int i = ((Integer) value).intValue();
			if (i < 0)
				throw new IllegalArgumentException("Invalid send/receive buffer size");
		}
		if (name == StandardSocketOptions.SO_LINGER) {
			int i = ((Integer) value).intValue();
			if (i < 0)
				value = Integer.valueOf(-1);
			if (i > 65535)
				value = Integer.valueOf(65535);
		}
		if (name == StandardSocketOptions.IP_TOS) {
			int i = ((Integer) value).intValue();
			if (i < 0 || i > 255)
				throw new IllegalArgumentException("Invalid IP_TOS value");
		}
		if (name == StandardSocketOptions.IP_MULTICAST_TTL) {
			int i = ((Integer) value).intValue();
			if (i < 0 || i > 255)
				throw new IllegalArgumentException("Invalid TTL/hop value");
		}

		// map option name to platform level/name
		OptionKey key = SocketOptionRegistry.findOption(name, family);
		if (key == null)
			throw new AssertionError("Option not found");

		int arg;
		if (type == Integer.class) {
			arg = ((Integer) value).intValue();
		} else {
			boolean b = ((Boolean) value).booleanValue();
			arg = (b) ? 1 : 0;
		}

		boolean mayNeedConversion = (family == UNSPEC);
		setIntOption0(fd, mayNeedConversion, key.level(), key.name(), arg);
	}

	public static void shutdown(FileDescriptor fdo, int jhow) throws IOException {
		int how = (jhow == sun.nio.ch.Net.SHUT_RD) ? sun.nio.ch.Net.SHUT_RD : (jhow == sun.nio.ch.Net.SHUT_WR) ? sun.nio.ch.Net.SHUT_WR : sun.nio.ch.Net.SHUT_RDWR;
		if ((Socket.shutdown(fdval(fdo), how) < 0) && Errno.errno() != Errno.ENOTCONN()) {
			UnixNet.handleSocketError(Errno.errno());
		}
	}

	public static int poll(FileDescriptor fdo, int events, long timeout) throws IOException {
		Poll.pollfd pfd = StackValue.get(Poll.pollfd.class);
		int rv;
		pfd.set_fd(PosixJavaNIOSubstitutions.fdval(fdo));
		pfd.set_events((short) events);
		rv = Poll.poll(pfd, 1, (int) timeout);
		if (rv >= 0) {
			return pfd.events();
		} else if (Errno.errno() == Errno.EINTR()) {
			return IOStatus.INTERRUPTED;
		} else {
			UnixNet.handleSocketError(Errno.errno());
			return IOStatus.THROWN;
		}
	}

	static int handleSocketError(int errorValue) throws IOException {
		IOException xn;
		final String exceptionString = "NioSocketError";
		if (errorValue == Errno.EINPROGRESS()) {
			return 0;
		} else if (errorValue == Errno.EPROTO()) {
			xn = new java.net.ProtocolException(PosixUtils.errorString(errorValue, exceptionString));
		} else if (errorValue == Errno.ECONNREFUSED()) {
			xn = new java.net.ConnectException(PosixUtils.errorString(errorValue, exceptionString));
		} else if (errorValue == Errno.ETIMEDOUT()) {
			xn = new java.net.ConnectException(PosixUtils.errorString(errorValue, exceptionString));
		} else if (errorValue == Errno.EHOSTUNREACH()) {
			xn = new java.net.NoRouteToHostException(PosixUtils.errorString(errorValue, exceptionString));
		} else if ((errorValue == Errno.EADDRINUSE()) || (errorValue == Errno.EADDRNOTAVAIL())) {
			xn = new java.net.BindException(PosixUtils.errorString(errorValue, exceptionString));
		} else {
			xn = new java.net.SocketException(PosixUtils.errorString(errorValue, exceptionString));
		}
		Errno.set_errno(errorValue);
		throw xn;
	}

}
