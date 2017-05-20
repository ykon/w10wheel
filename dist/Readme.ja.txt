名前:
        W10Wheel

バージョン:
        2.2.1

URL:
        Blog: https://ykon0x1.blogspot.jp
        Java版: https://github.com/ykon/w10wheel
        .NET版: https://github.com/ykon/w10wheel.net

概要:
        マウスホイールシミュレーター

履歴:
        2017-05-20: v2.2.1: エクスプローラーの再起動でアイコンが消える問題を修正
        2017-05-14: v2.2.0: Injectされたクリックを無視、同期改良、ライブラリの更新
        2016-10-15: v2.1.0: イベント受け渡しと同期を改良、SWTを更新
        2016-09-21: v2.0.11: イベントの順序を改良 (LR系トリガー)
        2016-09-14: v2.0.10: スクロール開始のフラグ更新を修正
        2016-09-06: v2.0.9: LR系トリガーのスクロール開始とスレッドの問題を修正
        2016-09-03: v2.0.8: LR系トリガーの動作変更、他
        2016-08-30: v2.0.7: トリガーが押されていたら、他のボタンではスクロールを終了しない(LR系以外)
        2016-08-29: v2.0.6: 再送方式を変更、EventWaiterスレッド優先度引き上げ
        2016-08-25: v2.0.5: EventWaiterのフラグ更新修正、Clickには別のタグを持たせる
        2016-08-24: v2.0.4: 再々送を廃止して、Clickとして再送信するように変更
        2016-08-23: v2.0.3: LastFlagsを参照と同時にリセットするように変更
        2016-08-23: v2.0.2: LR系トリガー再々送の無限ループ修正、他
        2016-08-22: v2.0.1: Propertiesファイルのユーザー定義名を制限 (先頭 "--")
        2016-08-21: v2.0.0: コマンドを追加 (--sendExit, --sendPassMode)
        2016-08-20: v1.9.0: 初期状態でのバグ修正とDPICorrection追加、他
        2016-08-15: v1.8.1: ホイール処理(スクロール)の最適化
        2016-08-12: v1.8.0: コマンドライン引数でPropertiesを指定可能、他
        2016-08-11: v1.7.0: Propertiesを選択できるように変更、他
        2016-08-08: v1.6.0: VHAdjusterを追加、他
        2016-08-05: v1.5.2: キーボードのフラグ変更
        2016-08-04: v1.5.1: EventHandlerの最適化
        2016-08-03: v1.5.0: トリガーにNoneを追加、他
        2016-07-31: v1.4.0: キーボード(トリガー)対応
        2016-07-29: v1.3.0: OSの終了時などに設定を保存、他
        2016-07-27: v1.2.0: イベントの順序、同期の改良
        2016-07-25: v1.1.0: SwapScrollの追加
        2016-07-25: v1.0.0: DraggedLockの追加、再送イベントの改良
        2016-07-23: v0.9.0: スレッド周辺とイベント受け渡しの改良
        2016-07-21: v0.8.3: 設定ファイル周辺の改良 / AccelTableのループ最適化
        2016-07-20: v0.8.2: Min JRE version(Launch4j)を1.8.0_101に引き上げ
        2016-07-20: v0.8.1: 修正: AccelTableが上りに適用されていない
        2016-07-19: v0.8.0: 追加: AccelTable / 廃止: verticalAccel, horizontalAccel
        2016-07-18: v0.7.5: 修正: 設定の再読み込みでメニューが二重に選択される
        2016-07-17: v0.7.4: 修正: メニューに数値のリセットが反映されていない
        2016-07-17: v0.7.3: 修正: 初回起動時にデフォルト優先度が設定されていない
        2016-07-17: v0.7.2: RealWheelModeのメニュー構造変更、他
        2016-07-16: v0.7.1: 32bit環境でのSWTの問題を修正
        2016-07-15: v0.7.0: AWT,SwingからSWTに変更 
        2016-07-14: v0.6.2: RealWheelModeのレスポンス改善
        2016-07-13: v0.6.1: hwCountの初期値を修正、デフォルト値の変更
        2016-07-12: v0.6.0: RealWheelModeを追加
        2016-07-12: v0.5.0: 追加: MiddleDrag, X1Drag, X2Drag 改名: LeftDrag, RightDrag
        2016-07-12: v0.4.2: 0.4.1の修正を一部修正
        2016-07-12: v0.4.1: 設定ファイルの読み取り(エラーハンドリング)を修正
        2016-07-11: v0.4.0: プロセスの優先度変更を追加、最適化
        2016-07-10: v0.3.1: 逆スクロールを追加
        2016-07-10: v0.3.0: PopupMenuをAWTからSwingに変更、他
        2016-07-08: v0.2.1: 細かい改良
        2016-07-06: v0.2.0: LeftOnlyTriggerとRightOnlyTriggerを追加
        2016-07-05: v0.1.0: 初公開
        
対応環境:
        Java 8 のシステム要件項目 Windows を参照
        https://www.java.com/ja/download/help/sysreq.xml
        
        最新の Java 8 をインストールしてください。
        http://java.com/ja/
        
        Windows 7, 8.1:
                WizMouse など、非アクティブウィンドウをスクロール可能にするソフトが必要です。
                http://forest.watch.impress.co.jp/docs/serial/okiniiri/587890.html
        
        Windows 10:
                オプションを有効にしてください。
                http://www.lifehacker.jp/2015/09/150909_window_scrolling.html
        
        Java の環境で作られていますが JNA (Java Native Access) で
        Windows API を使っているため、Mac や Linux では動作しません。
        
互換性:
        Logitech(ロジクール) の SetPoint は問題ありません。
        「ボタンの切り替え」などをしてもうまく動きます。
        フィルタドライバのレベルで動いているものは、恐らく安全です。
        
        グローバルフックのレベルで動いているものは競合します。
        ただし、WizMouse などは安全に使えます。
        
        WheelBall や Nadesath など同種のソフトは、同時に起動しないでください。
        動作が競合して操作不能になります。
        
回復操作:
        操作が効かなくなったら Ctrl-Alt-Delete でタスクマネージャーを開いてください。
        大抵の場合は、それだけで制御を取り戻せます。
        効かない場合は、何回か繰り返してください。
        それでも駄目な場合は、タスクマネージャーでアイコンを探し、
        プロセスを強制終了させてください。
        WheelBall などと同時に起動すると、何故か終了できなくなることがあります。
        この場合の終了方法は OS の再起動しかありません。
        # 他に何か方法があったら教えてください。
        
使用方法:
        W10Wheel.exe を実行してください。
        タスクトレイに exe ファイルと同様のアイコンが発生します。
        こちらから右クリックメニューで設定を変更できます。
        詳しい方は、設定ファイル (.W10Wheel.properties) を直接編集してください。
        一度起動して終了すれば、設定ファイルはユーザーディレクトリに生成されます。
        
        あとの使い方は WheelBall など同種のソフトと同様になります。
        各トリガーを押すと、スクロールモードに移行します。
        マウスまたはボールの操作でスクロールします。
        スクロールモードは何かのボタンを押すと解除されます。
        
        トリガーを押したままスクロールして、離したら止めることもできます。
        # 同時押しでは、両方を押さえたままにするのではなく、片方を先に離すと使いやすくなります。
        
        *Dragのトリガーではドラッグしている間だけスクロールします。
        スクロールモードに固定されません。(Dragged Lock が無効の場合)
        
        タスクトレイのアイコンをダブルクリックするとPassModeを切り替えます。
        PassModeを有効にするとアイコンが赤色に変更されます。
        
        終了するには、右クリックメニューから Exit を選択してください。
        
コマンド:
        "--"が先頭に付いた文字列を実行ファイルの引数に渡すとコマンドと解釈します。
        
        --send*のコマンドは実行したら、そのまま終了します。
        Java版と.NET版で相互に機能します。

        --sendPassMode [true | false]
        # --sendPassMode true // PassMode有効
        実行中のプロセスにPassModeを設定します。 
        第二引数を省略した場合はtrueと見なします。

        --sendExit
        実行中のプロセスを終了します。
        
設定ファイル:        
        Propertiesメニューで他のPropertiesを選択すると
        現在の設定は保存されて
        選択されたPropertiesから設定が読み込まれます。
        
        Defaultは".W10Wheel.properties"になります。
        # これは使えません => .W10Wheel.Default.properties

        Addで追加される、Propertiesファイルの名前の書式です。
        # 正規表現: \.W10Wheel\.(?!--)(.+)\.properties
        Left = .W10Wheel.Left.properties
        Right = .W10Wheel.Right.properties
        Test = .W10Wheel.Test.properties
        ファイル名の2回目の"."と3回目の"."の間が定義された(表示される)名前となります。
        コマンドを区別するため、先頭に"--"は使えません。
        # これは使えません => .W10Wheel.--Test.properties

        "--"が先頭に付かない文字列を実行ファイルの引数に渡すと、Propertiesを選択できます。
        引数を渡さない場合は、終了時の設定に関わらず、Defaultで起動します。
        
メニュー項目:
        Trigger: トリガーを変更 (設定項目を参照)
        Keyboard: キーボードトリガー
        Accel Table: 加速を有効にするか、どのテーブルを使うか # 1.0は1.0倍を表す
        Priority: プロセスの優先度を変更
        SetNumber: 数値をセット (設定項目を参照)
        Real Wheel Mode: 実際のホイールに近いスクロール (設定項目を参照)
        VH Adjuster: 垂直、水平の方向固定、切り替え機能
        DPI Correction: DPI補正 (1.00=100%, 1.25=125%, 1.50=150%, ...)
        Properties: 設定ファイル関連
        Cursor Change: スクロールモードのカーソル変更
        Horizontal Scroll: 水平スクロール
        Reverse Scroll (Flip): スクロールの方向を逆にする (反転する)
        Swap Scroll (V.H): 垂直スクロールと水平スクロールを入れ替える (Vertical <-> Horizontal)
        Pass Mode: 全てのメッセージをそのまま通す # WheelBall の制御停止
        Info: バージョン番号を表示
        Exit: 終了
        
設定項目:
        firstTrigger: string (default: LRTrigger)
                LRTrigger: # 同時押し
                        左から右か、右から左を押すとトリガーになります。
                        左、右クリックともに次のイベントを待つために遅延します。
                LeftTrigger: # 同時押し
                        左から右を押すとトリガーになります。
                        右からはトリガーになりません、そのため右クリックの遅延を解消できます。
                RightTrigger: # 同時押し
                        右から左を押すとトリガーになります。
                        左からはトリガーになりません、そのため左クリックの遅延を解消できます。
                MiddleTrigger:
                        ミドル(中)を押すとトリガーになります。
                X1Trigger:
                        X1を押すとトリガーになります。
                X2Trigger:
                        X2を押すとトリガーになります。
                LeftDragTrigger: # 固定なし
                        左ボタンでドラッグするとスクロールできます。
                        固定はされません、ドラッグしないで離すと左クリックを送ります。
                RightDragTrigger: # 固定なし
                        右ボタンでドラッグするとスクロールできます。
                        固定はされません、ドラッグしないで離すと右クリックを送ります。
                MiddleDragTrigger: # 固定なし
                        ミドル(中)ボタンでドラッグするとスクロールできます。
                        固定はされません、ドラッグしないで離すとミドル(中)クリックを送ります。
                X1DragTrigger: # 固定なし
                        X1ボタンでドラッグするとスクロールできます。
                        固定はされません、ドラッグしないで離すとX1クリックを送ります。
                X2DragTrigger: # 固定なし
                        X2ボタンでドラッグするとスクロールできます。
                        固定はされません、ドラッグしないで離すとX2クリックを送ります。
        
        processPriority: string (default: AboveNormal)
                High: 高
                AboveNormal: 通常以上
                Normal: 通常
                
        pollTimeout: 150-500 (default: 200)
                同時押しのイベント待ち時間(ミリ秒)  # WheelBall の 判定時間 
        scrollLocktime: 150-500 (default: 200)
                トリガーを離してスクロールモードに固定する時間(ミリ秒)
                この時間以内にトリガーを離すとスクロールモードに固定します。
        realWheelMode: bool (default: false)
                実際のホイールに近いスクロール
                こちらのモードではAccel値は使われません。
        cursorChange: bool (default: true)
                スクロールモードのカーソル変更
        verticalThreshold: 0-500 (default: 0)
                垂直(通常)スクロールの閾値
        horizontalScroll: bool (default: true)
                水平スクロール
                使わない人は無効にしてください。
        horizontalThreshold: 0-500 (default: 50)
                水辺スクロールの閾値
                この値をあまり小さくすると垂直(通常)スクロールが、使いづらくなります。
        reverseScroll: bool (default: false)
                スクロールの方向を逆にする (反転する)
        swapScroll: bool (default: false)
                垂直スクロールと水平スクロールを入れ替える (Vertical <-> Horizontal)
                
        sendMiddleClick: bool (default: false) # Middle, X1, X2
                Shift または Ctrl または Alt キーを押しながら
                トリガーを押すとミドル(中)クリックを送ります。
                
        draggedLock: bool (default: false) # *DragTrigger
                *DragTriggerでドラッグ後、スクロールモードに固定します。
                
        vhAdjusterMethod: string (default: Switching) # VHAdjuster
                Fixed: 初回の移動量が多い方向に固定
                Switching: 方向固定 + 切り替え (switchingThreshold参照)
                
        vhAdjusterMode: bool (default: false) # VHAdjuster
                VHAdjusterの有効、無効
        firstPreferVertical: bool (default: true) # VHAdjuster
                初回判定時に垂直(縦)を優先する
        firstMinThreshold: 1-10 (default: 5) # VHAdjuster
                初回を判定する移動量 (この値以下は無視する)
        switchingThreshold: 10-500 (default: 50) # VHAdjuster
                この値を超える入力で、方向を切り替える
                
        wheelDelta: 10-500 (default: 120) # RealWheelMode
                RealWheelModeでの一回分のホイール値
                通常のマウスのホイール値は120です。
        vWheelMove: 10-500 (default: 60) # RealWheelMode
                垂直(通常)スクロール、一回分のホイールに変換する移動量
        hWheelMove: 10-500 (default: 60) # RealWheelMode
                水平スクロール、一回分のホイールに変換する移動量
        quickFirst: bool (default: false) # RealWheelMode
                初回の反応を速くするか
                移動量に関係なくホイールを送ります。
                falseでも初回は半分の移動量に設定されます。
        quickTurn: bool (default: false)  # RealWheelMode
                折り返しの反応を速くするか
                移動量に関係なくホイールを送ります。
                
        accelTable: bool (default: false) # AccelTable
                AccelTableを有効にするか
        customAccelTable: bool (default: false) # AccelTable
                CustomTableを有効にするか
        accelMultiplier: string (default: M5) # AccelTable
                選択されている乗数テーブル
        customAccelThreshold: Int Array # AccelTable
                CustomTableで使われるThreshold
        customAccelMultiplier: Double Array # AccelTable
                CustomTableで使われるMultiplier
                
        keyboardHook: bool (default: false) # Keyboard
                キーボードトリガーを有効にする
        targetVKCode: string (default: VK_NONCONVERT) # Keyboard
                トリガーに使うキー
                
        dpiCorrection: double  (default: 1.00) # DPI Correction
                DPI補正に使う値 (1.00=100%, 1.25=125%, 1.50=150%, ...)
                
ライセンス:
        The MIT License
        詳しくは License.txt を参照
        
ライブラリ:
        Library.txt を参照
        
アイコン:
        こちらのジェネレーターで作りました。
        http://icon-generator.net/

.exe:
        exe wrapper はこちらを使っています。
        http://launch4j.sourceforge.net/

連絡:
        何かの問題や、気になる点、また改良案などがあったら
        Blog、GitHub、メールなどで報告頂けると幸いです。

製作者:
        Yuki Ono <ykon0x1@gmail.com>
        
著作権:
        Copyright (c) 2017 Yuki Ono
