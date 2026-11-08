package com.andrewf13.tuned.remote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-screen widget: your favourite channels as buttons. Tap one and the TV changes channel,
 * without opening the app. Plain RemoteViews (no extra libraries) to keep it light.
 */
class ChannelWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context, manager.getAppWidgetOptions(it))) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        manager.updateAppWidget(id, build(context, options))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_PLAY && action != ACTION_REFRESH) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val msg = if (action == ACTION_PLAY) WidgetActions.play(context, intent.getIntExtra(EXTRA_ID, -1))
                else WidgetActions.refresh(context)
                msg?.let { m -> CoroutineScope(Dispatchers.Main).launch { Toast.makeText(context, m, Toast.LENGTH_SHORT).show() } }
                updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PLAY = "com.andrewf13.tuned.remote.PLAY"
        const val ACTION_REFRESH = "com.andrewf13.tuned.remote.REFRESH"
        const val EXTRA_ID = "id"

        fun updateAll(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { m.getAppWidgetIds(ComponentName(context, ChannelWidget::class.java)) }.getOrNull() ?: return
            ids.forEach { m.updateAppWidget(it, build(context, m.getAppWidgetOptions(it))) }
        }

        private fun broadcast(context: Context, action: String, id: Int = 0): PendingIntent =
            PendingIntent.getBroadcast(
                context, if (action == ACTION_PLAY) id else -1,
                Intent(context, ChannelWidget::class.java).setAction(action).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Rows that fit the widget's current height (each row ≈ 40 dp, header ≈ 44 dp). */
        private fun rowsFor(options: Bundle?): Int {
            val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 } ?: 180
            return ((h - 44) / 40).coerceIn(1, 10)
        }

        fun build(context: Context, options: Bundle?): RemoteViews {
            val store = RemoteStore(context)
            val v = RemoteViews(context.packageName, R.layout.widget)
            val openApp = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_title, openApp)
            v.setOnClickPendingIntent(R.id.widget_refresh, broadcast(context, ACTION_REFRESH))
            v.removeAllViews(R.id.widget_items)

            val tv = store.tv
            val channels = store.widgetChannels
            when {
                tv == null -> {
                    v.setTextViewText(R.id.widget_title, "Tuned Remote")
                    v.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                    v.setTextViewText(R.id.widget_empty, "Tap to pair with your TV")
                    v.setOnClickPendingIntent(R.id.widget_empty, openApp)
                }
                channels.isEmpty() -> {
                    v.setTextViewText(R.id.widget_title, store.nowPlaying?.let { "▶ $it" } ?: tv.name)
                    v.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                    v.setTextViewText(R.id.widget_empty, "No favourites yet: hold OK on a channel on the TV")
                    v.setOnClickPendingIntent(R.id.widget_empty, openApp)
                }
                else -> {
                    v.setTextViewText(R.id.widget_title, store.nowPlaying?.let { "▶ $it" } ?: tv.name)
                    v.setViewVisibility(R.id.widget_empty, View.GONE)
                    channels.take(rowsFor(options)).forEach { (id, name) ->
                        val row = RemoteViews(context.packageName, R.layout.widget_item)
                        row.setTextViewText(R.id.widget_item_text, name)
                        row.setOnClickPendingIntent(R.id.widget_item_text, broadcast(context, ACTION_PLAY, id))
                        v.addView(R.id.widget_items, row)
                    }
                }
            }
            return v
        }
    }
}

/** What the widget's buttons do. Separate from the receiver so it can be tested. */
object WidgetActions {
    /** Change channel on the TV. Returns a short message for a toast. */
    suspend fun play(context: Context, id: Int): String? {
        val store = RemoteStore(context)
        val tv = store.tv ?: return "Open Tuned Remote to pair with your TV"
        return try {
            val state = TvClient(tv.host, tv.port, tv.token).play(id)
            store.nowPlaying = state.playing?.name
            null // the widget title shows the new channel
        } catch (e: TvException) {
            e.message
        } catch (e: Exception) {
            "Can't reach ${tv.name}. Is Tuned open on the TV?"
        }
    }

    /** Re-read favourites and what's playing from the TV. */
    suspend fun refresh(context: Context): String? {
        val store = RemoteStore(context)
        val tv = store.tv ?: return null
        return try {
            val c = TvClient(tv.host, tv.port, tv.token)
            store.widgetChannels = c.lineup().channels.filter { it.fav }.map { it.id to it.name }
            store.nowPlaying = c.state().playing?.name
            null
        } catch (e: Exception) {
            "Can't reach ${tv.name}"
        }
    }
}
