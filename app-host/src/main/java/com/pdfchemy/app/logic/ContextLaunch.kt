package com.pdfchemy.app.logic

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.util.Collections
import java.util.IdentityHashMap

/** Context wrappers retain the launch task of their underlying Activity. */
internal fun Context.hasActivityContext(): Boolean {
    var owner = this
    val visited = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
    while (owner is ContextWrapper && owner !is Activity && visited.add(owner)) {
        owner = owner.baseContext
    }
    return owner is Activity
}
