package net.jzajic.graalvm.socket.channel;

import java.io.IOException;
import java.net.ProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;

public class UnixSocketSelectorProvider extends SelectorProvider {

	@Override
	public DatagramChannel openDatagramChannel() throws IOException {
		return SelectorProvider.provider().openDatagramChannel();
	}

	@Override
	public DatagramChannel openDatagramChannel(ProtocolFamily family) throws IOException {
		return SelectorProvider.provider().openDatagramChannel(family);
	}

	@Override
	public Pipe openPipe() throws IOException {
		return SelectorProvider.provider().openPipe();
	}

	@Override
	public AbstractSelector openSelector() throws IOException {
		return SelectorProvider.provider().openSelector();
	}

	@Override
	public ServerSocketChannel openServerSocketChannel() throws IOException {
		return SelectorProvider.provider().openServerSocketChannel();
	}

	@Override
	public SocketChannel openSocketChannel() throws IOException {
		return new UnixSocketChannelImpl(this);
	}

	public static SelectorProvider provider() {
		return new UnixSocketSelectorProvider();
	}
	
}
