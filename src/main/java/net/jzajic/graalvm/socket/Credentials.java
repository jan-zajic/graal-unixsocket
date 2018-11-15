package net.jzajic.graalvm.socket;

import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CIntPointer;

import com.oracle.svm.core.posix.PosixUtils;
import com.oracle.svm.core.posix.headers.Socket;

public class Credentials {

	private final Socket.ucred ucred;

	Credentials(Socket.ucred ucred) {
		this.ucred = ucred;
	}

	/**
	 * Retrieves the peer's process ID.
	 * 
	 * @return The PID.
	 */
	public int getPid() {
		return ucred.pid();
	}

	/**
	 * Retrieves the peer's numeric effective user ID.
	 * 
	 * @return The EUID.
	 */
	public int getUid() {
		return ucred.uid();
	}

	/**
	 * Retrieves the peer's numeric effective group ID.
	 * 
	 * @return The EGID.
	 */
	public int getGid() {
		return ucred.gid();
	}

	/**
	 * Returns a human readable description of this instance.
	 */
	@Override
	public java.lang.String toString() {
		return java.lang.String.format("[uid=%d gid=%d pid=%d]", getUid(), getGid(), getPid());
	}

	static Credentials getCredentials(int fd) {
		Socket.ucred result_Pointer = StackValue.get(Socket.ucred.class);
		CIntPointer sa_len_Pointer = StackValue.get(CIntPointer.class);
		sa_len_Pointer.write(SizeOf.get(Socket.ucred.class));
		int error = Socket.getsockopt(fd, Socket.SOL_SOCKET(), Socket.SO_PEERCRED(), result_Pointer, sa_len_Pointer);
		if (error != 0) {
			throw new UnsupportedOperationException(PosixUtils.lastErrorString("sun.nio.ch.Net.getIntOption"));
		}
		return new Credentials(result_Pointer);
	}

}
