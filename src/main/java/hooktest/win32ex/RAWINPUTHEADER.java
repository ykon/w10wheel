package hooktest.win32ex;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinDef.WPARAM;

public class RAWINPUTHEADER extends Structure {
    public int dwType;
    public int dwSize;
    public HANDLE hDevice;
    public WPARAM wParam;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList(new String[] { "dwType", "dwSize", "hDevice", "wParam"});
    }
}
