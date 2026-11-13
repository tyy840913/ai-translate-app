package com.tyy.aitranslate

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter
    private lateinit var emptyView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_clear_all).setOnClickListener { confirmClearAll() }

        emptyView = findViewById(R.id.empty_view)
        adapter = HistoryAdapter(
            onOpen = { open(it) },
            onLongPress = { confirmDelete(it) }
        )
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val history = Prefs.history(this)
        adapter.submit(history)
        emptyView.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(entry: HistoryEntry) {
        // 超长译文走 Intent extras 会触发 Binder 大小限制（TransactionTooLargeException），
        // 与全屏页一致改用进程内单例传递，Intent 只回传结果码。
        HistoryPick.entry = entry
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun confirmDelete(entry: HistoryEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_history)
            .setMessage(R.string.delete_history_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                Prefs.deleteHistory(this, entry.id)
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        if (Prefs.history(this).isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_history)
            .setMessage(R.string.clear_history_message)
            .setPositiveButton(R.string.clear) { _, _ ->
                Prefs.clearHistory(this)
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/** 历史恢复的进程内传递通道，用完即清。 */
object HistoryPick {
    var entry: HistoryEntry? = null
}
