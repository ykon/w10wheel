package hooktest.win32ex;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.*;

// https://msdn.microsoft.com/library/windows/desktop/ms645565.aspx
public class RAWINPUTDEVICE extends Structure {
    public short usUsagePage;
    public short usUsage;
    public int dwFlags;
    public HWND hwndTarget;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList(new String[] { "usUsagePage", "usUsage", "dwFlags",
                                            "hwndTarget" });
    }
}
