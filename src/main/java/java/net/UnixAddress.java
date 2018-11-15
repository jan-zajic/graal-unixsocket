package java.net;

public class UnixAddress extends InetAddress {

	private static final long serialVersionUID = -2774003843491943407L;
	static final int UNIX = 3;
	
	public UnixAddress() {
		super();
		holder().hostName = null;
    holder().address = 0;
    holder().family = UNIX;
	}
	
	public UnixAddress(String path) {
		holder().hostName = path;
    holder().family = UNIX;
	}
	
	public String getPath() {
		return holder().hostName;
	}
	
	@Override
	public String getHostAddress() {
		return holder().getHostName();
	}
	
	@Override
	public String getCanonicalHostName() {
		return holder().getHostName();
	}
		
}
