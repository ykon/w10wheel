名前:
        W10Wheel

バージョン:
        0.7.5

URL:
        https://github.com/ykon/w10wheel

概要:
        マウスホイールシミュレーター

履歴:
        2016-07-18: Version 0.7.5: 修正: 設定の再読み込みでメニューが二重に選択される
        2016-07-17: Version 0.7.4: 修正: メニューに数値のリセットが反映されていない
        2016-07-17: Version 0.7.3: 修正: 初回起動時にデフォルト優先度が設定されていない
        2016-07-17: Version 0.7.2: RealWheelModeのメニュー構造変更、他
        2016-07-16: Version 0.7.1: 32bit環境でのSWTの問題を修正
        2016-07-15: Version 0.7.0: AWT,SwingからSWTに変更 
        2016-07-14: Version 0.6.2: RealWheelModeのレスポンス改善
        2016-07-13: Version 0.6.1: hwCountの初期値を修正、デフォルト値の変更
        2016-07-12: Version 0.6.0: RealWheelModeを追加
        2016-07-12: Version 0.5.0: 追加: MiddleDrag, X1Drag, X2Drag 改名: LeftDrag, RightDrag
        2016-07-12: Version 0.4.2: 0.4.1の修正を一部修正
        2016-07-12: Version 0.4.1: 設定ファイルの読み取り(エラーハンドリング)を修正
        2016-07-11: Version 0.4.0: プロセスの優先度変更を追加、最適化
        2016-07-10: Version 0.3.1: 逆スクロールを追加
        2016-07-10: Version 0.3.0: PopupMenuをAWTからSwingに変更、他
        2016-07-08: Version 0.2.1: 細かい改良
        2016-07-06: Version 0.2.0: LeftOnlyTriggerとRightOnlyTriggerを追加
        2016-07-05: Version 0.1.0: 初公開
        
対応環境:
        Java 8 のシステム要件項目 Windows を参照
        https://www.java.com/ja/download/help/sysreq.xml
        
        Java の環境で動いてはいますが JNA (Java Native Access) で
        Windows API を使っているため、Mac や Linux では動作しません。
        
        PostMessage ではなく SendInput でホイールメッセージを送っているため、
        Windows 10 以外の環境では WizMouse など
        http://forest.watch.impress.co.jp/docs/serial/okiniiri/587890.html
        非アクティブなウィンドウをスクロール可能にするソフトが必要です。
        
        Windows 10 ではオプションを有効にしてください。
        http://www.lifehacker.jp/2015/09/150909_window_scrolling.html
        
互換性:
        Logitech(ロジクール) の SetPoint は問題ありません。
        ボタンの切り替え(入れ替え)をしてもうまく動きます。
        フィルタドライバのレベルで動いているものは安全だと思います。
        
        グローバルフックのレベルで動いているものは競合しますが、
        WizMouse など安全に使えるものも存在しています。
        起動の順序を考慮することで使えるものもあるかもしれません。
        
        WheelBall や Nadesath など同じ機能のソフトは同時に起動しないでください。
        動作が競合して操作不能になったりします。
        
回復:
        操作が効かなくなったりした時は、まず Ctrl-Alt-Delete でタスクマネージャーを開いてください。
        それだけで制御を取り戻せると思います、効かない場合は何回か繰り返してください。
        その後は、タスクマネージャーでアイコンを探して終了させてください。
        WheelBall などと同時に起動すると、何故か終了できなくなることもあります。
        この場合の終了方法は OS の再起動しかありません。
        # 他に何か方法があったら教えてください。
        
使用方法:
        W10Wheel.exe を実行してください、これは単なる .jar のラッパーになっています。
        タスクトレイに exe ファイルと同様のアイコンが発生するはずです。
        こちらから右クリックメニューで設定を変更できます。
        詳しい方は、設定ファイル (.W10Wheel.properties) を直接編集してください。
        一度起動して終了すれば、設定ファイルはユーザーディレクトリに生成されます。
        
        あとの使い方は WheelBall など同種のソフトと同様になります。
        各トリガーを押すとスクロールモードに移行します。
        マウスまたはボールの操作でスクロールするはずです。
        スクロールモードは何かのボタンを押すと解除されます。
        
        トリガーを押したままスクロールして、離したら止めることもできます。
        # 同時押しでは、両方を押さえたままにするのではなく、片方を先に離すと使いやすくなります。
        
        Middle, X1, X2 のトリガーでは Shift か Ctrl か Alt の
        キーを押しながらトリガーを押すとミドル(中)クリックを送ります。
        
        終了するには、タスクトレイのアイコンをダブルクリックか
        右クリックメニューから Exit を選択してください。
        
メニュー項目:
        Trigger: トリガーを変更 (設定項目を参照)
        Priority: プロセスの優先度を変更
        SetNumber: 数値をセット (設定項目を参照)
        Real Wheel Mode: 実際のホイールに近いスクロール (設定項目を参照)
        Reload Properties: 設定ファイルを再読込
        Cursor Change: スクロールモードのカーソル変更
        Horizontal Scroll: 水平スクロール
        Reverse Scroll: スクロールの方向を逆にする
        Pass Mode: 全てのメッセージをそのまま通す # WheelBall の制御停止
        Info: バージョン番号を表示
        Exit: 終了
        
設定項目:
        firstTrigger: (default: LRTrigger)
                LRTrigger:
                        左から右か、右から左を押すとトリガーになります。 # 同時押し
                        左、右クリックともに次のイベントを待つために遅延します。
                LeftTrigger:
                        左から右を押すとトリガーになります。 # 同時押し
                        右からはトリガーになりません、そのため右クリックの遅延を解消できます。
                RightTrigger:
                        右から左を押すとトリガーになります。 # 同時押し
                        左からはトリガーになりません、そのため左クリックの遅延を解消できます。
                MiddleTrigger:
                        ミドル(中)を押すとトリガーになります。
                X1Trigger:
                        X1を押すとトリガーになります。
                X2Trigger:
                        X2を押すとトリガーになります。
                LeftDragTrigger:
                        左ボタンでドラッグするとスクロールできます。
                        固定はされません、ドラッグしないで離すと左クリックを送ります。
                RightDragTrigger:
                        右ボタンでドラッグするとスクロールできます。
                        固定はされません、ドラッグしないで離すと右クリックを送ります。
                MiddleDragTrigger:
                        ミドル(中)ボタンでドラッグするとスクロールできます。
                        固定はされません、ドラッグしないで離すとミドル(中)クリックを送ります。
                X1DragTrigger:
                        X1ボタンでドラッグするとスクロールできます。
                        固定はされません、ドラッグしないで離すとX1クリックを送ります。
                X2DragTrigger:
                        X2ボタンでドラッグするとスクロールできます。
                        固定はされません、ドラッグしないで離すとX2クリックを送ります。
        
        processPriority:  (default: AboveNormal)
                High: 高
                AboveNormal: 通常以上
                Normal: 通常
                
        pollTimeout: 150-500 (default: 300)
                同時押しのイベント待ち時間(ミリ秒)  # WheelBall の 判定時間 
        scrollLocktime: 150-500 (default: 300)
                トリガーを離してスクロールモードに固定する時間(ミリ秒)
                この時間以内にトリガーを離すとスクロールモードに固定します。
        realWheelMode: bool (default: false)
                実際のホイールに近いスクロール
                こちらのモードではAccel値は使われません。
        cursorChange: bool (default: true)
                スクロールモードのカーソル変更
        verticalAccel: 0-500 (default: 0)
                垂直(通常)スクロールのアクセル値
                スクロールが遅いと思ったら試してみてください。
        verticalThreshold: 0-500 (default: 0)
                垂直(通常)スクロールの閾値
        horizontalScroll: bool (default: true)
                水平スクロール
                使わない人は無効にしてください。
        horizontalAccel: 0-500 (default: 0)
                水平スクロールのアクセル値
        horizontalThreshold: 0-500 (default: 50)
                水辺スクロールの閾値
                この値をあまり小さくすると垂直(通常)スクロールが、使いづらくなります。
        reverseScroll: bool (default: false)
                スクロールの方向を逆にする
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
                
ライセンス:
        The MIT License
        詳しくは License.txt を参照
        
ライブラリ:
        Library.txt を参照
        
アイコン:
        こちらのジェネレーターで作りました。
        http://icon-generator.net/

.exe:
        jarファイルのラッパーはこちらです。
        http://launch4j.sourceforge.net/

連絡:
        使っていて何か問題が見つかったら、
        2ch の該当スレッドに書き込んでください。
        GitHub からも連絡する方法があると思います。
        # まだ GitHub に慣れていないため連絡が遅くなるかもしれません。
        
製作者:
        Yuki Ono
        
著作権:
        Copyright (c) 2016 Yuki Ono
