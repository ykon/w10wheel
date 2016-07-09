名前:
        W10Wheel

バージョン:
        0.3

URL:
        https://github.com/ykon/w10wheel

概要:
        マウスホイールシミュレーター

履歴:
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
        Trigger: 設定項目を参照
        SetNumber: 設定項目を参照
        Reload Properties: 設定ファイルを再読込
        Cursor Change: スクロールモードのカーソル変更
        Horizontal Scroll: 水平スクロール
        Pass Mode: 全てのメッセージをそのまま通す # WheelBall の制御停止
        Version: バージョン番号を表示
        Exit: 終了
        
設定項目:
        firstTrigger:
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
                LeftOnlyTrigger:
                        左を押すとトリガーになります。
                        固定はされません、ドラッグしないで離すと左クリックを送ります。
                RightOnlyTrigger:
                        右を押すとトリガーになります。
                        固定はされません、ドラッグしないで離すと右クリックを送ります。
        
        pollTimeout: 150-500 (デフォルト: 300)
                同時押しのイベント待ち時間(ミリ秒)  # WheelBall の 判定時間 
        scrollLocktime: 150-500 (デフォルト: 300)
                トリガーを離してスクロールモードに固定する時間(ミリ秒)
                この時間以内にトリガーを離すとスクロールモードに固定します。
        cursorChange: bool (デフォルト: true)
                スクロールモードのカーソル変更
        verticalAccel: 0-500 (デフォルト: 0)
                垂直(通常)スクロールのアクセル値
                スクロールが遅いと思ったら試してください。
        verticalThreshold: 0-500 (デフォルト: 0)
                垂直(通常)スクロールの閾値
        horizontalScroll: bool (デフォルト: true)
                水平スクロール
                使わない人は無効にしてください。
        horizontalAccel: 0-500 (デフォルト: 0)
                水平スクロールのアクセル値
        horizontalThreshold: 0-500 (デフォルト: 50)
                水辺スクロールの閾値
                この値をあまり小さくすると垂直(通常)スクロールが、使いづらくなります。

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
