package com.example.sampleandroid

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.TextView

/**
 * A collection widget the classic way: a `ListView` filled by [TaskListWidgetService]'s
 * `RemoteViewsFactory`, rows that toggle through a pending-intent template and per-row fill-in
 * intents, and a configure activity ([TaskListConfigureActivity]) that picks the list when the
 * widget is added. A toggle redraws the list with `notifyAppWidgetViewDataChanged` and the summary
 * with `partiallyUpdateAppWidget`.
 */
class TaskListWidgetReceiver : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
    for (id in appWidgetIds) manager.updateAppWidget(id, taskListRemoteViews(context, id))
  }

  override fun onReceive(context: Context, intent: Intent) {
    super.onReceive(context, intent)
    if (intent.action != ACTION_TOGGLE) return
    val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
    val task = intent.getIntExtra(EXTRA_TASK, -1)
    if (id < 0 || task < 0) return
    TaskStore(context, id).toggle(task)
    val manager = AppWidgetManager.getInstance(context)
    @Suppress("DEPRECATION") manager.notifyAppWidgetViewDataChanged(id, R.id.task_list)
    manager.partiallyUpdateAppWidget(
      id,
      RemoteViews(context.packageName, R.layout.widget_task_list).apply {
        setTextViewText(R.id.task_summary, TaskStore(context, id).summary())
      },
    )
  }

  companion object {
    const val ACTION_TOGGLE = "com.example.sampleandroid.TOGGLE_TASK"
    const val EXTRA_TASK = "task"
  }
}

/** The widget's views: the list's title and summary, its rows from the service, and the clicks. */
fun taskListRemoteViews(context: Context, id: Int): RemoteViews {
  val store = TaskStore(context, id)
  return RemoteViews(context.packageName, R.layout.widget_task_list).apply {
    setTextViewText(R.id.task_title, store.listName)
    setTextViewText(R.id.task_summary, store.summary())
    @Suppress("DEPRECATION")
    setRemoteAdapter(
      R.id.task_list,
      Intent(context, TaskListWidgetService::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        // One factory per widget: the service keys factories by the intent's filter.
        .setData(Uri.parse("tasks://widget/$id")),
    )
    setEmptyView(R.id.task_list, R.id.task_empty)
    setPendingIntentTemplate(
      R.id.task_list,
      PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, TaskListWidgetReceiver::class.java)
          .setAction(TaskListWidgetReceiver.ACTION_TOGGLE)
          .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
      ),
    )
    setOnClickPendingIntent(
      R.id.task_title,
      PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
      ),
    )
  }
}

/** Each widget's list and which of its tasks are done, in shared preferences. */
private class TaskStore(context: Context, private val id: Int) {
  private val prefs = context.getSharedPreferences("task_list_widget", Context.MODE_PRIVATE)

  val listName: String
    get() = prefs.getString("list_$id", null) ?: "Errands"

  val tasks: List<String>
    get() = (1..12).map { "$listName task $it" }

  fun isDone(task: Int): Boolean = prefs.getBoolean("done_${id}_$task", false)

  fun toggle(task: Int) {
    prefs.edit().putBoolean("done_${id}_$task", !isDone(task)).commit()
  }

  fun choose(name: String) {
    prefs.edit().putString("list_$id", name).commit()
  }

  fun summary(): String = "${tasks.indices.count { isDone(it) }} of ${tasks.size} done"
}

class TaskListWidgetService : RemoteViewsService() {
  override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
    TaskListFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
}

private class TaskListFactory(private val context: Context, private val id: Int) :
  RemoteViewsService.RemoteViewsFactory {
  private var tasks: List<String> = emptyList()
  private val store = TaskStore(context, id)

  override fun onCreate() {}

  override fun onDataSetChanged() {
    tasks = store.tasks
  }

  override fun onDestroy() {}

  override fun getCount(): Int = tasks.size

  override fun getViewAt(position: Int): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_task_item).apply {
      val done = store.isDone(position)
      setTextViewText(R.id.task_item, (if (done) "✓  " else "○  ") + tasks[position])
      setTextColor(R.id.task_item, if (done) 0x99FFFFFF.toInt() else 0xFFFFFFFF.toInt())
      setOnClickFillInIntent(
        R.id.task_item,
        Intent().putExtra(TaskListWidgetReceiver.EXTRA_TASK, position),
      )
    }

  override fun getLoadingView(): RemoteViews? = null

  override fun getViewTypeCount(): Int = 1

  override fun getItemId(position: Int): Long = position.toLong()

  override fun hasStableIds(): Boolean = true
}

/**
 * The task widget's configure activity: picks which list the new widget shows, draws it, and
 * returns `RESULT_OK`; Back leaves `RESULT_CANCELED`, and the launcher removes the widget.
 */
class TaskListConfigureActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setResult(RESULT_CANCELED)
    val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
    if (id < 0) {
      finish()
      return
    }
    val density = resources.displayMetrics.density
    val layout =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = (24 * density).toInt()
        setPadding(pad, pad, pad, pad)
      }
    layout.addView(
      TextView(this).apply {
        text = "Which list should the widget show?"
        textSize = 18f
      }
    )
    for (name in listOf("Work", "Home", "Errands")) {
      layout.addView(
        Button(this).apply {
          text = name
          setOnClickListener {
            TaskStore(this@TaskListConfigureActivity, id).choose(name)
            AppWidgetManager.getInstance(this@TaskListConfigureActivity)
              .updateAppWidget(id, taskListRemoteViews(this@TaskListConfigureActivity, id))
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
          }
        }
      )
    }
    setContentView(layout)
  }
}
