package com.example.radardetector.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.radardetector.db.DatabaseHelper
import com.example.radardetector.network.OverpassSyncManager
import com.example.radardetector.util.ServiceUtils

/**
 * Reusable dialog for country speed camera database downloads.
 */
object CountrySelectionDialog {

    private data class CountryItem(val name: String, val code: String)

    fun show(activity: Activity, dbHelper: DatabaseHelper) {
        val syncManager = OverpassSyncManager(activity.applicationContext, dbHelper)

        val dialog = Dialog(activity)
        dialog.setTitle("Select Country")
        dialog.setOnDismissListener {
            syncManager.shutdown()
        }

        val container = UiUtils.createDarkDialogContainer(activity)

        val searchInput = EditText(activity).apply {
            hint = "Search country..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(16, 16, 16, 16)
        }

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val loadingLabel = TextView(activity).apply {
            text = "Loading countries..."
            setTextColor(Color.GRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        listContainer.addView(loadingLabel)

        var activeCountriesList = emptyList<CountryItem>()

        fun populateList(query: String) {
            listContainer.removeAllViews()
            val filtered = activeCountriesList.filter {
                it.name.contains(query, ignoreCase = true) || it.code.contains(query, ignoreCase = true)
            }
            val itemParams = UiUtils.createStandardItemParams()
            for (item in filtered) {
                val btn = UiUtils.createStyledButton(
                    activity,
                    "${item.name} (${item.code})",
                    itemParams
                ) {
                    dialog.dismiss()
                    ServiceUtils.startRadarServiceForCountrySync(activity, item.code, item.name)
                }
                listContainer.addView(btn)
            }
        }

        syncManager.fetchOrGetCachedCountries { fetched ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (fetched.isNotEmpty()) {
                    activeCountriesList = fetched.map { CountryItem(it.first, it.second) }
                    populateList(searchInput.text?.toString() ?: "")
                } else {
                    listContainer.removeAllViews()
                    val errorLabel = TextView(activity).apply {
                        text = "No countries available. Check internet."
                        setTextColor(Color.RED)
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(0, 32, 0, 32)
                    }
                    listContainer.addView(errorLabel)
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        container.addView(searchInput)
        scrollView.addView(listContainer)
        container.addView(scrollView)

        dialog.setContentView(container)
        dialog.show()
    }
}
