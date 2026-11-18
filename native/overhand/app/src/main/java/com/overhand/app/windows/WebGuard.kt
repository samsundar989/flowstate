package com.overhand.app.windows

import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Web pages in panels must never open a window: a panel's window context cannot host a Dialog
 * (the Spinner crash, Nov 13). JS alert/confirm/prompt are answered here and shown as an in-panel line;
 * [SCRIPT] stops native pickers (select, date/time/colour inputs) from opening their popup.
 */
object WebGuard {
  /** Injected before page scripts. Pickers open on pointer/mouse down or a key press; cancel those. */
  const val SCRIPT="(function(){var q='select,input[type=date],input[type=time],input[type=datetime-local],input[type=month],input[type=week],input[type=color]';" +
    "function stop(e){var t=e.target;if(t&&t.closest&&t.closest(q)){e.preventDefault();e.stopPropagation();}}" +
    "['pointerdown','mousedown','touchstart','keydown','click'].forEach(function(k){document.addEventListener(k,stop,true);});})();"
  fun client(message: (String) -> Unit)=object: WebChromeClient() {
    override fun onJsAlert(view: WebView,url: String?,text: String?,result: JsResult): Boolean {
      result.confirm();message(text.orEmpty());return true
    }
    override fun onJsConfirm(view: WebView,url: String?,text: String?,result: JsResult): Boolean {
      result.cancel();message(text.orEmpty());return true
    }
    override fun onJsPrompt(view: WebView,url: String?,text: String?,default: String?,result: JsPromptResult): Boolean {
      result.cancel();message(text.orEmpty());return true
    }
    override fun onJsBeforeUnload(view: WebView,url: String?,text: String?,result: JsResult): Boolean {
      result.confirm();return true
    }
  }
}
