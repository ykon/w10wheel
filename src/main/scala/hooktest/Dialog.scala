package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.language.implicitConversions

import org.eclipse.swt.SWT
import org.eclipse.swt.widgets._
import org.eclipse.swt.events._
import org.eclipse.swt.graphics.Image
import org.eclipse.swt.graphics.Point
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.layout.GridLayout

object Dialog {
    implicit def toListener(f: Event => Unit) = new Listener {
        override def handleEvent(e: Event) { f(e) }
    }
    
    def errorMessage(parent: Shell, msg: String, title: String = "Error") {
        val mb = new MessageBox(parent, SWT.OK | SWT.ICON_ERROR)
        mb.setText(title)
        mb.setMessage(msg)
        mb.open()
    }
    
    def errorMessage(parent: Shell, e: Exception) {
        errorMessage(parent, e.getMessage, e.getClass.getName)
    }
    
    def openYesNoMessage(parent: Shell, msg: String, title: String = "Question"): Boolean = {
        val mb = new MessageBox(parent, SWT.YES | SWT.NO | SWT.ICON_QUESTION)
        mb.setText(title)
        mb.setMessage(msg)
        mb.open() == SWT.YES
    }
    
    class NumberInputDialog(parent: Shell, name: String, low: Int, up: Int) extends Dialog(parent) {
        private def createSpinner(shell: Shell) = {
            val spinner = new Spinner(shell, SWT.BORDER)
            spinner.setMinimum(low)
            spinner.setMaximum(up)
            spinner.setSelection(Context.getNumberOfName(name))
            spinner
        }
        
        private def createShell = {
            val shell = new Shell(getParent, SWT.TITLE | SWT.BORDER | SWT.APPLICATION_MODAL | SWT.ON_TOP)
            shell.setText("Set Number")
            shell.setLayout(new GridLayout(2, false))
            shell
        }
        
        private def createLabel(shell: Shell) = {
            val label = new Label(shell, SWT.NULL)
            label.setText(s"$name ($low - $up): ")
            label
        }
        
        private def createButton(shell: Shell, text: String, gridData: GridData) = {
            val button = new Button(shell, SWT.PUSH)
            button.setText(text)
            button.setLayoutData(gridData)
            button
        }
        
        private def makeKeyAdapter(ok: Button, cancel: Button) = {
            new KeyAdapter {
                override def keyReleased(e: KeyEvent) {
                    if (e.character == SWT.CR)
                        ok.notifyListeners(SWT.Selection, null)
                    else if (e.keyCode == SWT.ESC)
                        cancel.notifyListeners(SWT.Selection, null)
                }
            }
        }
        
        private def messageLoop(shell: Shell) = {
            val display = getParent.getDisplay
            while (!shell.isDisposed) {
                if (!display.readAndDispatch)
                    display.sleep
            }            
        }
        
        private def setLocation(shell: Shell) {
            val pt = Display.getDefault.getCursorLocation
            shell.setLocation(pt.x - (shell.getBounds.width / 2), pt.y - (shell.getBounds.height / 2))                
        }
        
        private def makeButtonGridData = {
            val gridData = new GridData(GridData.HORIZONTAL_ALIGN_END)
            gridData.widthHint = 70
            gridData
        }
        
        private def isValidNumber(res: Int) =
            res >= low && res <= up
            
        private def errorMessage(input: Int) {
            Dialog.errorMessage(parent, s"Invalid Number: $input")
        }
        
        def open: Option[Int] = {
            val shell = createShell
            val label = createLabel(shell)
            val spinner = createSpinner(shell)
            
            val gridData = makeButtonGridData
            val okButton = createButton(shell, "OK", gridData)
            val cancelButton = createButton(shell, "Cancel", gridData)
            
            spinner.addKeyListener(makeKeyAdapter(okButton, cancelButton))
            
            var res: Option[Int] = None
            okButton.addListener(SWT.Selection, (e: Event) => {
                val input = spinner.getSelection
                
                if (!isValidNumber(input))
                    errorMessage(input)
                else {
                    res = Some(input)
                    shell.dispose
                }
            })
            
            cancelButton.addListener(SWT.Selection, (e: Event) => shell.dispose)
            shell.addListener(SWT.Traverse, (e: Event) => if (e.detail == SWT.TRAVERSE_ESCAPE) e.doit = false)

            shell.pack
            setLocation(shell)    
            shell.open
            
            messageLoop(shell)
            res
        }
    }
    
    class TextInputDialog(parent: Shell, text: String, title: String = "Set Text") extends Dialog(parent) {
        private def createText(shell: Shell) = {
            val text = new Text(shell, SWT.SINGLE | SWT.BORDER)
            text
        }
        
        private def createShell = {
            val shell = new Shell(getParent, SWT.TITLE | SWT.BORDER | SWT.APPLICATION_MODAL | SWT.ON_TOP)
            shell.setText(title)
            shell.setLayout(new GridLayout(2, false))
            shell
        }
        
        private def createLabel(shell: Shell) = {
            val label = new Label(shell, SWT.NULL)
            label.setText(s"$text: ")
            label
        }
        
        private def createButton(shell: Shell, text: String, gridData: GridData) = {
            val button = new Button(shell, SWT.PUSH)
            button.setText(text)
            button.setLayoutData(gridData)
            button
        }
        
        private def makeKeyAdapter(ok: Button, cancel: Button) = {
            new KeyAdapter {
                override def keyReleased(e: KeyEvent) {
                    if (e.character == SWT.CR)
                        ok.notifyListeners(SWT.Selection, null)
                    else if (e.keyCode == SWT.ESC)
                        cancel.notifyListeners(SWT.Selection, null)
                }
            }
        }
        
        private def messageLoop(shell: Shell) = {
            val display = getParent.getDisplay
            while (!shell.isDisposed) {
                if (!display.readAndDispatch)
                    display.sleep
            }            
        }
        
        private def setLocation(shell: Shell) {
            val pt = Display.getDefault.getCursorLocation
            shell.setLocation(pt.x - (shell.getBounds.width / 2), pt.y - (shell.getBounds.height / 2))                
        }
        
        private def makeButtonGridData = {
            val gridData = new GridData(GridData.HORIZONTAL_ALIGN_END)
            gridData.widthHint = 70
            gridData
        }
        
        def open: Option[String] = {
            val shell = createShell
            val label = createLabel(shell)
            val text = createText(shell)
            
            val gridData = makeButtonGridData
            val okButton = createButton(shell, "OK", gridData)
            val cancelButton = createButton(shell, "Cancel", gridData)
            
            text.addKeyListener(makeKeyAdapter(okButton, cancelButton))
            
            var res: Option[String] = None
            okButton.addListener(SWT.Selection, (e: Event) => {
                val input = text.getText
                if (input != "") {
                    res = Some(input)
                    shell.dispose
                }
            })
            
            cancelButton.addListener(SWT.Selection, (e: Event) => shell.dispose)
            shell.addListener(SWT.Traverse, (e: Event) => if (e.detail == SWT.TRAVERSE_ESCAPE) e.doit = false)

            shell.pack
            setLocation(shell)    
            shell.open
            
            messageLoop(shell)
            res
        }
    }
}