package hooktest.win32ex;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.WinDef.POINT;

// https://msdn.microsoft.com/library/windows/desktop/ms644970.aspx
public class MSLLHOOKSTRUCT extends Structure 
{
    public POINT pt;
    public int mouseData;
    public int flags;
    public int time;
    public ULONG_PTR dwExtraInfo;
    
    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList(new String[] { "pt", "mouseData", "flags",
                                            "time", "dwExtraInfo" });
    }
}
