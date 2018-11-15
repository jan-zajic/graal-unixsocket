package net.jzajic.graalvm.headers;

import java.util.Arrays;
import java.util.List;

import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.struct.AllowNarrowingCast;
import org.graalvm.nativeimage.c.struct.AllowWideningCast;
import org.graalvm.nativeimage.c.struct.CField;
import org.graalvm.nativeimage.c.struct.CFieldAddress;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.PointerBase;

import net.jzajic.graalvm.headers.Un.UnHeaders;

@CContext(UnHeaders.class)
public class Un {

	static class UnHeaders implements CContext.Directives {
    @Override
    public List<String> getHeaderFiles() {
        return Arrays.asList("<sys/un.h>");
    }
	}
	
	@CStruct(value = "sockaddr_un", addStructKeyword = true)
  public interface sockaddr_un extends PointerBase {

      // sa_family_t	sun_family
      @CField
      @AllowWideningCast
      int sun_family(); /* addressing family */

      @CField
      @AllowNarrowingCast
      void set_sun_family(int value);
      
      @CFieldAddress
      CCharPointer sun_path(); /* file name */

	}
	
	public static int SUN_PATH_SIZE = 108;
	
}
