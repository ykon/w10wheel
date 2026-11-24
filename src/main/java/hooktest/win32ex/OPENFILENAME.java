package hooktest.win32ex;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.*;

// OPENFILENAMEW of GetOpenFileNameW (Comdlg32Ex): not in jna-platform. The strings are pointers to
// UTF-16 text (the filter and the files chosen have NULs in them).
// https://learn.microsoft.com/windows/win32/api/commdlg/ns-commdlg-openfilenamew
public class OPENFILENAME extends Structure {
    public int lStructSize;
    public HWND hwndOwner;
    public Pointer hInstance;
    public Pointer lpstrFilter;
    public Pointer lpstrCustomFilter;
    public int nMaxCustFilter;
    public int nFilterIndex;
    public Pointer lpstrFile;
    public int nMaxFile;
    public Pointer lpstrFileTitle;
    public int nMaxFileTitle;
    public Pointer lpstrInitialDir;
    public Pointer lpstrTitle;
    public int Flags;
    public short nFileOffset;
    public short nFileExtension;
    public Pointer lpstrDefExt;
    public Pointer lCustData;
    public Pointer lpfnHook;
    public Pointer lpTemplateName;
    public Pointer pvReserved;
    public int dwReserved;
    public int FlagsEx;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList(new String[] { "lStructSize", "hwndOwner", "hInstance", "lpstrFilter",
                                            "lpstrCustomFilter", "nMaxCustFilter", "nFilterIndex",
                                            "lpstrFile", "nMaxFile", "lpstrFileTitle", "nMaxFileTitle",
                                            "lpstrInitialDir", "lpstrTitle", "Flags", "nFileOffset",
                                            "nFileExtension", "lpstrDefExt", "lCustData", "lpfnHook",
                                            "lpTemplateName", "pvReserved", "dwReserved", "FlagsEx" });
    }
}
