package com.xmmtr.navlite

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.amap.api.services.core.AMapException

// 搜索选点对话框（主页面长按快捷键、设置页设家/公司/自定义位置共用）。
object PoiPicker {

    private const val DEBOUNCE_MS = 300L

    fun display(item: TipItem): String {
        val name = item.name.trim()
        val address = item.address.trim()
        return if (address.isEmpty()) name else "$name  $address"
    }

    fun toPoi(item: TipItem): SavedPoi =
        SavedPoi(item.name.trim(), item.address.trim(), item.latitude, item.longitude)

    fun show(activity: Activity, title: String, onPicked: (SavedPoi) -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_poi_search, null, false)
        view.findViewById<TextView>(R.id.dialog_title).text = title
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, ArrayList<String>())
        val list = view.findViewById<ListView>(R.id.dialog_tips)
        list.adapter = adapter
        val searcher = TipSearcher(activity)
        var items: List<TipItem> = emptyList()

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton(R.string.about_close, null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { onPicked(toPoi(it)) }
            dialog.dismiss()
        }
        view.findViewById<EditText>(R.id.dialog_keyword).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                val keyword = text?.toString()?.trim().orEmpty()
                searcher.request(keyword, DEBOUNCE_MS) { result, code ->
                    items = result
                    adapter.clear()
                    adapter.addAll(result.map { display(it) })
                    if (result.isEmpty() && code != AMapException.CODE_AMAP_SUCCESS) {
                        val message = if (AmapKeyState.hasKey) {
                            activity.getString(R.string.tip_search_failed, code)
                        } else {
                            activity.getString(R.string.tip_no_key)
                        }
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
        dialog.setOnDismissListener { searcher.cancel() }
        dialog.show()
    }

    // 先起名字（≤10 字）再选点：自定义位置的新增与改名入口。
    fun askName(activity: Activity, title: String, initial: String, onOk: (String) -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_place_name, null, false)
        view.findViewById<TextView>(R.id.name_dialog_title).text = title
        val input = view.findViewById<EditText>(R.id.name_input)
        input.setText(initial)
        input.setSelection(initial.length)
        AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> Toast.makeText(
                        activity,
                        R.string.settings_place_name_empty,
                        Toast.LENGTH_SHORT
                    ).show()

                    name.length > LocalStore.PLACE_NAME_MAX -> Toast.makeText(
                        activity,
                        R.string.settings_place_name_long,
                        Toast.LENGTH_SHORT
                    ).show()

                    else -> onOk(name)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
