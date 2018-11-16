package net.jzajic.graalvm.socket.channel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import net.jzajic.graalvm.socket.Credentials;
import net.jzajic.graalvm.socket.UnixSocketAddress;
import net.jzajic.graalvm.socket.UnixSocketOptions;

public class UnixSocket extends java.net.Socket {

	private UnixSocketChannelImpl chan;

  private AtomicBoolean closed = new AtomicBoolean(false);
  private AtomicBoolean indown = new AtomicBoolean(false);
  private AtomicBoolean outdown = new AtomicBoolean(false);

  private InputStream in;
  private OutputStream out;

  public UnixSocket(UnixSocketChannelImpl chan) {
      this.chan = chan;
      in = Channels.newInputStream(chan);
      out = Channels.newOutputStream(chan);
  }

  @Override
  public void bind(SocketAddress local) throws IOException {
      if (null != chan) {
          if (isClosed()) {
              throw new SocketException("Socket is closed");
          }
          if (isBound()) {
              throw new SocketException("already bound");
          }
          try {
              chan.bind(local);
          } catch (IOException e) {
              throw (SocketException)new SocketException().initCause(e);
          }
      }
  }

  @Override
  public void close() throws IOException {
      if (null != chan && closed.compareAndSet(false, true)) {
          try {
              chan.close();
          } catch (IOException e) {
              ignore();
          }
      }
  }

  @Override
  public void connect(SocketAddress addr) throws IOException {
      connect(addr, 0);
  }

  public void connect(SocketAddress addr, Integer timeout) throws IOException {
      if (addr instanceof UnixSocketAddress) {
          chan.connect((UnixSocketAddress) addr);
      } else {
          throw new IllegalArgumentException("address of type "
                  + addr.getClass() + " are not supported. Use "
                  + UnixSocketAddress.class + " instead");
      }
  }

  @Override
  public SocketChannel getChannel() {
      return chan;
  }

  @Override
  public InetAddress getInetAddress() {
      return null;
  }

  public InputStream getInputStream() throws IOException {
      if (chan.isConnected()) {
          return in;
      } else {
          throw new IOException("not connected");
      }
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
      UnixSocketAddress address = chan.getLocalSocketAddress();
      if (address != null) {
          return address;
      } else {
          return null;
      }
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
      if (chan.isConnected()) {
          return out;
      } else {
          throw new IOException("not connected");
      }
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
      SocketAddress address = chan.getRemoteSocketAddress();

      if (address != null) {
          return address;
      } else {
          return null;
      }
  }

  @Override
  public boolean isBound() {
      if (null == chan) {
          return false;
      }
      return chan.isBound();
  }

  @Override
  public boolean isClosed() {
      return closed.get();
  }

  @Override
  public boolean isConnected() {
      return chan.isConnected();
  }

  @Override
  public boolean isInputShutdown() {
      return indown.get();
  }

  @Override
  public boolean isOutputShutdown() {
      return outdown.get();
  }

  @Override
  public void shutdownInput() throws IOException {
      if (indown.compareAndSet(false, true)) {
          chan.shutdownInput();
      }
  }

  @Override
  public void shutdownOutput() throws IOException {
      if (outdown.compareAndSet(false, true)) {
          chan.shutdownOutput();
      }
  }

  /**
   * Retrieves the credentials for this UNIX socket. Clients calling this
   * method will receive the server's credentials, and servers will receive
   * the client's credentials. User ID, group ID, and PID are supplied.
   *
   * See man unix 7; SCM_CREDENTIALS
   *
   * @throws UnsupportedOperationException if the underlying socket library
   *         doesn't support the SO_PEERCRED option
   * @throws SocketException if fetching the socket option failed.
   *
   * @return the credentials of the remote; null if not connected
   */
  public final Credentials getCredentials() throws SocketException {
      if (!chan.isConnected()) {
          return null;
      }
      try {
          return chan.getOption(UnixSocketOptions.SO_PEERCRED);
      } catch (IOException e) {
          throw (SocketException)new SocketException().initCause(e);
      }
  }

  @Override
  public boolean getKeepAlive() throws SocketException {
      try {
          return chan.getOption(StandardSocketOptions.SO_KEEPALIVE).booleanValue();
      } catch (IOException e) {
          throw (SocketException)new SocketException().initCause(e);
      }
  }

  @Override
  public int getReceiveBufferSize() throws SocketException {
      try {
          return chan.getOption(StandardSocketOptions.SO_RCVBUF).intValue();
      } catch (IOException e) {
          throw (SocketException)new SocketException().initCause(e);
      }
  }

  @Override
  public int getSendBufferSize() throws SocketException {
      try {
          return chan.getOption(StandardSocketOptions.SO_SNDBUF).intValue();
      } catch (IOException e) {
          throw (SocketException)new SocketException().initCause(e);
      }
  }

  @Override
  public int getSoTimeout() throws SocketException {
      try {
          return chan.getOption(UnixSocketOptions.SO_RCVTIMEO).intValue();
      } catch (IOException e) {
          throw (SocketException)new SocketException().initCause(e);
      }
  }

  @Override
  public void setKeepAlive(boolean on) throws SocketException {
      try {
          chan.setOption(StandardSocketOptions.SO_KEEPALIVE, Boolean.valueOf(on));
      } catch (IOException e) {
          throw (SocketException)new SocketException().initCause(e);
      }
  }

  @Override
  public void setReceiveBufferSize(int size) throws SocketException {
      try {
          chan.setOption(StandardSocketOptions.SO_RCVBUF, Integer.valueOf(size));
      } catch (IOException e) {
          throw (SocketException)new SocketException().initCause(e);
      }
  }

  @Override
  public void setSendBufferSize(int size) throws SocketException {
      try {
          chan.setOption(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(size));
      } catch (IOException e) {
          throw (SocketException)new SocketException().initCause(e);
      }
  }

  @Override
  public void setSoTimeout(int timeout) throws SocketException {
  		/* BUG: temporarily disabled
      try {
          chan.setOption(UnixSocketOptions.SO_RCVTIMEO, Integer.valueOf(timeout));
      } catch (IOException e) {
          throw (SocketException)new SocketException().initCause(e);
      }
      */
  }
  
  private void ignore() {
  }
	
}
