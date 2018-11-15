package com.oracle.svm.core.posix;

import java.net.InetAddress;
import java.net.SocketException;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.posix.headers.LibC;
import com.oracle.svm.core.posix.headers.Socket;

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

}
