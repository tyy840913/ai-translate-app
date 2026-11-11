package com.tyy.aitranslate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INPUT = "input"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TARGET = "target"
    }

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
        val data = Intent().apply {
            putExtra(EXTRA_INPUT, entry.input)
            putExtra(EXTRA_OUTPUT, entry.output)
            putExtra(EXTRA_SOURCE, entry.source)
            putExtra(EXTRA_TARGET, entry.target)
        }
        setResult(Activity.RESULT_OK, data)
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
