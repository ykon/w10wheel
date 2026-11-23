package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.awt.{ BorderLayout, Component, Cursor, Desktop, Dimension, FlowLayout, Window }
import java.awt.event.{ MouseAdapter, MouseEvent => AwtMouseEvent }
import java.net.URI
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import javax.swing._
import javax.swing.event.{ DocumentEvent, DocumentListener }

// The message boxes (Swing, JOptionPane), in the language of W10Wheel.
//
// The thread: shown at once on the Swing thread. From the hook thread while its loop runs (e.g. an
// error of a load by --sendReloadProp), later on the Swing thread: the hooks must not wait for it.
// Else (at startup and exit, before the hooks run), shown and waited for.
object Dialog {
    private val logger = Logger.getLogger()
    private lazy val convLang: String => String = Context.convLang

    // The owner of the boxes shown without one: the settings dialog while it is shown (they are
    // modal to it and centered on it), else none (on top of the other windows: W10Wheel has no
    // window in the foreground)
    @volatile private var defaultOwner: () => Option[Window] = () => None

    def setDefaultOwner(f: () => Option[Window]): Unit =
        defaultOwner = f

    private def onUI(f: () => Unit): Unit = {
        if (SwingUtilities.isEventDispatchThread)
            f()
        else {
            Theme.setup()
            if (HookThread.isCurrent && HookThread.isLooping)
                SwingUtilities.invokeLater(() => f())
            else
                SwingUtilities.invokeAndWait(() => f())
        }
    }

    // On the Swing thread
    private def show(owner: Option[Window], pane: JOptionPane, title: String): Unit = {
        val o = owner.orElse(defaultOwner())
        val dialog = pane.createDialog(o.orNull, title)
        if (o.isEmpty) {
            dialog.setAlwaysOnTop(true)
            dialog.setIconImages(Tray.appIcons)
        }
        dialog.setVisible(true)
        dialog.dispose()
    }

    private def messagePane(msg: Any, kind: Int) = {
        val pane = new JOptionPane(msg, kind)
        pane.setOptions(Array[AnyRef](convLang("OK")))
        pane
    }

    def errorMessage(msg: String, title: String = "Error"): Unit = {
        logger.debug(s"errorMessage: $msg")
        onUI(() => show(None, messagePane(msg, JOptionPane.ERROR_MESSAGE), convLang(title)))
    }

    def errorMessageE(e: Exception): Unit =
        errorMessage(e.getMessage, e.getClass.getName)

    // An error of an action of the settings dialog (owner): what failed (heading) and why
    def showError(owner: Window, heading: String, e: Throwable): Unit = {
        val msg = Option(e.getMessage).getOrElse(e.toString)
        show(Some(owner), messagePane(s"$heading\n\n$msg", JOptionPane.ERROR_MESSAGE), Context.PROGRAM_NAME)
    }

    // The confirmation of an action that cannot be undone, modal to owner (the settings dialog): its
    // button says what it does, and Cancel is the default (Enter does not do it by mistake). On the
    // Swing thread.
    def confirm(owner: Window, heading: String, text: String, actionText: String): Boolean = {
        val cancel = convLang("Cancel")
        val options = Array[AnyRef](actionText, cancel)
        val pane = new JOptionPane(s"$heading\n\n$text", JOptionPane.WARNING_MESSAGE,
                                   JOptionPane.DEFAULT_OPTION, null, options, cancel)
        show(Some(owner), pane, Context.PROGRAM_NAME)
        pane.getValue == actionText
    }

    // A text asked in a small dialog modal to owner (e.g. the name of new properties). None if
    // canceled or empty. validate: why the text cannot be used (an English text), checked as it is
    // typed: shown under the box, and OK disabled (also while the box is empty). On the Swing thread.
    def askText(owner: Window, title: String, prompt: String, validate: String => Option[String]): Option[String] = {
        val dialog = new JDialog(owner, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        val box = new JTextField(24)
        val error = new JLabel(" ")
        error.setForeground(Theme.color("Component.error.focusedBorderColor", java.awt.Color.RED))
        val ok = new JButton(convLang("OK"))
        val cancel = new JButton(convLang("Cancel"))
        var res: Option[String] = None

        val panel = new JPanel()
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS))
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))
        val promptLabel = new JLabel(prompt)
        Seq[JComponent](promptLabel, box, error).foreach(c => {
            c.setAlignmentX(Component.LEFT_ALIGNMENT)
            panel.add(c)
        })
        panel.add(Box.createVerticalStrut(4))
        val buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0))
        buttons.add(ok)
        buttons.add(cancel)
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT)
        panel.add(buttons)
        box.setMaximumSize(new Dimension(Int.MaxValue, box.getPreferredSize.height))

        def check(): Unit = {
            val text = box.getText
            val why = if (text.isEmpty) None else validate(text)
            error.setText(why.map(convLang).getOrElse(" "))
            ok.setEnabled(text.nonEmpty && why.isEmpty)
        }
        box.getDocument.addDocumentListener(new DocumentListener {
            def insertUpdate(e: DocumentEvent): Unit = check()
            def removeUpdate(e: DocumentEvent): Unit = check()
            def changedUpdate(e: DocumentEvent): Unit = check()
        })
        check()

        ok.addActionListener(_ => {
            res = Some(box.getText).filter(_.nonEmpty)
            dialog.dispose()
        })
        cancel.addActionListener(_ => dialog.dispose())
        dialog.getRootPane.setDefaultButton(ok)
        dialog.getRootPane.registerKeyboardAction(_ => dialog.dispose(),
            KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW)

        dialog.setContentPane(panel)
        dialog.setResizable(false)
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.setVisible(true)
        res
    }

    // At startup when W10Wheel is not running as administrator (before the hooks): whether to restart
    // as administrator, and whether not to ask again ("Do not ask again" checked with Continue).
    // The restart has the shield of UAC. Closed (Esc): continue.
    def askRestartAsAdmin(): (Boolean, Boolean) = {
        var res = (false, false)
        onUI(() => {
            val heading = new JLabel(s"<html><b>${convLang("W10Wheel is not running as administrator")}</b></html>")
            val text = new JLabel(s"<html><div style='width: 320px'>${convLang(AdminMessage)}</div></html>")
            val dontAsk = new JCheckBox(convLang("Do not ask again"))
            val panel = new JPanel()
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS))
            Seq[JComponent](heading, text, dontAsk).foreach(c => {
                c.setAlignmentX(Component.LEFT_ALIGNMENT)
                panel.add(c)
                panel.add(Box.createVerticalStrut(8))
            })

            val restart = new JButton(convLang("Restart as administrator"))
            restart.setToolTipText(convLang("Windows asks for permission (User Account Control)"))
            val lineHeight = restart.getFontMetrics(restart.getFont).getHeight
            val shields = Seq(1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0).flatMap(s => Admin.shieldImage(math.round(lineHeight * s).toInt))
            if (shields.nonEmpty)
                restart.setIcon(new ImageIcon(new java.awt.image.BaseMultiResolutionImage(shields*)))
            val continueWithout = new JButton(convLang("Continue without administrator rights"))

            val pane = new JOptionPane(panel, JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
                                       Array[AnyRef](restart, continueWithout), restart)
            Seq(restart, continueWithout).foreach(b => b.addActionListener(_ => pane.setValue(b)))
            show(None, pane, Context.PROGRAM_NAME)

            val v = pane.getValue
            // Only with Continue: the choice to run without the rights. Not if closed or restarted.
            res = (v eq restart, (v eq continueWithout) && dontAsk.isSelected)
        })
        res
    }

    // Why W10Wheel should run as administrator (as in W10Wheel.NET)
    val AdminMessage =
        "So it won't work in the windows of some programs (those running as administrator). It's highly recommended to run it as administrator."

    // A link (e.g. in About): opened in the browser
    private def link(text: String, url: String): JLabel = {
        val l = new JLabel(s"<html><a href=''>$text</a></html>")
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
        l.addMouseListener(new MouseAdapter {
            override def mouseClicked(e: AwtMouseEvent): Unit = {
                try {
                    Desktop.getDesktop.browse(new URI(url))
                }
                catch {
                    case ex: Exception => logger.warn(s"browse: $ex")
                }
            }
        })
        l
    }

    // The version of W10Wheel and of Java. On the Swing thread.
    def showAbout(): Unit = {
        val text = new JLabel(
            s"<html><b>${Context.PROGRAM_NAME}</b><br><br>" +
            s"${convLang("Version")}: ${Context.PROGRAM_VERSION}<br>" +
            s"${convLang("Running as administrator")}: ${convLang(if (Admin.isAdmin) "Yes" else "No")}<br>" +
            s"Java: ${System.getProperty("java.version")} (${System.getProperty("os.arch")})</html>")
        val panel = new JPanel(new BorderLayout(0, 8))
        panel.add(text, BorderLayout.CENTER)
        panel.add(link("GitHub", Context.PROJECT_URL), BorderLayout.SOUTH)

        val icon = new ImageIcon(Tray.appIcon(48))
        val pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, icon)
        pane.setOptions(Array[AnyRef](convLang("OK")))
        show(None, pane, convLang("About"))
    }

    // Waits (at most timeoutMs) for f run on the Swing thread: at exit, the main thread. Not if the
    // Swing thread does not answer (e.g. blocked): W10Wheel exits anyway.
    def runOnUIAndWait(timeoutMs: Long)(f: () => Unit): Unit = {
        val done = new CountDownLatch(1)
        SwingUtilities.invokeLater(() => {
            try f() finally done.countDown()
        })
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS))
            logger.warn("The Swing thread did not answer")
    }
}
