# W10Wheel
W10Wheel is the Mouse Wheel Simulator for Windows.  
Written in Scala and running on Java. [W10Wheel.NET](https://github.com/ykon/w10wheel.net) is its .NET (F#) port.  
It has also been ported to Rust: [W10Wheel.rs](https://github.com/ykon/w10wheel.rs).
## Supported
Windows 10 / 11 (x64).  
Bundled with a Java 25 runtime (separate Java installation is not required).
### Scroll inactive windows
Enable the Windows option to scroll inactive windows when hovering over them.
- Windows 11: Settings > Bluetooth & devices > Mouse > "Scroll inactive windows when hovering over them"
- Windows 10: Settings > Devices > Mouse > "Scroll inactive windows when I hover over them"
## Download
1. Download `W10Wheel-<version>-x64.zip` from [Releases](https://github.com/ykon/w10wheel/releases). `W10Wheel-<version>-x64.zip.sha256` has its SHA-256 hash.
2. Extract it to any folder and run `W10Wheel.exe`. Keep `W10Wheel.exe` together with the `app` and `runtime` folders next to it: it cannot be moved alone.

`W10Wheel.exe` is not code-signed, so Windows SmartScreen may show "Windows protected your PC" when you run it for the first time.
If you downloaded it from the Releases page above, click "More info" and then "Run anyway".
## Upgrading from 2.7
- Exit the running W10Wheel first (tray menu > Exit).
- Extract the new zip to a new folder instead of overwriting the old version (a single `W10Wheel.exe` that needed Java to be installed).
- Your settings files (`.W10Wheel*.properties` in your user folder) are kept and used as they are.
- If you start W10Wheel from a shortcut (e.g. in the Startup folder), change its target to the new `W10Wheel.exe`.
## Usage
Right-click the tray icon and choose Settings... to open the settings dialog: a change is applied and saved at once.  
Double-click the tray icon (or choose Pause) to pause W10Wheel. The dialog and the menu follow the light or dark mode of Windows apps.  
Mouse software that installs its hook after W10Wheel (e.g. X-Mouse Button Control, which remaps buttons) stops the mouse triggers: W10Wheel installs its hooks again after a resume from sleep and an unlock. If the triggers stop otherwise (e.g. that software turned off and on), pause W10Wheel and resume it: this installs the hooks again. The keyboard hook is removed while paused.  
Not running as administrator, W10Wheel does not work in the windows of programs that run as administrator: it asks at startup to restart as administrator (also in the settings dialog, General).  
Properties file is stored in the user directory.  
Scrolling can be disabled in the windows of chosen programs (.exe) in the settings dialog (Excluded Programs), as in W10Wheel.NET: the window under the cursor is checked when a trigger is pressed, and the buttons and keys work as usual there. The list is kept per properties in `.W10Wheel.exclusions` (Default) or `.W10Wheel.<name>.exclusions`, one full path per line. UWP / Store apps are not supported.

The default trigger is RightDragTrigger: hold the right button and move to scroll, release it without moving for a right click. Change it in the settings dialog (Trigger).

* LRTrigger: Press Left to Right, or vice versa. (simultaneously)
* LeftTrigger: Press Left to Right. (simultaneously)
* RightTrigger: Press Right to Left. (simultaneously)
* MiddleTrigger: Press Middle.
* X1Trigger: Press X1.
* X2Trigger: Press X2.
* LeftDragTrigger: Press Left and Drag. (lock-free)
* RightDragTrigger: Press Right and Drag. (lock-free)
* MiddleDragTrigger: Press Middle and Drag. (lock-free)
* X1DragTrigger: Press X1 and Drag. (lock-free)
* X2DragTrigger: Press X2 and Drag. (lock-free)

While scrolling with a trigger held, pressing another trigger (a mouse trigger or the keyboard trigger) ends scroll mode.  
Keys sent by other software (e.g. the auto-type of a password manager, or macros) are not taken for the keyboard trigger.

Commands for the running W10Wheel: `W10Wheel.exe --sendExit`, `--sendPassMode [true | false]`, `--sendReloadProp` and `--sendInitState`.  
See `Readme.ja.txt` in the zip for the details (in Japanese).
## Build
Requires JDK 25 and sbt 2.1.
```
sbt run
```
The tests (`src/test`) call the hook procedures with the calls to Windows replaced: no input is sent, no cursor changed. CI runs them before the build.
```
sbt test
```
To build the distribution zip (`target/jpackage/W10Wheel-<version>-x64.zip` and its `.sha256`), with `JAVA_HOME` set to a JDK 25:
```
pwsh dist/build-portable-zip.ps1
```
## Release
1. Update the version in `build.sbt` and `dist/Readme.ja.txt` (the version and the history line: the build checks that they match), and write the release notes in `dist/release-notes/v<version>.md`.
2. Commit, then push a tag that matches the version: `git tag v2.10.2` and `git push origin v2.10.2`.
3. GitHub Actions builds the zip and publishes the release with it.
   To review it as a draft first, run the "Release Portable Package" workflow manually (Actions tab) with the tag and draft enabled before pushing the tag: the tag push then rebuilds the files of the draft. Publish the draft on GitHub.

## License
The MIT License
