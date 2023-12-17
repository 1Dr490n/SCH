package de.drgn.sch

import de.drgn.irbuilder.FuncSignature
import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.*
import de.drgn.irbuilder.values.VArray
import de.drgn.irbuilder.values.VGlobal
import de.drgn.irbuilder.values.VInt
import de.drgn.irbuilder.values.VString
import java.io.File


val func_inc = FuncSignature(TVoid, TPtr) to VGlobal("inc")
val func_inc_d = FuncSignature(TVoid, TPtr) to VGlobal("inc_d")
val func_dec = FuncSignature(TVoid, TPtr, TPtr) to VGlobal("dec")
val func_printf = FuncSignature(TVoid, TPtr, isVararg = true) to VGlobal("printf")
val func_malloc = FuncSignature(TPtr, tI64) to VGlobal("malloc")
val func_free = FuncSignature(TVoid, TPtr) to VGlobal("free")
val func_String_constructor = FuncSignature(TPtr, tI32, TPtr) to VGlobal("String_constructor")
val func_Array_constructor = FuncSignature(TPtr, tI32, tI64, TPtr) to VGlobal("Array_constructor")
val func_Array_constructor2 = FuncSignature(TPtr, tI32, tI64, TPtr, TPtr) to VGlobal("Array_constructor2")
val func_Array_destructor = FuncSignature(TVoid, TPtr) to VGlobal("Array_destructor")
val func_Array_get = FuncSignature(TPtr, TPtr, tI32, tI32, TPtr) to VGlobal("Array_get")
val func_null_assert = FuncSignature(TVoid, TPtr, TPtr) to VGlobal("null_assert")
val func_is_subclass = FuncSignature(tI1, TPtr, tI32, TPtr) to VGlobal("is_subclass")
val func_is_subclass_assert = FuncSignature(TVoid, /*obj*/TPtr, /*type*/tI32, /*inheritances*/TPtr, /*typenames*/TPtr, /*line*/TPtr) to VGlobal("is_subclass_assert")

val struct_Array = TStruct(tI32, tI32, TPtr, tI1)
val struct_Class = TStruct(tI32, tI32, TPtr)

lateinit var classNames: VArray
lateinit var classInheritances: VArray

fun main(args: Array<String>) {

    var emitLlvm = false
    var o: String? = null
    var s = false

    val files = mutableListOf(File("__TEMP__.sch"))

    File("__TEMP__.sch").also {
        it.deleteOnExit()
    }.writeBytes(object{}.javaClass.classLoader.getResourceAsStream("std.sch").readAllBytes())

    val ite = args.toSet().iterator()
    for (it in ite) {
        when(it) {
            "-emit-llvm" -> emitLlvm = true
            "-o" -> o = ite.next()
            "-S" -> s = true
            else -> files += File(it)
        }
    }
    val lexed = mutableListOf<Triple<DFile, List<Token>, ListIterator<TokenLine>>>()
    files.forEach {
        lexed += lex(it)
    }
    lexed.forEach { (f, _, ite) ->
        parse(f, ite)
    }

    ast.forEach {
        it.tree()?.let { tree += it }
    }
    ast.forEach {
        it.typesDone()
    }
    classNames = VArray(TPtr, DTClass.classes.map { VString(it.toString()) })
    classInheritances = VArray(tI32, DTClass.classes.map { VInt(tI32, it.superClass?.number?.toLong()?:0) })
    ast.forEach {
        it.code()
    }

    tree.forEach {
        it.ir()
    }

    var mainFunc: TreeFuncDefinition? = null
    for (t in tree) {
        if(t is TreeFuncDefinition && t.name.name == "main" && t.name.line.file.pckg.name == "main") {
            mainFunc = t
            break
        }
    }
    if(mainFunc == null)
        illegal("No main function found")

    IRBuilder.func("main", mainFunc.returnType.ir()) {
        blocks += Block()
        tree.forEach {
            if(it is TreeGlobalDeclaration) {
                it.variables.zip(it.objects).forEach { (v, o) ->
                    store(v.ir(this@func), o!!.ir(this@func))
                }
            }
        }
        val v = callFunc(FuncSignature(mainFunc.returnType.ir()), VGlobal(mainFunc.global.irName))
        blocks.forEach {
            it.variables.forEach { v ->
                decrement(this@func, v.ir(this@func), v.type)
            }
        }
        blocks.pop()
        ret(v)
    }
    listOf(
        func_printf,
        func_malloc,
        func_free,

        func_inc,
        func_inc_d,
        func_dec,

        func_String_constructor,
        func_Array_constructor,
        func_Array_constructor2,
        func_Array_destructor,
        func_Array_get,
        func_null_assert,
        func_is_subclass,
        func_is_subclass_assert
    ).forEach {
        IRBuilder.declareFunc(it.second.name, it.first)
    }

    File("__TEMP__.ll").also { it.deleteOnExit() }.writeText(IRBuilder.build())
    File("__TEMP__.o").also {
        it.deleteOnExit()
    }.writeBytes(object{}.javaClass.classLoader.getResourceAsStream("lib.o").readAllBytes())


    val args = mutableListOf("clang", "__TEMP__.ll", "__TEMP__.o")
    if(o != null) args += "-o $o"
    if(emitLlvm) args += "-S -emit-llvm"
    if(s) args += "-S"

    val p = Runtime.getRuntime().exec(args.toTypedArray())
    println(p.inputStream.bufferedReader().readText())
    val err = p.errorStream.bufferedReader().readText()
    println(err)
}