package com.example.radardetector.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object UiUtils {

    fun createStandardItemParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 4, 0, 4)
        }
    }

    fun createDarkDialogContainer(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#252525"))
        }
    }

    fun createDialogDivider(context: Context): View {
        return View(context).apply {
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }
    }

    fun createStyledButton(
        context: Context,
        buttonText: String,
        params: LinearLayout.LayoutParams? = null,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            text = buttonText
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            params?.let { layoutParams = it }
            setOnClickListener { onClick() }
        }
    }

    fun createHeaderLayout(activity: Activity, titleText: String): LinearLayout {
        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val btnBack = createStyledButton(activity, "Back") {
            activity.finish()
        }

        val headerTitle = TextView(activity).apply {
            text = titleText
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        headerLayout.addView(btnBack)
        headerLayout.addView(headerTitle)
        return headerLayout
    }

    fun setupTextPinchZoom(
        activity: Activity,
        targetTextView: TextView,
        initialSizeSp: Float = 14f,
        minSp: Float = 8f,
        maxSp: Float = 36f,
        onSizeChanged: ((Float) -> Unit)? = null
    ): ScaleGestureDetector {
        var currentSp = initialSizeSp
        return ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                currentSp = (currentSp * factor).coerceIn(minSp, maxSp)
                targetTextView.textSize = currentSp
                onSizeChanged?.invoke(currentSp)
                return true
            }
        })
    }
}
