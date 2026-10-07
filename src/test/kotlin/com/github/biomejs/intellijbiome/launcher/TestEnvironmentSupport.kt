package com.github.biomejs.intellijbiome.launcher

import com.intellij.util.EnvironmentUtil
import kotlinx.coroutines.CompletableDeferred
import java.util.function.Supplier

/** The test-only platform setter changed from Deferred in 253 to Supplier in 262. */
internal fun setTestEnvironment(environment: Map<String, String>) {
    val setter = EnvironmentUtil::class.java.methods.single {
        it.name == "setEnvironmentLoader" && it.parameterCount == 1
    }
    val argument = if (setter.parameterTypes.single() == Supplier::class.java) {
        Supplier { environment }
    } else {
        CompletableDeferred(environment)
    }
    setter.invoke(null, argument)
}
