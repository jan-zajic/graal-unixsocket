package net.jzajic.graalvm.socket;

import java.net.SocketOption;

public class UnixSocketOptions {

	private static class GenericOption<T> implements SocketOption<T> {
    private final String name;
    private final Class<T> type;
    GenericOption(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }
    @Override public String name() { return name; }
    @Override public Class<T> type() { return type; }
    @Override public String toString() { return name; }
	}
	
	/**
   * Fetch peer credentials.
   */
  public static final SocketOption<Credentials> SO_PEERCRED =
  		new GenericOption<Credentials>("SO_PEERCRED", Credentials.class);
	
  /**
   * Get/Set receive timeout.
   */
  public static final SocketOption<Integer> SO_RCVTIMEO =
  			new GenericOption<Integer>("SO_RCVTIMEO", Integer.class);
  
}
