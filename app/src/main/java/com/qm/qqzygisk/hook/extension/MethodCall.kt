package com.qm.qqzygisk.hook.extension

class MethodCall(
    private val initialArguments: Array<Any?>,
    private val isStatic: Boolean,
) {
    var args: Array<Any?> =
        if (isStatic) {
            initialArguments
        } else if (initialArguments.size > 1) {
            initialArguments.sliceArray(1 until initialArguments.size)
        } else {
            emptyArray()
        }

    val instance get() = if (isStatic) error("该方法为静态成员，没有instance") else this.initialArguments[0]!!
    var result: Any? = null
        set(v) {
            skipOriginalMethodCall = true
            field = v
        }

    private var skipOriginalMethodCall = false

    val shouldSkipOriginalMethodCall get() = skipOriginalMethodCall
}