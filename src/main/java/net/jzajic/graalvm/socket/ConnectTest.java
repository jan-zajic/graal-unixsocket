package net.jzajic.graalvm.socket;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.channels.SocketChannel;

import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.posix.PosixUtils;
import com.oracle.svm.core.posix.headers.LibC;
import com.oracle.svm.core.posix.headers.Socket;
import com.oracle.svm.core.posix.headers.Socket.sockaddr;

import net.jzajic.graalvm.headers.FdUtils.Util_java_io_FileDescriptor;
import net.jzajic.graalvm.socket.channel.UnixSocketSelectorProvider;
import net.jzajic.graalvm.headers.Un;

public class ConnectTest {
	
	public static void main(String[] args) {
		String path = args[0];		
		//testSocketRawApi(path);
		testSocketJavaApi(path);
	}
	
	private static void testSocketJavaApi(String path) {
		try {
			SocketChannel openSocketChannel = UnixSocketSelectorProvider.provider().openSocketChannel();
			boolean conn = openSocketChannel.connect(new UnixSocketAddress(path));
			System.out.println("Channel connected: "+conn);
			openSocketChannel.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private static void testSocketRawApi(String path) {
		int fd;
		try (CCharPointerHolder filePath = CTypeConversion.toCString(path)) {
			if( (fd = Socket.socket(Socket.AF_UNIX(), Socket.SOCK_STREAM(), 0)) == -1 ) {
				System.out.println("Socket error: "+PosixUtils.lastErrorString("Unknown error"));
				System.exit(-1);
			}
			
			CIntPointer len_Pointer = StackValue.get(CIntPointer.class);
			len_Pointer.write(SizeOf.get(Un.sockaddr_un.class));		
			Un.sockaddr_un sockAddr = (Un.sockaddr_un) LibC.malloc(WordFactory.unsigned(SizeOf.get(Un.sockaddr_un.class)));
			sockAddr.set_sun_family(Socket.AF_UNIX());
			LibC.strncpy(sockAddr.sun_path(), filePath.get(), WordFactory.unsigned(Un.SUN_PATH_SIZE - 1));
			if(Socket.connect(fd, (sockaddr) sockAddr, len_Pointer.read()) == -1) {
				System.out.println("Connect error: "+PosixUtils.lastErrorString("Unknown error"));
				System.exit(-1);
			}
			
			System.out.println("UNIX SOCKET CONNECTED!");
			FileDescriptor javaFileDescriptor = new FileDescriptor();
			Util_java_io_FileDescriptor.setFD(javaFileDescriptor, fd);
			
			try {
				SocketChannel socketChannel = SocketChannel.open();
				
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
		}
	}
	
}
