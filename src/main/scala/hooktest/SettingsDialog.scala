package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.awt._
import java.awt.event.{ WindowAdapter, WindowEvent }
import javax.swing._
import javax.swing.event.{ PopupMenuEvent, PopupMenuListener }

import scala.collection.mutable.ArrayBuffer

import com.formdev.flatlaf.util.UIScale

// The settings dialog (Swing), built in code as in W10Wheel.NET (SettingsForm). A control changes
// its setting at once (then the dialog's changed is called, e.g. to store the properties), and
// shows the setting again when the dialog is refreshed. The texts are English (as in the menu)
// converted by conv to the current language. On the Swing thread.

// The kind of a note of a page (AddNote, AddStatus): its icon and its color. Warning: a setting
// does not work as it is set (e.g. no trigger), in the color of the texts; Info: an explanation
// (e.g. what a setting depends on), in gray; Plain: in gray without an icon (e.g. the state of the
// custom table, under its check box).
enum NoteKind {
    case Info, Warning, Plain
}

// What a control does when the dialog is refreshed (show its setting) and retexted (set its texts
// in the current language)
private final case class Binding(refresh: () => Unit, retext: () => Unit)

private object SettingsUI {
    def ignore(): Unit = ()

    // The icon of the message boxes of the look and feel, as high as a line of text
    final class NoteIcon(var kind: NoteKind, sizeOf: () => Int) extends Icon {
        private def icon: Option[Icon] = kind match {
            case NoteKind.Warning => Option(UIManager.getIcon("OptionPane.warningIcon"))
            case NoteKind.Info => Option(UIManager.getIcon("OptionPane.informationIcon"))
            case NoteKind.Plain => None
        }

        def getIconWidth: Int = if (icon.isEmpty) 0 else sizeOf()
        def getIconHeight: Int = if (icon.isEmpty) 0 else sizeOf()

        def paintIcon(c: Component, g: Graphics, x: Int, y: Int): Unit = {
            icon.foreach(i => {
                val g2 = g.create().asInstanceOf[Graphics2D]
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    g2.translate(x, y)
                    val s = sizeOf().toDouble / i.getIconHeight
                    g2.scale(s, s)
                    i.paintIcon(c, g2, 0, 0)
                }
                finally {
                    g2.dispose()
                }
            })
        }
    }

    // A note with the icon of its kind before it (getNote: its kind and its text, already converted;
    // None: empty). It keeps the height of a line when empty: the page does not change its size.
    def noteRow(getNote: () => Option[(NoteKind, String)], indent: Int): (JComponent, Binding) = {
        val label = new JLabel(" ")
        val icon = new NoteIcon(NoteKind.Plain, () => label.getFontMetrics(label.getFont).getHeight)
        label.setIcon(icon)
        label.setIconTextGap(UIScale.scale(4))
        label.setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 0))

        val show = () => {
            val note = getNote()
            label.setText(note.map(_._2).filter(_.nonEmpty).getOrElse(" "))
            icon.kind = note.map(_._1).getOrElse(NoteKind.Plain)
            label.setForeground(note match {
                case Some((NoteKind.Warning, _)) => Theme.color("Label.foreground", Color.BLACK)
                case _ => Theme.color("Label.disabledForeground", Color.GRAY)
            })
            label.revalidate()
            label.repaint()
        }
        (label, Binding(show, show))
    }

    // A tooltip in HTML: the lines of the text, and the key on a line of its own
    def tipText(text: String, key: Option[String]): String = {
        def esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val lines = esc(text).replace("\n", "<br>")
        key match {
            case Some(k) => s"<html>$lines<br>($k)</html>"
            case None => s"<html>$lines</html>"
        }
    }
}

import SettingsUI._

// A page: rows of a label and a control (with the range of a number), or of a check box. A change
// by the user sets the setting and calls changed (changedLater for a number); a change by refresh
// (isRefreshing) does not. isReloading: the settings have been loaded (Reload, a switch of the
// properties): a number being typed is dropped. tip (of the add methods): an English text shown on
// the controls, converted as the other texts; key: the name of the setting in the properties file,
// shown after it (to find it in the file and the Readme).
final class SettingsPage private[hooktest] (conv: String => String, grid: JPanel, register: Binding => Unit,
                                            isRefreshing: () => Boolean, changed: () => Unit,
                                            changedLater: () => Unit, setTip: (JComponent, String, Option[String]) => Unit,
                                            isReloading: () => Boolean, spinners: ArrayBuffer[JSpinner],
                                            beforeAction: () => Unit) {
    private var row = 0
    private var gap = false

    private def indentWidth = UIScale.scale(20)

    private def tipOn(cs: Seq[JComponent], tip: Option[String], key: Option[String]): Unit =
        tip.foreach(t => cs.foreach(c => setTip(c, t, key)))

    private def addRow(cs: Seq[(JComponent, Int)], indent: Boolean = false): Unit = {
        // The space between the groups of settings (addGap)
        val top = UIScale.scale(if (gap) 12 else 3)
        gap = false

        var col = 0
        cs.foreach { case (c, span) =>
            val gc = new GridBagConstraints()
            gc.gridx = col
            gc.gridy = row
            gc.gridwidth = span
            gc.anchor = GridBagConstraints.LINE_START
            val left = if (col == 0) (if (indent) indentWidth else 0) else UIScale.scale(8)
            gc.insets = new Insets(top, left, UIScale.scale(3), 0)
            c.setOpaque(false)
            grid.add(c, gc)
            col += span
        }
        row += 1
    }

    private def label(engText: String): JLabel = {
        val l = new JLabel()
        register(Binding(ignore, () => l.setText(conv(engText))))
        l
    }

    private def onChange(set: () => Unit): Unit = {
        if (!isRefreshing()) {
            set()
            changed()
        }
    }

    // items: the IDs of the choices and their texts; isCurrent: whether the ID is the setting
    private def comboBox(items: Seq[(String, String)], isCurrent: String => Boolean, set: String => Unit) = {
        val ids = items.map(_._1)
        val cb = new JComboBox[String]()
        cb.addActionListener(_ => {
            val i = cb.getSelectedIndex
            if (i >= 0) onChange(() => set(ids(i)))
        })
        register(Binding(
            () => cb.setSelectedIndex(ids.indexWhere(isCurrent)),
            () => {
                cb.removeAllItems()
                items.foreach { case (_, text) => cb.addItem(conv(text)) }
            }))
        cb
    }

    private def checkBox(engText: String, get: () => Boolean, set: Boolean => Unit) = {
        val cb = new JCheckBox()
        // Only by the user (not setSelected)
        cb.addActionListener(_ => onChange(() => set(cb.isSelected)))
        register(Binding(() => cb.setSelected(get()), () => cb.setText(conv(engText))))
        cb
    }

    private def addNote(getNote: () => Option[(NoteKind, String)], indent: Boolean): Unit = {
        val (c, binding) = noteRow(getNote, if (indent) indentWidth else 0)
        register(binding)
        addRow(Seq((c, 3)))
    }

    def addGap(): Unit =
        gap = true

    // enabled: whether it can be changed (e.g. Dragged Lock for a drag trigger only); indent: an
    // option of the setting above (e.g. of the mouse trigger)
    def addCheck(engText: String, get: () => Boolean, set: Boolean => Unit, enabled: Option[() => Boolean] = None,
                 indent: Boolean = false, tip: Option[String] = None, key: Option[String] = None): Unit = {
        val cb = checkBox(engText, get, set)
        enabled.foreach(f => register(Binding(() => cb.setEnabled(f()), ignore)))
        tipOn(Seq(cb), tip, key)
        addRow(Seq((cb, 3)), indent)
    }

    // A check box with the box of its choice on its right, enabled while it is checked (e.g. the
    // keyboard trigger and its key)
    def addCheckComboBox(engText: String, get: () => Boolean, set: Boolean => Unit,
                         items: Seq[(String, String)], isCurrent: String => Boolean, setItem: String => Unit,
                         tip: Option[String] = None, key: Option[String] = None): Unit = {
        val check = checkBox(engText, get, set)
        val cb = comboBox(items, isCurrent, setItem)
        register(Binding(() => cb.setEnabled(check.isSelected), ignore))
        tipOn(Seq(check, cb), tip, key)
        addRow(Seq((check, 1), (cb, 2)))
    }

    // A button of an action (e.g. Restart as administrator), with the shield of UAC before its text
    // if shield (as high as a line of its text, drawn by Windows at the scale of the display).
    // enabled: as addCheck (e.g. not while the action is in progress).
    def addButton(engText: String, onClick: () => Unit, shield: Boolean = false,
                  enabled: Option[() => Boolean] = None, tip: Option[String] = None): Unit = {
        val b = new JButton()
        // A number waiting to be stored first, as the buttons of the dialog do
        b.addActionListener(_ => {
            beforeAction()
            onClick()
        })
        register(Binding(
            () => enabled.foreach(f => b.setEnabled(f())),
            () => {
                b.setText(conv(engText))
                if (shield) shieldIcon(b.getFontMetrics(b.getFont).getHeight).foreach(b.setIcon)
            }))
        tipOn(Seq(b), tip, None)
        addRow(Seq((b, 3)))
    }

    // The shield at size (in the units of the UI) for each scale of the displays: the one of the
    // display is drawn (not a smaller one scaled up)
    private def shieldIcon(size: Int): Option[Icon] = {
        val scales = Seq(1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)
        val images = scales.flatMap(s => Admin.shieldImage(math.round(size * s).toInt))
        if (images.isEmpty) None
        else Some(new ImageIcon(new java.awt.image.BaseMultiResolutionImage(images*)))
    }

    // A short note (e.g. what a setting of the page depends on)
    def addNote(engText: String, kind: NoteKind = NoteKind.Info): Unit =
        addNote(() => Some((kind, conv(engText))), false)

    // A note that follows the settings (its kind and its text, already converted; None if none):
    // e.g. the state of the custom table
    def addStatus(getNote: () => Option[(NoteKind, String)], indent: Boolean = false): Unit =
        addNote(getNote, indent)

    // A number out of its range cannot be entered (the box goes back to the number). enabled: as
    // addCheck; unit: shown after the range (e.g. ms).
    def addNumber(name: String, low: Int, up: Int, get: () => Int, set: Int => Unit,
                  enabled: Option[() => Boolean] = None, unit: Option[String] = None,
                  tip: Option[String] = None, key: Option[String] = None): Unit = {
        val model = new SpinnerNumberModel(low, low, up, 1)
        val sp = new JSpinner(model)
        // No thousands separator
        val editor = new JSpinner.NumberEditor(sp, "#")
        sp.setEditor(editor)
        val field = editor.getTextField
        field.setColumns(4)
        field.setHorizontalAlignment(SwingConstants.RIGHT)
        spinners += sp

        sp.addChangeListener(_ => {
            // Applied at once, stored a little later: holding a button of the box changes it many
            // times
            if (!isRefreshing()) {
                set(model.getNumber.intValue)
                changedLater()
            }
        })
        register(Binding(
            () => {
                val v = math.max(low, math.min(up, get()))
                // Only if the setting has changed, or the settings have been loaded (Reload,
                // --sendReloadProp: the file is taken, even if its number is the same): a number
                // being typed is dropped. Otherwise it is left as typed, set when it is entered.
                if (v != model.getNumber.intValue || isReloading()) {
                    model.setValue(Integer.valueOf(v))
                    field.setValue(Integer.valueOf(v))
                }
            },
            ignore))

        val rangeText = unit match {
            case Some(u) => s"($low - $up $u)"
            case None => s"($low - $up)"
        }
        val range = new JLabel(rangeText)
        val l = label(name)
        enabled.foreach(f => register(Binding(() => {
            val b = f()
            Seq[JComponent](l, sp, range).foreach(_.setEnabled(b))
        }, ignore)))
        tipOn(Seq(l, sp, field), tip, key)
        addRow(Seq((l, 1), (sp, 1), (range, 1)))
    }

    def addComboBox(engText: String, items: Seq[(String, String)], isCurrent: String => Boolean, set: String => Unit,
                    tip: Option[String] = None, key: Option[String] = None): Unit = {
        val cb = comboBox(items, isCurrent, set)
        val l = label(engText)
        tipOn(Seq(l, cb), tip, key)
        addRow(Seq((l, 1), (cb, 2)))
    }

    // A list of items (e.g. the paths of the excluded programs) with Add and Remove under it. get:
    // the items; set: the items as they are to be (a change by the user). ask: the items to add
    // (e.g. chosen in a file dialog), none if canceled; they are selected once added. sameItem:
    // whether two items are the same, as set compares them (an item added that is already in is
    // selected where it is). Remove (or the Delete key) removes the selected items. A long item
    // scrolls horizontally: the list keeps its width.
    def addList(get: () => Seq[String], set: Seq[String] => Unit, ask: () => Seq[String],
                addText: String, removeText: String, sameItem: (String, String) => Boolean,
                tip: Option[String] = None, key: Option[String] = None): Unit = {
        val model = new DefaultListModel[String]()
        val list = new JList[String](model)
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        val scroll = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                                     ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        scroll.setPreferredSize(new Dimension(UIScale.scale(380), UIScale.scale(150)))
        // The items shown (set by refresh)
        var shown = Seq.empty[String]

        val add = new JButton()
        // Nothing selected: also when there is no item
        val remove = new JButton()
        remove.setEnabled(false)
        val buttons = new JPanel()
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS))
        buttons.add(add)
        buttons.add(Box.createHorizontalStrut(UIScale.scale(6)))
        buttons.add(remove)

        def updateRemove(): Unit = remove.setEnabled(!list.isSelectionEmpty)
        def show(items: Seq[String]): Unit = {
            model.clear()
            items.foreach(model.addElement)
            shown = items
            updateRemove()
        }

        list.addListSelectionListener(_ => updateRemove())
        // The change shows the items again (changed refreshes the dialog)
        def setItems(items: Seq[String]): Unit = onChange(() => set(items))
        def removeSelected(): Unit = {
            val selected = list.getSelectedIndices.toSet
            if (selected.nonEmpty) {
                val index = selected.min
                setItems(shown.zipWithIndex.collect { case (item, i) if !selected.contains(i) => item })
                // The item after the removed ones selected, as Explorer does: Delete again goes on
                if (model.size > 0) {
                    val i = math.min(index, model.size - 1)
                    list.setSelectedIndex(i)
                    list.ensureIndexIsVisible(i)
                }
            }
        }

        add.addActionListener(_ => {
            // A number waiting to be stored first, as the buttons of the dialog do
            beforeAction()
            val added = ask()
            if (added.nonEmpty) {
                setItems(shown ++ added)
                // The items chosen selected: the new ones, and those already in the list (where they
                // were), which shows them
                val indices = shown.indices.filter(i => added.exists(sameItem(_, shown(i)))).toArray
                list.setSelectedIndices(indices)
                indices.headOption.foreach(list.ensureIndexIsVisible)
                list.requestFocusInWindow()
            }
        })
        remove.addActionListener(_ => {
            beforeAction()
            removeSelected()
            list.requestFocusInWindow()
        })
        list.addKeyListener(new java.awt.event.KeyAdapter {
            override def keyPressed(e: java.awt.event.KeyEvent): Unit = {
                if (e.getKeyCode == java.awt.event.KeyEvent.VK_DELETE) {
                    beforeAction()
                    removeSelected()
                    e.consume()
                }
            }
        })
        register(Binding(
            // Only if they have changed: the selection is kept
            () => { val items = get(); if (items != shown) show(items) },
            () => {
                add.setText(conv(addText))
                remove.setText(conv(removeText))
            }))
        tipOn(Seq(list), tip, key)
        addRow(Seq((scroll, 3)))
        addRow(Seq((buttons, 3)))
    }

    // A group of its own: the other radio buttons of the page are not unchecked. enabled: as addCheck.
    def addRadioButtons(items: Seq[(String, String)], isCurrent: String => Boolean, set: String => Unit,
                        enabled: Option[() => Boolean] = None, tip: Option[String] = None,
                        key: Option[String] = None): Unit = {
        val panel = new JPanel()
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS))
        val group = new ButtonGroup()
        val buttons = items.map { case (id, engText) =>
            val rb = new JRadioButton()
            rb.setOpaque(false)
            rb.addActionListener(_ => if (rb.isSelected) onChange(() => set(id)))
            register(Binding(() => rb.setSelected(isCurrent(id)), () => rb.setText(conv(engText))))
            tipOn(Seq(rb), tip, key)
            group.add(rb)
            panel.add(rb)
            rb
        }
        enabled.foreach(f => register(Binding(() => {
            val b = f()
            buttons.foreach(_.setEnabled(b))
        }, ignore)))
        addRow(Seq((panel, 3)), indent = true)
    }
}

// The dialog: the properties at the top (addPropertiesComboBox, addPropertiesButton), the pages
// (addPage) with their list on the left, and Close. Closing hides it: it is made once, and shown
// again (show).
final class SettingsDialog(conv: String => String) {
    private val bindings = ArrayBuffer.empty[Binding]
    private var refreshing = false
    // Set by refreshAll: the list of the properties is read again (the files may have been added or
    // deleted), not at every change of a setting; a number being typed is dropped
    private var readLists = false
    private val spinners = ArrayBuffer.empty[JSpinner]

    // Called after a setting has been changed in the dialog (not after a properties switch)
    var changed: () => Unit = () => ()
    // Why the changes are not stored (its kind and its text, converted), None if they are: shown at
    // the bottom instead of the hint that they are
    var status: () => Option[(NoteKind, String)] = () => None

    // A change of a number calls changed 300 ms after the last one (changedLater): changed stores
    // the properties, which a button held on a number box would do many times. Called at once
    // (flushChanged) before the dialog is closed and before the buttons act.
    private val changedTimer = new javax.swing.Timer(300, _ => changed())
    changedTimer.setRepeats(false)

    private def changedLater(): Unit =
        changedTimer.restart()

    def flushChanged(): Unit = {
        if (changedTimer.isRunning) {
            changedTimer.stop()
            changed()
        }
    }

    // A change waiting to be stored, dropped by the button being clicked (dropPending): it waits
    // again if restorePending is called (e.g. Reload could not read the file)
    private var dropped = false

    val frame = new JFrame()
    private val top = new JPanel(new FlowLayout(FlowLayout.LEADING, UIScale.scale(6), 0))
    private val navModel = new DefaultListModel[String]()
    private val nav = new JList[String](navModel)
    private val cards = new CardLayout()
    // In the color of the lists (as the area of the pages of the browsers' settings), also after the
    // theme has changed
    private val content = new JPanel(cards) {
        override def updateUI(): Unit = {
            super.updateUI()
            setBackground(Theme.color("List.background", Color.WHITE))
        }
    }
    private val pageNames = ArrayBuffer.empty[String]
    private val bottomLeft = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0))
    private val closeButton = new JButton()

    private def register(b: Binding): Unit = bindings += b

    private def setTip(c: JComponent, engText: String, key: Option[String]): Unit =
        register(Binding(ignore, () => c.setToolTipText(tipText(conv(engText), key))))

    // The controls set from the settings do not change them (see SettingsPage.onChange)
    private def withRefreshing(f: () => Unit): Unit = {
        refreshing = true
        try f() finally refreshing = false
    }

    // The dialog moved into the working area of its display (as far as it fits)
    private def moveIntoWorkingArea(): Unit = {
        val gc = frame.getGraphicsConfiguration
        val b = gc.getBounds
        val in = Toolkit.getDefaultToolkit.getScreenInsets(gc)
        val (l, t) = (b.x + in.left, b.y + in.top)
        val (r, btm) = (b.x + b.width - in.right, b.y + b.height - in.bottom)
        val x = math.max(l, math.min(frame.getX, r - frame.getWidth))
        val y = math.max(t, math.min(frame.getY, btm - frame.getHeight))
        if (x != frame.getX || y != frame.getY) frame.setLocation(x, y)
    }

    // Fitted to its contents (a text may have become wider or narrower, e.g. another language), and
    // kept on the screen
    private def fit(): Unit = {
        if (frame.isDisplayable && frame.getPreferredSize != frame.getSize) {
            frame.pack()
            if (frame.isVisible) moveIntoWorkingArea()
        }
    }

    private def refresh(): Unit = {
        withRefreshing(() => bindings.foreach(_.refresh()))
        fit()
    }

    private def retext(): Unit = {
        withRefreshing(() => {
            frame.setTitle(s"${Context.PROGRAM_NAME} - ${conv("Settings")}")
            val sel = math.max(0, nav.getSelectedIndex)
            navModel.clear()
            pageNames.foreach(n => navModel.addElement(conv(n)))
            nav.setSelectedIndex(sel)
            bindings.foreach(_.retext())
            bindings.foreach(_.refresh())
        })
        fit()
    }

    // The numbers being typed are set (as leaving their box would)
    private def commitNumbers(): Unit = {
        spinners.foreach(sp => {
            try {
                sp.commitEdit()
            }
            catch {
                case _: java.text.ParseException =>
                    sp.getEditor match {
                        case e: JSpinner.DefaultEditor => e.getTextField.setValue(sp.getValue)
                        case _ => ()
                    }
            }
        })
    }

    // However the dialog is closed (Esc, Close, the close box, the exit of W10Wheel): the changes are
    // not canceled
    def close(): Unit = {
        commitNumbers()
        flushChanged()
        frame.setVisible(false)
    }

    // At exit: a number being typed, or waiting to be stored, is stored
    def commitAll(): Unit = {
        commitNumbers()
        flushChanged()
    }

    locally {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)
        frame.addWindowListener(new WindowAdapter {
            override def windowClosing(e: WindowEvent): Unit = close()
        })
        frame.setResizable(false)
        frame.setIconImages(Tray.appIcons)
        frame.getRootPane.registerKeyboardAction(_ => close(), KeyStroke.getKeyStroke("ESCAPE"),
                                                 JComponent.WHEN_IN_FOCUSED_WINDOW)

        val (hint, hintBinding) = noteRow(() => status() match {
            case Some(note) => Some(note)
            case None => Some((NoteKind.Plain, conv("Changes are applied and saved at once")))
        }, 0)
        register(hintBinding)
        register(Binding(ignore, () => closeButton.setText(conv("Close"))))
        closeButton.addActionListener(_ => close())

        val gap = UIScale.scale(8)
        val bottom = new JPanel(new BorderLayout(UIScale.scale(12), 0))
        bottom.add(bottomLeft, BorderLayout.WEST)
        bottom.add(hint, BorderLayout.CENTER)
        bottom.add(closeButton, BorderLayout.EAST)
        bottom.setBorder(BorderFactory.createEmptyBorder(gap, 0, 0, 0))

        // The pages are chosen in a list on the left (as in the settings of the browsers), and shown
        // on the right, one at a time
        nav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        nav.setOpaque(false)
        nav.setFixedCellHeight(-1)
        nav.setCellRenderer(new DefaultListCellRenderer {
            override def getListCellRendererComponent(list: JList[?], value: Any, index: Int,
                                                      isSelected: Boolean, cellHasFocus: Boolean): Component = {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                setBorder(BorderFactory.createEmptyBorder(UIScale.scale(5), UIScale.scale(8),
                                                          UIScale.scale(5), UIScale.scale(16)))
                c
            }
        })
        nav.addListSelectionListener(e => {
            val i = nav.getSelectedIndex
            if (i >= 0) cards.show(content, i.toString)
        })

        content.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.color("Component.borderColor", Color.LIGHT_GRAY)),
            BorderFactory.createEmptyBorder(UIScale.scale(12), UIScale.scale(12), UIScale.scale(12), UIScale.scale(12))))

        val middle = new JPanel(new BorderLayout(gap, 0))
        val navPanel = new JPanel(new BorderLayout())
        navPanel.add(nav, BorderLayout.NORTH)
        middle.add(navPanel, BorderLayout.WEST)
        middle.add(content, BorderLayout.CENTER)

        top.setBorder(BorderFactory.createEmptyBorder(0, 0, gap, 0))

        val root = new JPanel(new BorderLayout())
        root.setBorder(BorderFactory.createEmptyBorder(gap, gap + UIScale.scale(2), gap, gap + UIScale.scale(2)))
        root.add(top, BorderLayout.NORTH)
        root.add(middle, BorderLayout.CENTER)
        root.add(bottom, BorderLayout.SOUTH)
        frame.setContentPane(root)

        // The colors set from the look and feel (the notes, the border): set again
        Theme.addListener(() => {
            content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.color("Component.borderColor", Color.LIGHT_GRAY)),
                content.getBorder.asInstanceOf[javax.swing.border.CompoundBorder].getInsideBorder))
            if (frame.isDisplayable) refresh()
        })
    }

    def addPage(engText: String): SettingsPage = {
        val index = pageNames.length
        pageNames += engText

        // The rows at the top left of the page
        val grid = new JPanel(new GridBagLayout())
        grid.setOpaque(false)
        val north = new JPanel(new BorderLayout())
        north.setOpaque(false)
        north.add(grid, BorderLayout.WEST)
        val page = new JPanel(new BorderLayout())
        page.setOpaque(false)
        page.add(north, BorderLayout.NORTH)
        content.add(page, index.toString)

        new SettingsPage(conv, grid, register, () => refreshing, () => changed(), () => changedLater(),
                         setTip, () => readLists, spinners, () => { commitNumbers(); flushChanged() })
    }

    def addPropertiesLabel(engText: String): Unit = {
        val l = new JLabel()
        register(Binding(ignore, () => l.setText(conv(engText))))
        top.add(l)
    }

    // getItems: the names of the properties and their texts, read again when the list opens (the
    // files may have been added or deleted); switchTo: switch to the name. Not changed: no setting
    // has been changed by the user.
    def addPropertiesComboBox(getItems: () => Seq[(String, String)], isCurrent: String => Boolean,
                              switchTo: String => Unit): Unit = {
        val cb = new JComboBox[String]()
        var ids = Seq.empty[String]

        def selectCurrent(): Unit =
            cb.setSelectedIndex(ids.indexWhere(isCurrent))

        def reload(): Unit = {
            val items = getItems()
            ids = items.map(_._1)
            cb.removeAllItems()
            items.foreach { case (_, text) => cb.addItem(text) }
            // The names are the user's: a long one would widen the dialog without a limit
            cb.setPreferredSize(null)
            val pref = cb.getPreferredSize
            cb.setPreferredSize(new Dimension(math.min(pref.width, UIScale.scale(240)), pref.height))
            selectCurrent()
        }

        cb.addPopupMenuListener(new PopupMenuListener {
            // Also fitted and kept on the screen: a longer name added outside W10Wheel may widen the
            // dialog
            def popupMenuWillBecomeVisible(e: PopupMenuEvent): Unit = {
                withRefreshing(() => reload())
                fit()
            }
            def popupMenuWillBecomeInvisible(e: PopupMenuEvent): Unit = ()
            def popupMenuCanceled(e: PopupMenuEvent): Unit = ()
        })
        cb.addActionListener(_ => {
            val i = cb.getSelectedIndex
            if (!refreshing && i >= 0 && !isCurrent(ids(i))) {
                // Taken first: storing a number waiting (flushChanged) refreshes the dialog, which
                // selects the current properties again
                val id = ids(i)
                flushChanged()
                switchTo(id)
            }
        })
        register(Binding(
            // Read again after a load, Add, Delete or Reload (refreshAll): a deleted name left in
            // the list could be chosen, and its file written again. After a change of a setting,
            // only for a name not in the list.
            () => if (readLists || !ids.exists(isCurrent)) reload() else selectCurrent(),
            () => reload()))
        top.add(cb)
    }

    // dropPending: the change waiting to be stored is not stored (Reload: storing would write the
    // settings over the file edited outside W10Wheel, which is about to be read); onClick makes it
    // wait again if nothing could be read (restorePending)
    def addPropertiesButton(engText: String, onClick: () => Unit, enabled: Option[() => Boolean] = None,
                            dropPending: Boolean = false): Unit = {
        val b = new JButton()
        b.addActionListener(_ => {
            if (dropPending) {
                commitNumbers()
                dropped = changedTimer.isRunning
                changedTimer.stop()
            }
            else {
                commitNumbers()
                flushChanged()
            }
            try onClick() finally dropped = false
        })
        register(Binding(() => enabled.foreach(f => b.setEnabled(f())), () => b.setText(conv(engText))))
        top.add(b)
    }

    // A button at the bottom left, for the whole dialog (e.g. Reset to defaults)
    def addBottomButton(engText: String, onClick: () => Unit): Unit = {
        val b = new JButton()
        b.addActionListener(_ => {
            commitNumbers()
            flushChanged()
            onClick()
        })
        register(Binding(ignore, () => b.setText(conv(engText))))
        bottomLeft.add(b)
    }

    // Shows the settings again after a change in the dialog
    def refreshValues(): Unit =
        refresh()

    // Shows the settings and the list of the properties again (after a load, Add, Delete...): later,
    // a switch is made by the box of the properties in its handler. From any thread.
    def refreshAllLater(): Unit = SwingUtilities.invokeLater(() => {
        if (frame.isDisplayable) {
            readLists = true
            try refresh() finally readLists = false
        }
    })

    // From the onClick of a button that drops the change waiting to be stored (dropPending): it waits
    // again, stored as it would have been (nothing if there was none)
    def restorePending(): Unit = {
        if (dropped) {
            dropped = false
            changedLater()
        }
    }

    // Shows the settings again later, without reading the list of the properties again, nor
    // dropping a number being typed (e.g. after a Reload that could not read the file). Any thread.
    def refreshLater(): Unit = SwingUtilities.invokeLater(() => if (frame.isDisplayable) refresh())

    // The language has changed. Later: maybe called from the handler of a control that retext
    // changes (the language box). Any thread.
    def retextLater(): Unit = SwingUtilities.invokeLater(() => if (frame.isDisplayable) retext())

    def isVisible: Boolean = frame.isVisible

    def show(): Unit = {
        if (!frame.isVisible) {
            val first = !frame.isDisplayable
            // The settings may have been changed (e.g. Reload) or the language since it was shown
            retext()
            frame.pack()
            if (first) {
                // On the display of the cursor
                val pt = Option(MouseInfo.getPointerInfo).map(_.getLocation).getOrElse(new Point(0, 0))
                val gc = GraphicsEnvironment.getLocalGraphicsEnvironment.getScreenDevices
                    .map(_.getDefaultConfiguration).find(_.getBounds.contains(pt))
                    .getOrElse(frame.getGraphicsConfiguration)
                val b = gc.getBounds
                frame.setLocation(b.x + (b.width - frame.getWidth) / 2, b.y + (b.height - frame.getHeight) / 2)
            }
            moveIntoWorkingArea()
            frame.setVisible(true)
            // The focus on the list of the pages, not on the properties (the first control)
            nav.requestFocusInWindow()
        }

        if ((frame.getExtendedState & Frame.ICONIFIED) != 0)
            frame.setExtendedState(frame.getExtendedState & ~Frame.ICONIFIED)
        frame.toFront()
        frame.requestFocus()
    }
}
