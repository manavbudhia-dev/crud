package com.papervault.scanner.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import com.papervault.scanner.AppContainer
import com.papervault.scanner.PaperVaultApp

val CreationExtras.container: AppContainer
    get() = (this[APPLICATION_KEY] as PaperVaultApp).container

fun Context.findActivity(): Activity {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    error("No activity in context chain")
}

typealias Factory = ViewModelProvider.Factory
