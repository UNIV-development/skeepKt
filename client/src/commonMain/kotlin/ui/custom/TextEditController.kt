package ui.custom

import korlibs.datastructure.HistoryStack
import korlibs.event.ISoftKeyboardConfig
import korlibs.event.Key
import korlibs.event.KeyEvent
import korlibs.event.SoftKeyboardConfig
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.font.Font
import korlibs.image.text.HorizontalAlign
import korlibs.io.async.Signal
import korlibs.io.lang.*
import korlibs.io.util.length
import korlibs.korge.component.onNewAttachDetach
import korlibs.korge.input.cursor
import korlibs.korge.input.doubleClick
import korlibs.korge.input.newKeys
import korlibs.korge.input.newMouse
import korlibs.korge.style.styles
import korlibs.korge.style.textAlignment
import korlibs.korge.time.timers
import korlibs.korge.ui.*
import korlibs.korge.ui.UIText
import korlibs.korge.view.*
import korlibs.korge.view.debug.debugVertexView
import korlibs.math.geom.Margin
import korlibs.math.geom.Point
import korlibs.math.geom.PointArrayList
import korlibs.math.geom.bezier.Bezier
import korlibs.memory.Platform
import korlibs.memory.clamp
import korlibs.render.GameWindow
import korlibs.render.TextClipboardData
import korlibs.time.seconds
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class TextEditController(
    val textView: Text,
    val caretContainer: Container = textView,
    val eventHandler: View = textView,
    val hint: UIText? = null,
    val bg: RenderableView? = null,
) : Closeable, UIFocusable, ISoftKeyboardConfig by SoftKeyboardConfig() {
    init {
        textView.focusable = this
    }

    val stage: Stage? get() = textView.stage
    var initialText: String = textView.text
    private val closeables = CancellableGroup()
    override val UIFocusManager.Scope.focusView: View get() = this@TextEditController.textView
    val onEscPressed = Signal<TextEditController>()
    val onReturnPressed = Signal<TextEditController>()
    val onTextUpdated = Signal<TextEditController>()
    val onFocused = Signal<TextEditController>()
    val onFocusLost = Signal<TextEditController>()
    val onOver = Signal<TextEditController>()
    val onOut = Signal<TextEditController>()
    val onSizeChanged = Signal<TextEditController>()

    //private val bg = ninePatch(NinePatchBmpSlice.createSimple(Bitmap32(3, 3) { x, y -> if (x == 1 && y == 1) Colors.WHITE else Colors.BLACK }.slice(), 1, 1, 2, 2), width, height).also { it.smoothing = false }
    /*
    private val bg = renderableView(width, height, skin)
    var skin by bg::viewRenderer
    private val container = clipContainer(0.0, 0.0)
    //private val container = fixedSizeContainer(width - 4.0, height - 4.0).position(2.0, 3.0)
    private val textView = container.text(initialText, 16.0, color = Colors.BLACK)
     */

    private val caret = caretContainer.debugVertexView().also {
        it.blendMode = BlendMode.INVERT
        it.visible = false
    }

    var padding: Margin = Margin(3f, 2f, 2f, 2f)
        set(value) {
            field = value
            onSizeChanged(this)
        }

    init {
        closeables += textView.onNewAttachDetach(onAttach = {
            this.stage.uiFocusManager
        })
        onSizeChanged(this)
    }

    //override fun onSizeChanged() {
    //    bg.setSize(width, height)
    //    container.bounds(Rectangle(0.0, 0.0, width, height).without(padding))
    //}

    data class TextSnapshot(var text: String, var selectionRange: IntRange) {
        fun apply(out: TextEditController) {
            out.setTextNoSnapshot(text)
            out.select(selectionRange)
        }
    }

    private val textSnapshots = HistoryStack<TextSnapshot>()

    private fun setTextNoSnapshot(text: String, out: TextSnapshot = TextSnapshot("", 0..0)): TextSnapshot? {
        if (!acceptTextChange(textView.text, text)) return null
        out.text = textView.text
        out.selectionRange = selectionRange
        textView.text = text
        reclampSelection()
        onTextUpdated(this)
        return out
    }

    var text: String
        get() = textView.text
        set(value) {
            hint?.visible = value == " " && hasSpace || value == ""
            val snapshot = setTextNoSnapshot(value)
            if (snapshot != null) {
                textSnapshots.push(snapshot)
            }
        }

    fun undo() {
        textSnapshots.undo()?.apply(this)
    }

    fun redo() {
        textSnapshots.redo()?.apply(this)
    }

    fun insertText(substr: String) {
        var index = min(selectionStart, selectionEnd)
        val rangedText = text.withoutRange(selectionRange)
        if (text.isNotEmpty()) {
            val lastStr = text[max(0, index - 1)]
            val insertion = khangul.HangulProcessor.composeHangul(lastStr + substr)
            if (insertion !== null) {
                val tempText = rangedText
                    .withoutIndex(max(0, index - 1))
                    .withInsertion(index, insertion)
                text = tempText.makeStartWithSpace(hasSpace)
                if (tempText.startsWith(" ") && hasSpace) {
                    cursorIndex += insertion.length - 1
                } else {
                    cursorIndex += insertion.length
                }
                return
            }
        }
        text = rangedText.withInsertion(index, substr).makeStartWithSpace(hasSpace)
        cursorIndex += substr.length
    }

    var font: Font
        get() = textView.font as Font
        set(value) {
            textView.font = value
            updateCaretPosition()
        }

    var textSize: Float
        get() = textView.textSize
        set(value) {
            textView.textSize = value
            updateCaretPosition()
        }
    var textColor: RGBA by textView::color

    private var _selectionStart: Int = initialText.length
    private var _selectionEnd: Int = _selectionStart

    private fun clampIndex(index: Int) = index.clamp(0, text.length)

    private fun reclampSelection() {
        select(selectionStart, selectionEnd)
        selectionStart = selectionStart
    }

    var selectionStart: Int
        get() = _selectionStart
        set(value) {
            _selectionStart = clampIndex(value)
            updateCaretPosition()
        }

    var selectionEnd: Int
        get() = _selectionEnd
        set(value) {
            _selectionEnd = clampIndex(value)
            updateCaretPosition()
        }

    var cursorIndex: Int
        get() = selectionStart
        set(value) {
            val value = clampIndex(value)
            _selectionStart = value
            _selectionEnd = value
            updateCaretPosition()
        }

    fun select(start: Int, end: Int) {
        _selectionStart = clampIndex(start)
        _selectionEnd = clampIndex(end)
        updateCaretPosition()
    }

    fun select(range: IntRange) {
        select(range.first, range.last + 1)
    }

    fun selectAll() {
        select(0, text.length)
    }

    val selectionLength: Int get() = (selectionEnd - selectionStart).absoluteValue
    val selectionText: String get() = text.substring(min(selectionStart, selectionEnd), max(selectionStart, selectionEnd))
    var selectionRange: IntRange
        get() = min(selectionStart, selectionEnd) until max(selectionStart, selectionEnd)
        set(value) {
            select(value)
        }

    private val gameWindow get() = textView.stage!!.views.gameWindow

    fun getCaretAtIndex(index: Int): Bezier {
        val glyphPositions = textView.getGlyphMetrics().glyphs
        if (glyphPositions.isEmpty()) return Bezier(Point(), Point())
        val glyph = glyphPositions[min(index, glyphPositions.size - 1)]
        return when {
            index < glyphPositions.size -> glyph.caretStart
            else -> glyph.caretEnd
        }
    }

    /*
    init {
        caretContainer.gpuShapeView {
            for (g in textView.getGlyphMetrics().glyphs) {
                fill(Colors.WHITE) {
                    write(g.boundsPath)
                }
            }
        }
    }
    */

    fun getIndexAtPos(pos: Point): Int {
        val glyphPositions = textView.getGlyphMetrics().glyphs

        var index = 0
        var minDist = Double.POSITIVE_INFINITY

        if (glyphPositions.isNotEmpty()) {
            for (n in 0..glyphPositions.size) {
                val glyph = glyphPositions[min(glyphPositions.size - 1, n)]
                val dist = glyph.distToPath(pos)
                if (minDist > dist) {
                    minDist = dist
                    //println("[$n] dist=$dist")
                    index = when {
                        n >= glyphPositions.size - 1 && dist != 0.0 && glyph.distToPath(pos, startEnd = false) < glyph.distToPath(pos, startEnd = true) -> n + 1
                        else -> n
                    }
                }
            }
        }

        return index
    }

    fun updateCaretPosition() {
        val range = selectionRange
        //val startX = getCaretAtIndex(range.start)
        //val endX = getCaretAtIndex(range.endExclusive)
        val xOffset =
            if (textView.styles.textAlignment.horizontal.equals(HorizontalAlign.CENTER)) textView.width/2f else 0f
        val xOffsetPoint = Point(xOffset, 0)
        val array = PointArrayList(if (range.isEmpty()) 2 else (range.length + 1) * 2)
        if (range.isEmpty()) {
            val last = (range.first >= this.text.length)
            val caret = getCaretAtIndex(range.first)
            val sign = if (last) -1.0 else +1.0
            val normal = caret.normal(0f) * (2.0 * sign)
            val p0 = caret.points.first
            val p1 = caret.points.last
            array.add(p0 + xOffsetPoint)
            array.add(p1 + xOffsetPoint)
            array.add(p0 + normal + xOffsetPoint)
            array.add(p1 + normal + xOffsetPoint)
        } else {
            for (n in range.first..range.last + 1) {
                val caret = getCaretAtIndex(n)
                array.add(caret.points.first + xOffsetPoint)
                array.add(caret.points.last + xOffsetPoint)
                //println("caret[$n]=$caret")
            }
        }
        caret.color = Colors.WHITE
        caret.pointsList = listOf(array)
        /*
        caret.x = startX.x0
        caret.scaledWidth = endX - startX + (if (range.isEmpty()) 2.0 else 0.0)
        */
        caret.visible = focused
        textView.invalidateRender()
    }

    fun moveToIndex(selection: Boolean, index: Int) {
        if (selection) selectionStart = index else cursorIndex = index
    }

    fun nextIndex(index: Int, direction: Int, word: Boolean): Int {
        val dir = direction.sign
        if (word) {
            val sidx = index + dir
            var idx = sidx
            while (true) {
                if (idx !in text.indices) {
                    return when {
                        dir < 0 -> idx - dir
                        else -> idx
                    }
                }
                if (!text[idx].isLetterOrDigit()) {
                    return when {
                        dir < 0 -> if (idx == sidx) idx else idx - dir
                        else -> idx
                    }
                }
                idx += dir
            }
        }
        return index + dir
    }

    fun leftIndex(index: Int, word: Boolean): Int = nextIndex(index, -1, word)
    fun rightIndex(index: Int, word: Boolean): Int = nextIndex(index, +1, word)

    override var tabIndex: Int = 0
    override val isFocusable: Boolean get() = true
    var acceptTextChange: (old: String, new: String) -> Boolean = { old, new -> true }

    override fun focusChanged(value: Boolean) {
        bg?.isFocused = value

        if (value) {
            caret.visible = true
            //println("stage?.gameWindow?.showSoftKeyboard(): ${stage?.gameWindow}")
            stage?.uiFocusManager?.requestToggleSoftKeyboard(true, this)
        } else {
            caret.visible = false
            if (stage?.uiFocusedView !is ISoftKeyboardConfig) {
                stage?.uiFocusManager?.requestToggleSoftKeyboard(false, null)
            }
        }

        if (value) {
            onFocused(this)
        } else {
            onFocusLost(this)
        }
    }

    //override var focused: Boolean
    //    set(value) {
    //        if (value == focused) return
//
    //        bg?.isFocused = value
//
    //
    //    }
    //    get() = stage?.uiFocusedView == this

    init {
        //println(metrics)

        this.eventHandler.cursor = GameWindow.Cursor.TEXT

        closeables += this.eventHandler.timers.interval(0.5.seconds) {
            if (!focused) {
                caret.visible = false
            } else {
                if (selectionLength == 0 && hasSpace) {
                    caret.visible = !caret.visible
                } else {
                    caret.visible = true
                }
            }
        }

        closeables += this.eventHandler.newKeys {
            typed {
                //println("focused=$focused, focus=${textView.stage?.uiFocusManager?.uiFocusedView}")
                if (!focused) return@typed
                if (it.meta) return@typed
                val code = it.character.code
                when (code) {
                    8, 127 -> Unit // backspace, backspace (handled by down event)
                    9, 10, 13 -> { // tab & return: Do nothing in single line text inputs
                        if (code == 10 || code == 13) {
                            onReturnPressed(this@TextEditController)
                        }
                    }
                    27 -> {
                        onEscPressed(this@TextEditController)
                    }
                    else -> {
                        insertText(it.characters())
                    }
                }
                //println(it.character.toInt())
                //println("NEW TEXT[${it.character.code}]: '${text}'")
            }
            down {
                if (!focused) return@down
                when (it.key) {
                    Key.C, Key.V, Key.Z, Key.A, Key.X -> {
                        if (it.isNativeCtrl()) {
                            when (it.key) {
                                Key.Z -> {
                                    if (it.shift) redo() else undo()
                                }
                                Key.C, Key.X -> {
                                    if (selectionText.isNotEmpty()) {
                                        gameWindow.clipboardWrite(TextClipboardData(selectionText))
                                    }
                                    if (it.key == Key.X) {
                                        val selection = selectionRange
                                        text = text.withoutRange(selectionRange).makeStartWithSpace(hasSpace)
                                        moveToIndex(false, selection.first)
                                    }
                                }
                                Key.V -> {
                                    val rtext = (gameWindow.clipboardRead() as? TextClipboardData?)?.text
                                    if (rtext != null) insertText(rtext)
                                }
                                Key.A -> {
                                    selectAll()
                                }
                                else -> Unit
                            }
                        }
                    }
                    Key.BACKSPACE, Key.DELETE -> {
                        val range = selectionRange
                        if (range.length > 0) {
                            text = text.withoutRange(range).makeStartWithSpace(hasSpace)
                            cursorIndex = range.first
                            if (range.first == 0) cursorIndex += 1
                        } else {
                            if (it.key == Key.BACKSPACE) {
                                if (cursorIndex > 0) {
                                    val oldCursorIndex = cursorIndex
                                    var tempText = text.withoutIndex(cursorIndex - 1)
                                    if (tempText.startsWith(" ") || !hasSpace) {
                                        text = tempText.makeStartWithSpace(hasSpace)
                                        cursorIndex = oldCursorIndex -(if (hasSpace) 0  else 1) // This [oldCursorIndex] is required since changing text might change the cursorIndex already in some circumstances
                                    } else {
                                    }
                                }
                            } else {
                                if (cursorIndex < text.length) {
                                    text = text.withoutIndex(cursorIndex).makeStartWithSpace(hasSpace)
                                }
                            }
                        }
                    }
                    Key.LEFT -> {
                        when {
                            it.isStartFinalSkip() -> moveToIndex(it.shift, 0)
                            else -> moveToIndex(it.shift, leftIndex(selectionStart, it.isWordSkip()))
                        }
                    }
                    Key.RIGHT -> {
                        when {
                            it.isStartFinalSkip() -> moveToIndex(it.shift, text.length)
                            else -> moveToIndex(it.shift, rightIndex(selectionStart, it.isWordSkip()))
                        }
                    }
                    Key.HOME -> moveToIndex(it.shift, 0)
                    Key.END -> moveToIndex(it.shift, text.length)
                    else -> Unit
                }
            }
        }

        closeables += this.eventHandler.newMouse {
        //container.mouse {
            var dragging = false
            bg?.isOver = false
            onOut(this@TextEditController)
            over {
                onOver(this@TextEditController)
                bg?.isOver = true
            }
            out {
                onOut(this@TextEditController)
                bg?.isOver = false
            }
            downImmediate {
                cursorIndex = getIndexAtPos(it.currentPosLocal)
                dragging = false
                focused = true
            }
            down {
                //println("UiTextInput.down")
                cursorIndex = getIndexAtPos(it.currentPosLocal)
                dragging = false
            }
            downOutside {
                //println("UiTextInput.downOutside")
                dragging = false
                if (focused) {
                    focused = false
                    blur()
                }
            }
            moveAnywhere {
                //println("UiTextInput.moveAnywhere: focused=$focused, pressing=${it.pressing}")
                if (focused && it.pressing) {
                    dragging = true
                    selectionEnd = getIndexAtPos(it.currentPosLocal)
                    it.stopPropagation()
                }
            }
            upOutside {
                //println("UiTextInput.upOutside: dragging=$dragging, isFocusedView=$isFocusedView, view=${it.view}, stage?.uiFocusedView=${stage?.uiFocusedView}")
                if (!dragging && focused) {
                    //println(" -- BLUR")
                    blur()
                }
                dragging = false
            }
            doubleClick {
                //println("UiTextInput.doubleClick")
                val index = getIndexAtPos(it.currentPosLocal)
                select(leftIndex(index, true), rightIndex(index, true))
            }
        }

        updateCaretPosition()
    }

    fun KeyEvent.isWordSkip(): Boolean = if (Platform.os.isApple) this.alt else this.ctrl
    fun KeyEvent.isStartFinalSkip(): Boolean = this.meta && Platform.os.isApple
    fun KeyEvent.isNativeCtrl(): Boolean = this.metaOrCtrl

    override fun close() {
        this.textView.cursor = null
        closeables.cancel()
        textView.focusable = null
    }
}

fun Text.editText(caretContainer: Container = this): TextEditController =
    TextEditController(this, caretContainer)

val TextEditController.hasSpace get() = textView.isLeftTextAlign
val View.isLeftTextAlign get() = styles.textAlignment.horizontal == HorizontalAlign.LEFT

fun String.makeStartWithSpace(hasSpace: Boolean = true): String = if (startsWith(" ")) this else (if (hasSpace) " $this" else this)