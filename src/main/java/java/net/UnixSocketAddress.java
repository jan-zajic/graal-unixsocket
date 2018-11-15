package java.net;

public class UnixSocketAddress extends SocketAddress {

	private static final long serialVersionUID = -1166004607034249624L;
	
	private final String path;
	
	public UnixSocketAddress() {
		this.path = "";
	}
	
	public UnixSocketAddress(String path) {
		super();
		this.path = path;
	}

	public UnixAddress getAddress() {
		return new UnixAddress(path);
	}

	public String getPath() {
		return path;
	}
	
}
