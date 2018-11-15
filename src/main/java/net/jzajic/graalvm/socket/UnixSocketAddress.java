package net.jzajic.graalvm.socket;

import java.net.SocketAddress;

public class UnixSocketAddress extends SocketAddress {

	private static final long serialVersionUID = -1166004607034249624L;
	
	private String path;
	
	public UnixSocketAddress() {
		this.path = "";
	}
	
	public UnixSocketAddress(String path) {
		super();
		this.path = path;
	}

	public String getPath() {
		return path;
	}
	
	public void setPath(String path) {
		this.path = path;
	}
	
}
