
package sun.nio.ch;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.UnixProtocolFamily;
import java.net.UnixSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.NoConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.oracle.svm.core.posix.UnixNet;

/**
 * An implementation of SocketChannels
 */

class UnixSocketChannelImpl
    extends SocketChannel
{

    // Used to make native read and write calls
    private static NativeDispatcher nd;

    // Our file descriptor object
    private final FileDescriptor fd;

    // fd value needed for dev/poll. This value will remain valid
    // even after the value in the file descriptor object has been set to -1
    private final int fdVal;

    // IDs of native threads doing reads and writes, for signalling
    private volatile long readerThread = 0;
    private volatile long writerThread = 0;

    // Lock held by current reading or connecting thread
    private final Object readLock = new Object();

    // Lock held by current writing or connecting thread
    private final Object writeLock = new Object();

    // Lock held by any thread that modifies the state fields declared below
    // DO NOT invoke a blocking I/O operation while holding this lock!
    private final Object stateLock = new Object();

    // -- The following fields are protected by stateLock

    // set true when exclusive binding is on and SO_REUSEADDR is emulated
    private boolean isReuseAddress;

    // State, increases monotonically
    private static final int ST_UNINITIALIZED = -1;
    private static final int ST_UNCONNECTED = 0;
    private static final int ST_PENDING = 1;
    private static final int ST_CONNECTED = 2;
    private static final int ST_KILLPENDING = 3;
    private static final int ST_KILLED = 4;
    private int state = ST_UNINITIALIZED;

    // Binding
    private UnixSocketAddress socketAdress;

    // Input/Output open
    private boolean isInputOpen = true;
    private boolean isOutputOpen = true;
    private boolean readyToConnect = false;

    // Socket adaptor, created on demand
    private Socket socket;

    // -- End of fields protected by stateLock

    // Constructor for normal connecting sockets
    //
    UnixSocketChannelImpl(SelectorProvider sp) throws IOException {
        super(sp);
        this.fd = UnixNet.socket(UnixProtocolFamily.UNIX, true);
        this.fdVal = IOUtil.fdVal(fd);
        this.state = ST_UNCONNECTED;
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            return socketAdress;
        }
    }

    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            return socketAdress;
        }
    }

    @Override
    public <T> SocketChannel setOption(SocketOption<T> name, T value)
        throws IOException
    {
        if (name == null)
            throw new NullPointerException();
        if (!supportedOptions().contains(name))
            throw new UnsupportedOperationException("'" + name + "' not supported");

        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            Net.setSocketOption(fd, Net.UNSPEC, name, value);
            return this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOption(SocketOption<T> name)
        throws IOException
    {
        if (name == null)
            throw new NullPointerException();
        if (!supportedOptions().contains(name))
            throw new UnsupportedOperationException("'" + name + "' not supported");

        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            return (T) Net.getSocketOption(fd, Net.UNSPEC, name);
        }
    }

    private static class DefaultOptionsHolder {
        static final Set<SocketOption<?>> defaultOptions = defaultOptions();

        private static Set<SocketOption<?>> defaultOptions() {
            HashSet<SocketOption<?>> set = new HashSet<SocketOption<?>>(8);
            return Collections.unmodifiableSet(set);
        }
    }

    @Override
    public final Set<SocketOption<?>> supportedOptions() {
        return DefaultOptionsHolder.defaultOptions;
    }

    private boolean ensureReadOpen() throws ClosedChannelException {
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (!isConnected())
                throw new NotYetConnectedException();
            if (!isInputOpen)
                return false;
            else
                return true;
        }
    }

    private void ensureWriteOpen() throws ClosedChannelException {
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (!isOutputOpen)
                throw new ClosedChannelException();
            if (!isConnected())
                throw new NotYetConnectedException();
        }
    }

    private void readerCleanup() throws IOException {
        synchronized (stateLock) {
            readerThread = 0;
            if (state == ST_KILLPENDING)
                kill();
        }
    }

    private void writerCleanup() throws IOException {
        synchronized (stateLock) {
            writerThread = 0;
            if (state == ST_KILLPENDING)
                kill();
        }
    }

    public int read(ByteBuffer buf) throws IOException {

        if (buf == null)
            throw new NullPointerException();

        synchronized (readLock) {
            if (!ensureReadOpen())
                return -1;
            int n = 0;
            try {

                // Set up the interruption machinery; see
                // AbstractInterruptibleChannel for details
                //
                begin();

                synchronized (stateLock) {
                    if (!isOpen()) {
                    // Either the current thread is already interrupted, so
                    // begin() closed the channel, or another thread closed the
                    // channel since we checked it a few bytecodes ago.  In
                    // either case the value returned here is irrelevant since
                    // the invocation of end() in the finally block will throw
                    // an appropriate exception.
                    //
                        return 0;

                    }

                    // Save this thread so that it can be signalled on those
                    // platforms that require it
                    //
                    readerThread = NativeThread.current();
                }

                // Between the previous test of isOpen() and the return of the
                // IOUtil.read invocation below, this channel might be closed
                // or this thread might be interrupted.  We rely upon the
                // implicit synchronization point in the kernel read() call to
                // make sure that the right thing happens.  In either case the
                // implCloseSelectableChannel method is ultimately invoked in
                // some other thread, so there are three possibilities:
                //
                //   - implCloseSelectableChannel() invokes nd.preClose()
                //     before this thread invokes read(), in which case the
                //     read returns immediately with either EOF or an error,
                //     the latter of which will cause an IOException to be
                //     thrown.
                //
                //   - implCloseSelectableChannel() invokes nd.preClose() after
                //     this thread is blocked in read().  On some operating
                //     systems (e.g., Solaris and Windows) this causes the read
                //     to return immediately with either EOF or an error
                //     indication.
                //
                //   - implCloseSelectableChannel() invokes nd.preClose() after
                //     this thread is blocked in read() but the operating
                //     system (e.g., Linux) doesn't support preemptive close,
                //     so implCloseSelectableChannel() proceeds to signal this
                //     thread, thereby causing the read to return immediately
                //     with IOStatus.INTERRUPTED.
                //
                // In all three cases the invocation of end() in the finally
                // clause will notice that the channel has been closed and
                // throw an appropriate exception (AsynchronousCloseException
                // or ClosedByInterruptException) if necessary.
                //
                // *There is A fourth possibility. implCloseSelectableChannel()
                // invokes nd.preClose(), signals reader/writer thred and quickly
                // moves on to nd.close() in kill(), which does a real close.
                // Then a third thread accepts a new connection, opens file or
                // whatever that causes the released "fd" to be recycled. All
                // above happens just between our last isOpen() check and the
                // next kernel read reached, with the recycled "fd". The solution
                // is to postpone the real kill() if there is a reader or/and
                // writer thread(s) over there "waiting", leave the cleanup/kill
                // to the reader or writer thread. (the preClose() still happens
                // so the connection gets cut off as usual).
                //
                // For socket channels there is the additional wrinkle that
                // asynchronous shutdown works much like asynchronous close,
                // except that the channel is shutdown rather than completely
                // closed.  This is analogous to the first two cases above,
                // except that the shutdown operation plays the role of
                // nd.preClose().
                for (;;) {
                    n = IOUtil.read(fd, buf, -1, nd);
                    if ((n == IOStatus.INTERRUPTED) && isOpen()) {
                        // The system call was interrupted but the channel
                        // is still open, so retry
                        continue;
                    }
                    return IOStatus.normalize(n);
                }

            } finally {
                readerCleanup();        // Clear reader thread
                // The end method, which is defined in our superclass
                // AbstractInterruptibleChannel, resets the interruption
                // machinery.  If its argument is true then it returns
                // normally; otherwise it checks the interrupt and open state
                // of this channel and throws an appropriate exception if
                // necessary.
                //
                // So, if we actually managed to do any I/O in the above try
                // block then we pass true to the end method.  We also pass
                // true if the channel was in non-blocking mode when the I/O
                // operation was initiated but no data could be transferred;
                // this prevents spurious exceptions from being thrown in the
                // rare event that a channel is closed or a thread is
                // interrupted at the exact moment that a non-blocking I/O
                // request is made.
                //
                end(n > 0 || (n == IOStatus.UNAVAILABLE));

                // Extra case for socket channels: Asynchronous shutdown
                //
                synchronized (stateLock) {
                    if ((n <= 0) && (!isInputOpen))
                        return IOStatus.EOF;
                }

                assert IOStatus.check(n);

            }
        }
    }

    public long read(ByteBuffer[] dsts, int offset, int length)
        throws IOException
    {
        if ((offset < 0) || (length < 0) || (offset > dsts.length - length))
            throw new IndexOutOfBoundsException();
        synchronized (readLock) {
            if (!ensureReadOpen())
                return -1;
            long n = 0;
            try {
                begin();
                synchronized (stateLock) {
                    if (!isOpen())
                        return 0;
                    readerThread = NativeThread.current();
                }

                for (;;) {
                    n = IOUtil.read(fd, dsts, offset, length, nd);
                    if ((n == IOStatus.INTERRUPTED) && isOpen())
                        continue;
                    return IOStatus.normalize(n);
                }
            } finally {
                readerCleanup();
                end(n > 0 || (n == IOStatus.UNAVAILABLE));
                synchronized (stateLock) {
                    if ((n <= 0) && (!isInputOpen))
                        return IOStatus.EOF;
                }
                assert IOStatus.check(n);
            }
        }
    }

    public int write(ByteBuffer buf) throws IOException {
        if (buf == null)
            throw new NullPointerException();
        synchronized (writeLock) {
            ensureWriteOpen();
            int n = 0;
            try {
                begin();
                synchronized (stateLock) {
                    if (!isOpen())
                        return 0;
                    writerThread = NativeThread.current();
                }
                for (;;) {
                    n = IOUtil.write(fd, buf, -1, nd);
                    if ((n == IOStatus.INTERRUPTED) && isOpen())
                        continue;
                    return IOStatus.normalize(n);
                }
            } finally {
                writerCleanup();
                end(n > 0 || (n == IOStatus.UNAVAILABLE));
                synchronized (stateLock) {
                    if ((n <= 0) && (!isOutputOpen))
                        throw new AsynchronousCloseException();
                }
                assert IOStatus.check(n);
            }
        }
    }

    public long write(ByteBuffer[] srcs, int offset, int length)
        throws IOException
    {
        if ((offset < 0) || (length < 0) || (offset > srcs.length - length))
            throw new IndexOutOfBoundsException();
        synchronized (writeLock) {
            ensureWriteOpen();
            long n = 0;
            try {
                begin();
                synchronized (stateLock) {
                    if (!isOpen())
                        return 0;
                    writerThread = NativeThread.current();
                }
                for (;;) {
                    n = IOUtil.write(fd, srcs, offset, length, nd);
                    if ((n == IOStatus.INTERRUPTED) && isOpen())
                        continue;
                    return IOStatus.normalize(n);
                }
            } finally {
                writerCleanup();
                end((n > 0) || (n == IOStatus.UNAVAILABLE));
                synchronized (stateLock) {
                    if ((n <= 0) && (!isOutputOpen))
                        throw new AsynchronousCloseException();
                }
                assert IOStatus.check(n);
            }
        }
    }

    protected void implConfigureBlocking(boolean block) throws IOException {
        IOUtil.configureBlocking(fd, block);
    }

    public UnixSocketAddress localAddress() {
        synchronized (stateLock) {
            return socketAdress;
        }
    }

    public UnixSocketAddress remoteAddress() {
        synchronized (stateLock) {
            return socketAdress;
        }
    }

    @Override
    public SocketChannel bind(SocketAddress local) throws IOException {
        synchronized (readLock) {
            synchronized (writeLock) {
                synchronized (stateLock) {
                    if (!isOpen())
                        throw new ClosedChannelException();
                    if (state == ST_PENDING)
                        throw new ConnectionPendingException();
                    if (socketAdress != null)
                        throw new AlreadyBoundException();
                    UnixSocketAddress usa = (local == null) ?
                        new UnixSocketAddress() : (UnixSocketAddress) local;
                    Net.bind(fd, usa.getAddress(), 0);
                    this.socketAdress = (UnixSocketAddress) local;
                }
            }
        }
        return this;
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
        int localPort = 0;
        UnixSocketAddress usa = (UnixSocketAddress) sa;
        synchronized (readLock) {
            synchronized (writeLock) {
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
                                readerThread = NativeThread.current();
                            }
                            for (;;) {
                                InetAddress ia = usa.getAddress();
                                if (ia.isAnyLocalAddress())
                                    ia = InetAddress.getLocalHost();
                                n = UnixNet.connect(fd,
                                                ia);
                                if (  (n == IOStatus.INTERRUPTED)
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
                            	this.socketAdress = usa;
                            return true;
                        }
                        // If nonblocking and no exception then connection
                        // pending; disallow another invocation
                        if (!isBlocking()) {
                        		this.socketAdress = usa;
                            state = ST_PENDING;
                        }
                        else
                            assert false;
                    }
                }
                return false;
            }
        }
    }

    public boolean finishConnect() throws IOException {
        synchronized (readLock) {
            synchronized (writeLock) {
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
                                readerThread = NativeThread.current();
                            }
                            if (!isBlocking()) {
                                for (;;) {
		                                	n = UnixNet.connect(fd,
		                                				this.socketAdress.getAddress());
                                    if (  (n == IOStatus.INTERRUPTED)
                                          && isOpen())
                                        continue;
                                    break;
                                }
                            } else {
                                for (;;) {
                                    n = UnixNet.connect(fd,
                                				this.socketAdress.getAddress());
                                    if (n == 0) {
                                        // Loop in case of
                                        // spurious notifications
                                        continue;
                                    }
                                    if (  (n == IOStatus.INTERRUPTED)
                                          && isOpen())
                                        continue;
                                    break;
                                }
                            }
                        }
                    } finally {
                        synchronized (stateLock) {
                            readerThread = 0;
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
        }
    }

    @Override
    public SocketChannel shutdownInput() throws IOException {
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (!isConnected())
                throw new NotYetConnectedException();
            if (isInputOpen) {
                Net.shutdown(fd, Net.SHUT_RD);
                if (readerThread != 0)
                    NativeThread.signal(readerThread);
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
            	Net.shutdown(fd, Net.SHUT_WR);
                if (writerThread != 0)
                    NativeThread.signal(writerThread);
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
        synchronized (stateLock) {
            isInputOpen = false;
            isOutputOpen = false;

            // Close the underlying file descriptor and dup it to a known fd
            // that's already closed.  This prevents other operations on this
            // channel from using the old fd, which might be recycled in the
            // meantime and allocated to an entirely different channel.
            //
            if (state != ST_KILLED)
                nd.preClose(fd);

            // Signal native threads, if needed.  If a target thread is not
            // currently blocked in an I/O operation then no harm is done since
            // the signal handler doesn't actually do anything.
            //
            if (readerThread != 0)
                NativeThread.signal(readerThread);

            if (writerThread != 0)
                NativeThread.signal(writerThread);

            // If this channel is not registered then it's safe to close the fd
            // immediately since we know at this point that no thread is
            // blocked in an I/O operation upon the channel and, since the
            // channel is marked closed, no thread will start another such
            // operation.  If this channel is registered then we don't close
            // the fd since it might be in use by a selector.  In that case
            // closing this channel caused its keys to be cancelled, so the
            // last selector to deregister a key for this channel will invoke
            // kill() to close the fd.
            //
            if (!isRegistered())
                kill();
        }
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

            // Postpone the kill if there is a waiting reader
            // or writer thread. See the comments in read() for
            // more detailed explanation.
            if (readerThread == 0 && writerThread == 0) {
                nd.close(fd);
                state = ST_KILLED;
            } else {
                state = ST_KILLPENDING;
            }
        }
    }

    // package-private
    int poll(int events, long timeout) throws IOException {
        assert Thread.holdsLock(blockingLock()) && !isBlocking();

        synchronized (readLock) {
            int n = 0;
            try {
                begin();
                synchronized (stateLock) {
                    if (!isOpen())
                        return 0;
                    readerThread = NativeThread.current();
                }
                n = Net.poll(fd, events, timeout);
            } finally {
                readerCleanup();
                end(n > 0);
            }
            return n;
        }
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
                if (remoteAddress() != null) {
                    sb.append(" address=");
                    sb.append(remoteAddress().toString());
                }
            }
        }
        sb.append(']');
        return sb.toString();
    }


    // -- Native methods --
    static {
        IOUtil.load();
        nd = new SocketDispatcher();
    }

		@Override
		public Socket socket() {
			throw new UnsupportedOperationException();
		}

}