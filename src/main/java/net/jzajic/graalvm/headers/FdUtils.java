package net.jzajic.graalvm.headers;

import java.io.FileDescriptor;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.Uninterruptible;

public class FdUtils {

	@TargetClass(java.io.FileDescriptor.class)
  public static final class Target_java_io_FileDescriptor {

      @Alias int fd;
  }

  public static final class Util_java_io_FileDescriptor {

      @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
      public static int getFD(FileDescriptor descriptor) {
          return SubstrateUtil.cast(descriptor, Target_java_io_FileDescriptor.class).fd;
      }

      public static void setFD(FileDescriptor descriptor, int fd) {
      	SubstrateUtil.cast(descriptor, Target_java_io_FileDescriptor.class).fd = fd;
      }
  }
	
}
