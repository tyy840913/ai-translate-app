package com.tyy.aitranslate

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class HistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter
    private lateinit var emptyView: View
    private lateinit var searchInput: EditText
    private var allHistory: List<HistoryEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_clear_all).setOnClickListener { confirmClearAll() }

        emptyView = findViewById(R.id.empty_view)
        searchInput = findViewById(R.id.search_input)
        adapter = HistoryAdapter(
            onOpen = { open(it) },
            onLongPress = { confirmDelete(it) }
        )
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }
        setupSwipeDelete()
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        allHistory = Prefs.history(this)
        // 刷新时保留当前搜索词，避免滑动删除后过滤状态丢失
        applyFilter(searchInput.text.toString())
    }

    private fun applyFilter(query: String) {
        val q = query.trim()
        val shown = if (q.isEmpty()) {
            allHistory
        } else {
            // 原文和译文都参与匹配，忽略大小写
            allHistory.filter {
                it.input.contains(q, ignoreCase = true) ||
                    it.output.contains(q, ignoreCase = true)
            }
        }
        adapter.submit(shown)
        emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupSwipeDelete() {
        val callback = object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int = makeMovementFlags(0, ItemTouchHelper.START or ItemTouchHelper.END)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                // 过滤状态下 position 对应的是当前显示列表，按条目 id 删，不会错删
                val entry = adapter.itemAt(position) ?: return
                Prefs.deleteHistory(this@HistoryActivity, entry.id)
                reload()
                Snackbar.make(
                    findViewById(R.id.recycler),
                    getString(R.string.history_deleted),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.undo) {
                    // 撤销把整条加回去。addHistory 会生成新 id/时间戳并放到列表头，
                    // 顺序与删除前略有出入，但内容完整。
                    Prefs.addHistory(
                        this@HistoryActivity,
                        entry.source,
                        entry.target,
                        entry.input,
                        entry.output,
                        entry.image
                    )
                    reload()
                }.show()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(findViewById(R.id.recycler))
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
