package hooktest.win32ex;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Structure;

public class RAWMOUSE extends Structure {
    public short usFlags;
    public short _alignment; // the native button union is 4-byte aligned
    public short usButtonFlags;
    public short usButtonData;
    public int ulRawButtons;
    public int lLastX;
    public int lLastY;
    public int ulExtraInformation;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList(new String[] { "usFlags", "_alignment", "usButtonFlags", "usButtonData",
                            "ulRawButtons", "lLastX", "lLastY", "ulExtraInformation"});
    }
}
