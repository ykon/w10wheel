package hooktest.win32ex;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

public class RAWINPUT extends Structure {
    public RAWINPUTHEADER header;
    public RAWMOUSE mouse;

    public RAWINPUT() {
        super();
    }

    public RAWINPUT(Pointer ptr) {
        super(ptr);
        read();
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList(new String[] { "header", "mouse"});
    }
}
