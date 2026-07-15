package org.example.atvretranslation.auth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** A regular WebView plus an independent D-pad cursor overlay for Android TV. */
class TvCursorWebView(context: Context) : FrameLayout(context) {
    val webView = WebView(context)
    private val cursor = CursorOverlay(context)
    private val density = resources.displayMetrics.density
    private val cursorMargin = 20f * density
    private val cursorStep = 32f * density
    private val scrollStep = (120f * density).roundToInt()
    private var pointerDownTime = 0L
    private var pointerPressed = false

    init {
        addView(
            webView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        addView(
            cursor,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                cursor.moveTo(event.x, event.y)
            }
            false
        }
    }

    fun requestWebViewFocus() {
        webView.requestFocus()
        cursor.visibility = View.VISIBLE
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        cursor.setViewport(width, height, cursorMargin)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> handleDirection(event, -cursorStep, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> handleDirection(event, cursorStep, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> handleDirection(event, 0f, -cursorStep)
            KeyEvent.KEYCODE_DPAD_DOWN -> handleDirection(event, 0f, cursorStep)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> handlePage(event, -1)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> handlePage(event, 1)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> handleClick(event)
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    private fun handleDirection(event: KeyEvent, deltaX: Float, deltaY: Float): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val nextY = cursor.cursorY + deltaY
            when {
                nextY > height - cursorMargin && webView.canScrollVertically(1) ->
                    webView.scrollBy(0, scrollStep)
                nextY < cursorMargin && webView.canScrollVertically(-1) ->
                    webView.scrollBy(0, -scrollStep)
                else -> cursor.moveBy(deltaX, deltaY)
            }
        }
        return true
    }

    private fun handlePage(event: KeyEvent, direction: Int): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            webView.scrollBy(0, direction * (height * 0.75f).roundToInt())
        }
        return true
    }

    private fun handleClick(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (!pointerPressed) {
                pointerPressed = true
                pointerDownTime = SystemClock.uptimeMillis()
                cursor.setCursorPressed(true)
                dispatchPointer(MotionEvent.ACTION_DOWN, pointerDownTime)
            }
            KeyEvent.ACTION_UP -> if (pointerPressed) {
                dispatchPointer(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
                pointerPressed = false
                cursor.setCursorPressed(false)
            }
        }
        return true
    }

    private fun dispatchPointer(action: Int, eventTime: Long) {
        val event = MotionEvent.obtain(
            pointerDownTime.takeIf { it > 0L } ?: eventTime,
            eventTime,
            action,
            cursor.cursorX,
            cursor.cursorY,
            0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            webView.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}

private class CursorOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val cursorRadius = 13f * density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(139, 92, 246) }
    var cursorX = 0f
        private set
    var cursorY = 0f
        private set
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var margin = 0f
    private var pressed = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setViewport(width: Int, height: Int, cursorMargin: Float) {
        viewportWidth = width
        viewportHeight = height
        margin = cursorMargin
        if (cursorX == 0f && cursorY == 0f) {
            cursorX = width * 0.5f
            cursorY = height * 0.45f
        }
        clamp()
        invalidate()
    }

    fun moveBy(deltaX: Float, deltaY: Float) = moveTo(cursorX + deltaX, cursorY + deltaY)

    fun moveTo(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        clamp()
        invalidate()
    }

    fun setCursorPressed(value: Boolean) {
        pressed = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(cursorX, cursorY, cursorRadius + 4f * density, outline)
        canvas.drawCircle(cursorX, cursorY, cursorRadius, fill)
        canvas.drawCircle(cursorX, cursorY, (if (pressed) 7f else 4f) * density, center)
    }

    private fun clamp() {
        cursorX = cursorX.coerceIn(margin, (viewportWidth - margin).coerceAtLeast(margin))
        cursorY = cursorY.coerceIn(margin, (viewportHeight - margin).coerceAtLeast(margin))
    }
}
