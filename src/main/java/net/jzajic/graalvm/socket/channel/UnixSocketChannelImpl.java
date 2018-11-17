
package net.jzajic.graalvm.socket.channel;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.NoConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.posix.headers.Errno;
import com.oracle.svm.core.posix.headers.LibC;

import net.jzajic.graalvm.posix.Native;
import net.jzajic.graalvm.posix.UnixNet;
import net.jzajic.graalvm.socket.UnixProtocolFamily;
import net.jzajic.graalvm.socket.UnixSocketAddress;
import net.jzajic.graalvm.socket.UnixSocketOptions;

/**
 * An implementation of SocketChannels
 */
class UnixSocketChannelImpl
		extends SocketChannel {
	// Our file descriptor object
	private final FileDescriptor fd;

	// fd value needed for dev/poll. This value will remain valid
	// even after the value in the file descriptor object has been set to -1
	private final int fdVal;

	// Lock held by any thread that modifies the state fields declared below
	// DO NOT invoke a blocking I/O operation while holding this lock!
	private final Object stateLock = new Object();

	// -- The following fields are protected by stateLock

	// State, increases monotonically
	private static final int ST_UNINITIALIZED = -1;
	private static final int ST_UNCONNECTED = 0;
	private static final int ST_PENDING = 1;
	private static final int ST_CONNECTED = 2;
	private static final int ST_KILLPENDING = 3;
	private static final int ST_KILLED = 4;
	private int state = ST_UNINITIALIZED;

	// Binding
	private UnixSocketAddress remoteAddress = null;
	private UnixSocketAddress localAddress = null;

	// Input/Output open
	private boolean isInputOpen = true;
	private boolean isOutputOpen = true;

	// Socket adaptor, created on demand
	private Socket socket;

	// -- End of fields protected by stateLock

	// Constructor for normal connecting sockets
	//
	UnixSocketChannelImpl(SelectorProvider sp) throws IOException {
		super(sp);
		this.fd = UnixNet.socket(UnixProtocolFamily.UNIX, true);
		this.fdVal = UnixNet.fdval(fd);
		this.state = ST_UNCONNECTED;
	}

	@Override
	public SocketAddress getRemoteAddress() throws IOException {
		return remoteAddress;
	}

	@Override
	public SocketAddress getLocalAddress() throws IOException {
		return localAddress;
	}

	@Override
	public <T> SocketChannel setOption(SocketOption<T> name, T value)
			throws IOException {
		if (name == null)
			throw new NullPointerException();
		if (!supportedOptions().contains(name))
			throw new UnsupportedOperationException("'" + name + "' not supported");

		synchronized (stateLock) {
			if (!isOpen())
				throw new ClosedChannelException();
			UnixNet.setSocketOption(fd, UnixNet.UNSPEC, name, value);
			return this;
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getOption(SocketOption<T> name)
			throws IOException {
		if (name == null)
			throw new NullPointerException();
		if (!supportedOptions().contains(name))
			throw new UnsupportedOperationException("'" + name + "' not supported");

		synchronized (stateLock) {
			if (!isOpen())
				throw new ClosedChannelException();
			return (T) UnixNet.getSocketOption(fd, UnixNet.UNSPEC, name);
		}
	}

	private static class DefaultOptionsHolder {
		static final Set<SocketOption<?>> defaultOptions = defaultOptions();

		private static Set<SocketOption<?>> defaultOptions() {
			HashSet<SocketOption<?>> set = new HashSet<SocketOption<?>>(8);
			set.add(UnixSocketOptions.SO_PEERCRED);
			set.add(UnixSocketOptions.SO_RCVTIMEO);
			set.add(StandardSocketOptions.SO_SNDBUF);
			set.add(StandardSocketOptions.SO_RCVBUF);
			set.add(StandardSocketOptions.SO_KEEPALIVE);
			return Collections.unmodifiableSet(set);
		}
	}

	@Override
	public final Set<SocketOption<?>> supportedOptions() {
		return DefaultOptionsHolder.defaultOptions;
	}

	private void readerCleanup() throws IOException {
		synchronized (stateLock) {
			if (state == ST_KILLPENDING)
				kill();
		}
	}

	@Override
	public int read(ByteBuffer dst) throws IOException {
		CCharPointer buf = UnmanagedMemory.malloc(WordFactory.unsigned(dst.remaining()));
		ByteBuffer buffer = CTypeConversion.asByteBuffer(buf, dst.remaining());
		try {
			int n = Native.read(this.fdVal, buf, buffer);
			buffer.flip();
			dst.put(buffer);

			switch (n) {
			case 0:
				return -1;

			case -1:
				int lastError = Errno.errno();
				if (lastError == Errno.EAGAIN() || lastError == Errno.EWOULDBLOCK()) {
					return 0;
				} else {
					throw new IOException(Native.getLastErrorString());
				}
			default: {
				return n;
			}
			}
		} finally {
			UnmanagedMemory.free(buf);
		}
	}

	public long read(ByteBuffer[] dsts, int offset, int length)
			throws IOException {
		long total = 0;

		for (int i = 0; i < length; i++) {
			ByteBuffer dst = dsts[offset + i];
			long read = read(dst);
			if (read == -1) {
				return read;
			}
			total += read;
		}

		return total;
	}

	public int write(ByteBuffer src) throws IOException {
		int r = src.remaining();
		int n;

		CCharPointer buf = UnmanagedMemory.malloc(WordFactory.unsigned(r));
		ByteBuffer buffer = CTypeConversion.asByteBuffer(buf, r);
		try {
			buffer.put(src);
			buffer.position(0);

			n = Native.write(fdVal, buf, buffer);

			if (n >= 0) {
				if (n < r) {
					src.position(src.position() - (r - n));
				}
			} else {
				int lastError = Errno.errno();
				if (lastError == Errno.EAGAIN() || lastError == Errno.EWOULDBLOCK()) {
					src.position(src.position() - r);
				} else {
					throw new IOException(Native.getLastErrorString());
				}
			}
		} finally {
			UnmanagedMemory.free(buf);
		}

		return n;
	}

	public long write(ByteBuffer[] srcs, int offset, int length)
			throws IOException {
		long result = 0;
		int index = 0;

		for (index = offset; index < length; index++) {
			result += write(srcs[index]);
		}

		return result;
	}

	protected void implConfigureBlocking(boolean block) throws IOException {
		Native.setBlocking(fdVal, block);
	}

	public final UnixSocketAddress getRemoteSocketAddress() {
		if (!isConnected()) {
			return null;
		}

		if (remoteAddress != null) {
			return remoteAddress;
		} else {
			remoteAddress = UnixNet.getpeername(fdVal);
			return remoteAddress;
		}
	}

	public final UnixSocketAddress getLocalSocketAddress() {
		if (localAddress != null) {
			return localAddress;
		} else {
			localAddress = UnixNet.getsockname(fdVal);
			return localAddress;
		}
	}

	@Override
	public SocketChannel bind(SocketAddress local) throws IOException {
		synchronized (stateLock) {
			if (!isOpen())
				throw new ClosedChannelException();
			if (state == ST_PENDING)
				throw new ConnectionPendingException();
			if (localAddress != null)
				throw new AlreadyBoundException();			
			this.localAddress = bind((UnixSocketAddress) local);
		}
		return this;
	}

	UnixSocketAddress bind(UnixSocketAddress local) throws IOException {
    UnixSocketAddress usa = (local == null) ? new UnixSocketAddress() : (UnixSocketAddress) local;
		UnixNet.bind(fd, usa);
    return UnixNet.getsockname(fdVal);
	}
	
	@Override
	public boolean isConnected() {
		synchronized (stateLock) {
			return (state == ST_CONNECTED);
		}
	}

	@Override
	public boolean isConnectionPending() {
		synchronized (stateLock) {
			return (state == ST_PENDING);
		}
	}

	void ensureOpenAndUnconnected() throws IOException { // package-private
		synchronized (stateLock) {
			if (!isOpen())
				throw new ClosedChannelException();
			if (state == ST_CONNECTED)
				throw new AlreadyConnectedException();
			if (state == ST_PENDING)
				throw new ConnectionPendingException();
		}
	}

	@Override
	public boolean connect(SocketAddress sa) throws IOException {
		UnixSocketAddress usa = (UnixSocketAddress) sa;
		ensureOpenAndUnconnected();
		synchronized (blockingLock()) {
			int n = 0;
			try {
				try {
					begin();
					synchronized (stateLock) {
						if (!isOpen()) {
							return false;
						}
					}
					for (;;) {
						n = UnixNet.connect(
								fd,
									usa);
						if ((n == IOStatus.INTERRUPTED)
								&& isOpen())
							continue;
						break;
					}

				} finally {
					readerCleanup();
					end((n > 0) || (n == IOStatus.UNAVAILABLE));
					assert IOStatus.check(n);
				}
			} catch (IOException x) {
				// If an exception was thrown, close the channel after
				// invoking end() so as to avoid bogus
				// AsynchronousCloseExceptions
				close();
				throw x;
			}
			synchronized (stateLock) {
				if (n > 0) {

					// Connection succeeded; disallow further
					// invocation
					state = ST_CONNECTED;
					if (isOpen())
						this.remoteAddress = usa;
					return true;
				}
				// If nonblocking and no exception then connection
				// pending; disallow another invocation
				if (!isBlocking()) {
					this.remoteAddress = usa;
					state = ST_PENDING;
				} else
					assert false;
			}
		}
		return false;
	}

	public boolean finishConnect() throws IOException {
		synchronized (stateLock) {
			if (!isOpen())
				throw new ClosedChannelException();
			if (state == ST_CONNECTED)
				return true;
			if (state != ST_PENDING)
				throw new NoConnectionPendingException();
		}
		int n = 0;
		try {
			try {
				begin();
				synchronized (blockingLock()) {
					synchronized (stateLock) {
						if (!isOpen()) {
							return false;
						}
					}
					if (!isBlocking()) {
						for (;;) {
							n = UnixNet.connect(
									fd,
										this.remoteAddress);
							if ((n == IOStatus.INTERRUPTED)
									&& isOpen())
								continue;
							break;
						}
					} else {
						for (;;) {
							n = UnixNet.connect(
									fd,
										this.remoteAddress);
							if (n == 0) {
								// Loop in case of
								// spurious notifications
								continue;
							}
							if ((n == IOStatus.INTERRUPTED)
									&& isOpen())
								continue;
							break;
						}
					}
				}
			} finally {
				synchronized (stateLock) {
					if (state == ST_KILLPENDING) {
						kill();
						// poll()/getsockopt() does not report
						// error (throws exception, with n = 0)
						// on Linux platform after dup2 and
						// signal-wakeup. Force n to 0 so the
						// end() can throw appropriate exception
						n = 0;
					}
				}
				end((n > 0) || (n == IOStatus.UNAVAILABLE));
				assert IOStatus.check(n);
			}
		} catch (IOException x) {
			// If an exception was thrown, close the channel after
			// invoking end() so as to avoid bogus
			// AsynchronousCloseExceptions
			close();
			throw x;
		}
		if (n > 0) {
			synchronized (stateLock) {
				state = ST_CONNECTED;
			}
			return true;
		}
		return false;
	}

	@Override
	public SocketChannel shutdownInput() throws IOException {
		synchronized (stateLock) {
			if (!isOpen())
				throw new ClosedChannelException();
			if (!isConnected())
				throw new NotYetConnectedException();
			if (isInputOpen) {
				UnixNet.shutdown(fd, UnixNet.SHUT_RD);
				isInputOpen = false;
			}
			return this;
		}
	}

	@Override
	public SocketChannel shutdownOutput() throws IOException {
		synchronized (stateLock) {
			if (!isOpen())
				throw new ClosedChannelException();
			if (!isConnected())
				throw new NotYetConnectedException();
			if (isOutputOpen) {
				UnixNet.shutdown(fd, UnixNet.SHUT_WR);
				isOutputOpen = false;
			}
			return this;
		}
	}

	public boolean isInputOpen() {
		synchronized (stateLock) {
			return isInputOpen;
		}
	}

	public boolean isOutputOpen() {
		synchronized (stateLock) {
			return isOutputOpen;
		}
	}

	// AbstractInterruptibleChannel synchronizes invocations of this method
	// using AbstractInterruptibleChannel.closeLock, and also ensures that this
	// method is only ever invoked once.  Before we get to this method, isOpen
	// (which is volatile) will have been set to false.
	//
	protected void implCloseSelectableChannel() throws IOException {
		Native.close(fdVal);
	}

	public void kill() throws IOException {
		synchronized (stateLock) {
			if (state == ST_KILLED)
				return;
			if (state == ST_UNINITIALIZED) {
				state = ST_KILLED;
				return;
			}
			assert !isOpen() && !isRegistered();
			state = ST_KILLED;
		}
	}

	// package-private
	int poll(int events, long timeout) throws IOException {
		assert Thread.holdsLock(blockingLock()) && !isBlocking();

		int n = 0;
		try {
			begin();
			synchronized (stateLock) {
				if (!isOpen())
					return 0;
			}
			n = UnixNet.poll(fd, events, timeout);
		} finally {
			readerCleanup();
			end(n > 0);
		}
		return n;
	}

	public FileDescriptor getFD() {
		return fd;
	}

	public int getFDVal() {
		return fdVal;
	}

	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append(this.getClass().getSuperclass().getName());
		sb.append('[');
		if (!isOpen())
			sb.append("closed");
		else {
			synchronized (stateLock) {
				switch (state) {
				case ST_UNCONNECTED:
					sb.append("unconnected");
					break;
				case ST_PENDING:
					sb.append("connection-pending");
					break;
				case ST_CONNECTED:
					sb.append("connected");
					if (!isInputOpen)
						sb.append(" ishut");
					if (!isOutputOpen)
						sb.append(" oshut");
					break;
				}
				if (localAddress != null) {
					sb.append(" localAddress=");
					sb.append(localAddress.toString());
				}
				if (remoteAddress != null) {
					sb.append(" remoteAddress=");
					sb.append(remoteAddress.toString());
				}
			}
		}
		sb.append(']');
		return sb.toString();
	}

	@Override
	public Socket socket() {
		return new UnixSocket(this);
	}

	public boolean isBound() {
		return localAddress != null;
	}

}