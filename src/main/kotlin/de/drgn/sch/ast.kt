package de.drgn.sch

import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.TPtr
import de.drgn.irbuilder.types.tI1
import de.drgn.irbuilder.types.tI32
import de.drgn.irbuilder.values.VInt
import de.drgn.irbuilder.values.VString
import de.drgn.irbuilder.values.VValue
import java.util.*
import kotlin.math.max

abstract class ASTType(val tokens: TokenLine) {
    abstract fun tree(): DType
}
class ASTTVoid(tokens: TokenLine) : ASTType(tokens) {
    override fun tree() = DTVoid
}
class ASTTBool(tokens: TokenLine) : ASTType(tokens) {
    override fun tree() = DTBool
}
class ASTTInt(tokens: TokenLine, val name: String) : ASTType(tokens) {
    override fun tree() = when(name) {
        "i8" -> DTI8
        "i16" -> DTI16
        "i32" -> DTI32
        "i64" -> DTI64
        "u8" -> DTU8
        "u16" -> DTU16
        "u32" -> DTU32
        "u64" -> DTU64
        else -> TODO()
    }
}
class ASTTArray(tokens: TokenLine, val of: ASTType) : ASTType(tokens) {
    override fun tree() = DTArray(of.tree())
}
class ASTTName(tokens: TokenLine, val name: Token.Name) : ASTType(tokens) {
    override fun tree(): DTClass {
        return name.line.file.pckg.classes.find { it.name.name == name.name }?:illegal("Unknown type", tokens.line())
    }
}
class ASTTNullable(tokens: TokenLine, val of: ASTType) : ASTType(tokens) {
    override fun tree(): DType {
        val t = of.tree()
        if(t !is DTComplexType) illegal("Type '$t' cannot be nullable", tokens.line())
        return DTNullable(t)
    }
}
class ASTTTuple(tokens: TokenLine, val elements: List<Pair<Token.Name, ASTType>>) : ASTType(tokens) {
    override fun tree() = DTTuple(elements.map { (n, t) -> n to t.tree() })
}
class ASTTFunction(tokens: TokenLine, val returnType: ASTType, val args: List<ASTType>) : ASTType(tokens) {
    override fun tree(): DType {
        return DTFunc(args.map { it.tree() }, false, returnType.tree(), null)
    }
}


val ast = mutableListOf<ASTTopLevel>()

abstract class ASTTopLevel(val tokens: TokenLine) {
    abstract fun tree(): TreeTopLevel?
    open fun typesDone() {}
    open fun code() {}
}
class ASTFuncDefinition(
    tokens: TokenLine,
    val name: Token.Name,
    val params: List<Pair<Token.Name, ASTType>>?,
    val isVararg: Boolean,
    val returns: ASTType,
    val elements: List<ASTElement>,
    val isVirtual: Boolean = false,
    val override: Token.Override? = null
) : ASTTopLevel(tokens) {
    lateinit var treeFunc: TreeFuncDefinition
    override fun tree(): TreeFuncDefinition {
        treeFunc = TreeFuncDefinition(name, isVararg, isVirtual, override != null)
        return treeFunc
    }

    override fun typesDone() {
        treeFunc.returnType = returns.tree()

        treeFunc.params = params!!.map { DLocal(it.first, it.second.tree(), false) }

        treeFunc.global = DGlobal(
            tokens[0].line.file.pckg,
            if(treeFunc.inClass != null) Token.Name(name.line, "${treeFunc.inClass}::$name") else name,
            DTFunc(treeFunc.params.map { it.type }, isVararg, treeFunc.constructorOf?:treeFunc.returnType, treeFunc.inClass),
            true,
            when {
                treeFunc.inClass != null -> "\"${treeFunc.inClass}::$name\""
                treeFunc.destructorOf != null -> "\"${treeFunc.destructorOf}::destructor\""
                else -> null
            },
            function = true,
            global = true
        )


        name.line.file.pckg.globals += treeFunc.global
    }

    override fun code() {
        Companion.code(tokens, treeFunc, elements)
    }

    companion object {
        fun code(tokens: TokenLine, treeFunc: TreeFuncDefinition, elements: List<ASTElement>) {
            openFuncDefinitions += treeFunc
            blocks += treeFunc.block

            treeFunc.params.forEach {
                blocks.peek().variables += it
                blocks.peek().variableInformation[it] = VariableInformation(it, true, it.type)
            }

            val properties = (treeFunc.constructorOf?:treeFunc.inClass)?.properties
            properties?.forEachIndexed { i, it ->
                val v = DReceiverMember(
                    it.first,
                    it.second,
                    treeFunc.constructorOf ?: treeFunc.inClass!!,
                    i + DTClass.INTERNAL_VALUES.toInt() - properties.take(i).count { isConstantFunction(it.third) },
                    it.third
                )
                treeFunc.block.variables += v
                treeFunc.block.variableInformation[v] = VariableInformation(v, false, v.type)
            }

            elements.forEach {
                treeFunc.block.elements += it.tree()
            }
            if(!treeFunc.block.returned && treeFunc.returnType != DTVoid)
                illegal("Function has to return a value", tokens.last().line)

            if(treeFunc.constructorOf != null) {
                treeFunc.block.variables.forEach {
                    if (it is DReceiverMember && it.function == null && !treeFunc.block.variableInformation[it]!!.isInitialized)
                        illegal("Property needs to be initialized in constructor", it.name.line)
                }
            }

            blocks.pop()
            openFuncDefinitions.pop()
        }
    }
}

class ASTIntrinsicDeclaration(
    tokens: TokenLine,
    val name: Token.Intrinsic,
    val args: List<ASTType?>,
    val isVararg: Boolean,
    val returns: ASTType
) : ASTTopLevel(tokens) {
    lateinit var f: TreeIntrinsicDeclaration
    override fun tree(): TreeTopLevel {
        f = TreeIntrinsicDeclaration(
            tokens,
            name,
            isVararg,
            intrinsics["${tokens[0].line.file.pckg}::${name.name}"]
                ?: illegal("Intrinsic doesn't have a body", name.line)
        )
        return f
    }

    override fun typesDone() {
        f.returnType = returns.tree()
        f.args = this.args.map { it?.tree() }
        tokens[0].line.file.pckg.intrinsics += f
    }

    companion object {
        val intrinsics =
            mapOf<String, (tokens: TokenLine, args: List<Any>, builder: IRBuilder.FunctionBuilder) -> VValue?>(
                /*"std::print" to { line, args, builder ->
                    format(line, builder, args[0].constant as String, args[0], args.drop(1))
                    null
                },*/
                "std::println" to { tokens, args, builder ->

                    val format = args[0] as List<*>

                    if(format.size != 1 || format[0] !is Token.StringLiteral)
                        illegalIntrinsic("Expected string literal", (format as TokenLine).line())

                    format(tokens, builder, (format[0] as Token.StringLiteral).string + '\n', format[0] as Token.StringLiteral, args.drop(1).map { it as TreeObject })
                    null
                },
                /*"std::stoi" to { line, args, builder ->
                    builder.callFunc(atoi.first, atoi.second, args[0].ir(builder))
                },
                "rnd::seed" to { line, args, builder ->
                    builder.callFunc(srand.first, srand.second, args[0].ir(builder))
                    null
                },
                "rnd::randint" to { line, args, builder ->
                    builder.callFunc(rand.first, rand.second)
                },
                "std::slen" to { line, args, builder ->
                    builder.extractValue(args[0].ir(builder), 0)
                },*/
                "std::len" to { tokens, args, builder ->
                    val o = parseObject(args[0] as TokenLine).obj()
                    if(o.type !is DTArray) illegalIntrinsic("Expected array but found '${o.type}'", o.tokens.line())
                    val sizeptr = builder.elementPtr(struct_Array, o.ir(builder), VInt(tI32, 0), VInt(tI32, 1))
                    builder.load(tI32, sizeptr)
                },
                "std::type" to { tokens, args, builder ->
                    val o = parseObject(args[0] as TokenLine).obj()
                    if(o.type !is DTClass) illegalIntrinsic("Cannot get type of non-class objects", o.tokens.line())
                    val typeptr = builder.elementPtr(struct_Class, o.ir(builder), VInt(tI32, 0), VInt(tI32, 1))
                    builder.load(tI32, typeptr)
                }
            )

        private fun format(tokens: TokenLine, builder: IRBuilder.FunctionBuilder, format: String, formatToken: Token.StringLiteral, args: List<TreeObject>) {
            val sb = StringBuilder()

            var n = 0

            val resArgs = mutableListOf<VValue>()

            val ite = format.withIndex().iterator()

            for ((i, c) in ite) {
                if (c == '{') {
                    if (n == args.size) illegalIntrinsic("More positional arguments in format string than arguments", formatToken.line)
                    var j = i + 1
                    while(ite.next().value != '}') j++
                    val p = formatObj(args[n], builder, format.substring(i + 1, j).trim())
                    sb.append(p.first)
                    resArgs += p.second
                    n++
                } else sb.append(c)
            }
            if (n != args.size) {
                val l = mutableListOf<C>()
                args.drop(n).forEach { l += it.tokens.line().l }
                illegalIntrinsic("More arguments that positional arguments in format string", Line(tokens[0].line.file, l))
            }
            builder.callFunc(func_printf.first, func_printf.second, *(listOf(VString(sb.toString())) + resArgs).toTypedArray())
        }

        private fun formatObj(obj: TreeObject, builder: IRBuilder.FunctionBuilder, parameter: String = ""): Pair<String, List<VValue>> {
            if(obj is TreeStringLiteral) return obj.value to emptyList()
            val sb = StringBuilder()
            val resArgs = mutableListOf<VValue>()
            when (val type = obj.type) {
                is DTInt -> {
                    val t = when(type) {
                        is DTI8 -> {
                            (if(parameter == "c") "c" else "h") to TreeCast(obj.tokens, obj, DTI16)
                        }

                        is DTI16 -> "h" to obj
                        is DTI32 -> "" to obj
                        is DTI64 -> "ll" to obj
                        else -> TODO()
                    }
                    sb.append('%').append(t.first)
                    resArgs += t.second.ir(builder)
                    if (parameter == "x") sb.append('x') else if(parameter.isEmpty()) sb.append('d')
                }

                is DTBool -> {
                    sb.append("%s")
                    resArgs += builder.select(obj.ir(builder), VString("true"), VString("false"))
                }

                is DTArray -> {
                    if(type.of is DTI8 && parameter == "s") {
                        val o = obj.ir(builder)
                        resArgs += builder.load(tI32, builder.elementPtr(struct_Array, o, VInt(tI32, 0), VInt(tI32, 1)))
                        resArgs += builder.load(TPtr, builder.elementPtr(struct_Array, o, VInt(tI32, 0), VInt(tI32, 2)))
                        sb.append("%.*s")
                    } else illegalTodo(obj.tokens.line())
                }

                else -> illegalIntrinsic("Cannot print objects of type '$type'", obj.tokens.line())
            }
            return sb.toString() to resArgs
        }
    }
}
class ASTClassDefinition(
    tokens: TokenLine,
    val name: Token.Name,
    val superClass: Token.Name?,
    val constructor: ASTFuncDefinition,
    val properties: List<Pair<Token.Name, ASTType>>,
    val functions: List<ASTFuncDefinition>
) : ASTTopLevel(tokens) {
    lateinit var dtClass: DTClass
    val deconstructor = ASTFuncDefinition(tokens, name, emptyList(), false, ASTTVoid(tokens), emptyList())
    override fun tree(): TreeTopLevel? {
        val construct = constructor.tree()
        val destruct = deconstructor.tree()

        val funcs = functions.map { it.tree() }

        dtClass = DTClass(name, construct, destruct, funcs)
        construct.constructorOf = dtClass
        destruct.destructorOf = dtClass
        funcs.forEach { it.inClass = dtClass }
        name.line.file.pckg.classes += dtClass

        tree += construct
        tree += destruct
        tree += funcs

        return null
    }

    override fun typesDone() {
        dtClass.superClass = if(superClass == null) null else ASTTName(listOf(superClass), superClass).tree()
        constructor.typesDone()
        deconstructor.typesDone()
        functions.forEach {
            it.typesDone()
            if(it.override != null) {
                val f = dtClass.superClass?.functions?.find { f -> f.name.name == it.name.name }?: illegal("Super function cannot be found", it.override.line)
                if(!it.treeFunc.global.type.isType(f.global.type)) illegal("Function signature has to match super function", it.override.line)
            }
        }

        dtClass.properties = run {
            if(dtClass.superClass != null) dtClass.superClass!!.properties.map {
                if(isLocalFunction(it.third))
                    Triple(it.first, it.second, dtClass.functions.find { f -> f.name.name == it.third!!.name.name }?.also { f ->
                        if(!f.isOverride) illegal("Function has to override '${it.third!!.inClass!!}.${it.third!!.name}'", f.name.line)
                    }?:it.third)
                else it
            } else emptyList()
        } + properties.map { Triple(it.first, it.second.tree(), null) } + dtClass.functions.filter { !it.isOverride }.map { Triple(it.name, it.global.type, it) }
    }

    override fun code() {
        constructor.code()
        deconstructor.code()
        functions.forEach { it.code() }
    }
}

class ASTGlobalDeclaration(tokens: TokenLine, val names: List<Token.Name>, val types: List<ASTType?>, val astObjects: List<ASTObject>) : ASTTopLevel(tokens) {
    lateinit var treeGlobal: TreeGlobalDeclaration

    lateinit var objects: List<Any>

    override fun tree(): TreeTopLevel {
        treeGlobal = TreeGlobalDeclaration()
        return treeGlobal
    }

    override fun typesDone() {
        objects = astObjects.flatMap { if (it is ASTUnwrap) it.unwrap() else listOf(it) }

        if (names.size != objects.size)
            illegal(
                "Expected ${names.size} object(s) but found ${objects.size}",
                objects.flatMap { if (it is ASTObject) it.tokens else (it as TreeObject).tokens }.line()
            )

        val ts = mutableListOf<DType>()
        val os = Array<TreeObject?>(names.size) { null }

        objects.zip(types).forEachIndexed { i, (obj, type) ->
            if (type != null) {
                ts += type.tree()
            } else {
                val o = if (obj is TreeObject) obj else (obj as ASTObject).obj()
                os[i] = o
                ts += o.type
            }
        }

        val pckg = tokens[0].line.file.pckg

        names.zip(ts).forEachIndexed { i, (name, t) ->
            if (name.name == "_")
                return@forEachIndexed

            pckg.globals.find { it.name.name == name.name }?.let {
                illegal("Variable declared twice", it.name.line, name.line)
            }

            val v = DGlobal(pckg, name, t, false)
            treeGlobal.variables += v
            treeGlobal.objects += os[i]
            pckg.globals += v
        }
    }

    override fun code() {
        objects.forEachIndexed { i, it ->
            if(treeGlobal.objects[i] == null)
                treeGlobal.objects[i] = if(it is ASTObject) it.obj(treeGlobal.variables[i].type) else it as TreeObject
        }
    }
}



abstract class ASTElement(val tokens: TokenLine) {
    abstract fun tree(): TreeElement
}
class ASTReturn(tokens: TokenLine, val obj: ASTObject?) : ASTElement(tokens) {
    override fun tree(): TreeElement {
        if(openFuncDefinitions.peek().constructorOf != null || openFuncDefinitions.peek().destructorOf != null)
            illegal("Cannot return in constructors", tokens.line())

        val expects = openFuncDefinitions.peek().returnType

        if(obj == null && expects != DTVoid)
            illegal("Expected '$expects' but found 'void'", tokens.line())

        if(obj != null && expects == DTVoid)
            illegal("Expected end of line", obj.tokens.line())

        blocks.peek().returned = true

        return TreeReturn(obj?.obj(openFuncDefinitions.peek().returnType))
    }
}
class ASTVariableDeclaration(tokens: TokenLine, val names: List<Token.Name>, val types: List<ASTType?>, val objects: List<ASTObject>?) : ASTElement(tokens) {
    override fun tree(): TreeElement {

        val objects = objects?.flatMap { if(it is ASTUnwrap) it.unwrap() else listOf(it) }

        if(objects != null && names.size != objects.size)
            illegal(
                "Expected ${names.size} object(s) but found ${objects.size}",
                objects.flatMap { if(it is ASTObject) it.tokens else (it as TreeObject).tokens }.line()
            )

        val ts: List<DType>
        val os: List<TreeObject>?

        if(objects == null) {
            ts = types.mapIndexed { i, it -> it?.tree()?: illegal("Cannot implicitly infer variable type", names[i].line) }
            os = null
        } else {
            ts = mutableListOf()
            os = mutableListOf()
            objects.zip(types).forEachIndexed { i, (obj, type) ->
                if(type != null) {
                    val t = type.tree()
                    ts += t
                    os += if(obj is TreeObject) {
                        if(!obj.type.isType(t))
                            illegal("Expected '$t' but found '${obj.type}' in unwrapping at index $i", obj.tokens.line())
                        obj
                    } else (obj as ASTObject).obj(t)
                } else {
                    val o = if(obj is TreeObject) obj else (obj as ASTObject).obj()
                    os += o
                    ts += o.type
                }
            }
        }

        val vars = mutableListOf<DLocal>()
        names.zip(ts).forEachIndexed { i, (name, t) ->
            if(name.name == "_")
                return@forEachIndexed

            blocks.peek().variables.find { it.name.name == name.name }?.let {
                illegal("Variable declared twice", it.name.line, name.line)
            }

            val v = DLocal(name, t, false)
            blocks.peek().variables += v
            blocks.peek().variableInformation[v] = VariableInformation(v, os != null, os?.get(i)?.type?:t)
            vars += v
        }

        return TreeVariableDeclaration(vars, os)
    }
}
class ASTSet(tokens: TokenLine, val storages: List<ASTStorage>, val objects: List<ASTObject>) : ASTElement(tokens) {
    override fun tree(): TreeElement {
        if(storages.size != objects.size)
            illegal("Expected ${storages.size} objects but found ${objects.size}", objects.flatMap { it.tokens }.line())

        val ss = storages.map { it.tree() }
        val os = objects.mapIndexed { i, it -> it.obj(ss[i].type) }
        ss.zip(os).forEach { (s, o) -> s.initialize(o) }
        return TreeSet(ss, os)
    }
}
class ASTWhile(tokens: TokenLine, val condition: ASTObject, val element: ASTElement) : ASTElement(tokens) {
    override fun tree(): TreeElement {

        val block = Block()

        block.variableInformation += blocks.peek().variableInformation.mapValues { it.value.copy() }
        block.returned = blocks.peek().returned
        blocks += block
        val o = require(condition, DTBool)
        val e = TreeWhile(o)
        if(element is ASTScope) {
            element.elements.forEach {
                block.elements += it.tree()
            }
        } else block.elements += element.tree()
        blocks.pop()

        e.block = block

        return e
    }
}
class ASTFor(tokens: TokenLine, val pre: ASTElement?, val condition: ASTObject, val post: ASTElement?, val element: ASTElement) : ASTElement(tokens) {
    override fun tree(): TreeElement {
        val block = Block()
        val outerBlock = Block()

        outerBlock.variableInformation += blocks.peek().variableInformation.mapValues { it.value.copy() }
        outerBlock.returned = blocks.peek().returned
        blocks += outerBlock

        val pre = pre?.tree()
        val post = post?.tree()

        block.variableInformation += blocks.peek().variableInformation.mapValues { it.value.copy() }
        block.returned = blocks.peek().returned
        blocks += block


        val condition = require(condition, DTBool)
        val e = TreeFor(pre, condition, post, outerBlock)
        if(element is ASTScope) {
            element.elements.forEach {
                block.elements += it.tree()
            }
        } else block.elements += element.tree()
        blocks.pop()
        blocks.pop()

        e.block = block

        return e
    }
}
class ASTCalculationAssign(tokens: TokenLine, val operator: Token, val storage: ASTStorage, val obj: ASTObject) : ASTElement(tokens) {
    override fun tree(): TreeElement {
        val s = storage.tree()
        if(!s.isInitialized) illegal("Variable may not be initialized", s.tokens.line())
        if(s.type !is DTInt) illegal("Expected integer but found '${s.type}'", storage.tokens.line())
        val o = obj.obj(s.type)
        return TreeCalculationAssign(operator, s, o)
    }
}
class ASTContinue(tokens: TokenLine) : ASTElement(tokens) {
    override fun tree(): TreeElement {
        return TreeContinue(tokens.single() as Token.Continue)
    }
}
class ASTBreak(tokens: TokenLine) : ASTElement(tokens) {
    override fun tree(): TreeElement {
        return TreeBreak(tokens.single() as Token.Break)
    }
}



abstract class ASTObject(tokens: TokenLine, val isOnlyObject: Boolean = true) : ASTElement(tokens) {
    override fun tree(): TreeElement {
        TODO("Not yet implemented")
    }
    fun obj(type: DType? = null): TreeObject {
        val o = _obj(type)
        if(type != null && !o.type.isType(type)) {
            illegal("Expected '$type' but found '${o.type}'", o.tokens.line())
        }
        return o
    }
    abstract fun _obj(type: DType?): TreeObject
}
class ASTIntLiteral(tokens: TokenLine, val literal: Token.IntLiteral) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        if(type !is DTInt?) illegal("Expected '$type' but found integer", tokens.line())
        when(type) {
            null -> {
                literal.value.toIntOrNull()?:literal.value.toLongOrNull()?:literal.value.toULongOrNull()?:illegal("Integer literal cannot be stored", literal.line)
            }
            is DTI8 -> literal.value.toByteOrNull()?:illegal("Integer literal cannot be stored in a 'i8'", literal.line)
            is DTI16 -> literal.value.toShortOrNull()?:illegal("Integer literal cannot be stored in a 'i16'", literal.line)
            is DTI32 -> literal.value.toIntOrNull()?:illegal("Integer literal cannot be stored in a 'i32'", literal.line)
            is DTI64 -> literal.value.toLongOrNull()?:illegal("Integer literal cannot be stored in a 'i64'", literal.line)

            is DTU8 -> literal.value.toUByteOrNull()?:illegal("Integer literal cannot be stored in a 'u8'", literal.line)
            is DTU16 -> literal.value.toUShortOrNull()?:illegal("Integer literal cannot be stored in a 'u16'", literal.line)
            is DTU32 -> literal.value.toUIntOrNull()?:illegal("Integer literal cannot be stored in a 'u32'", literal.line)
            is DTU64 -> literal.value.toULongOrNull()?:illegal("Integer literal cannot be stored in a 'u64'", literal.line)
            else -> TODO()
        }
        val type = when {
            type != null -> type
            literal.value.toIntOrNull() != null -> DTI32
            literal.value.toUIntOrNull() != null -> DTU32
            literal.value.toLongOrNull() != null -> DTI64
            else -> DTU64
        }
        val value = literal.value.toLong()
        return TreeIntLiteral(tokens, value, type)
    }
}
class ASTStringLiteral(tokens: TokenLine, val literal: Token.StringLiteral) : ASTObject(tokens) {
    override fun _obj(type: DType?) = TreeStringLiteral(tokens, literal.string)
}
class ASTBoolLiteral(tokens: TokenLine, val literal: Token.BoolLiteral) : ASTObject(tokens) {
    override fun _obj(type: DType?) = TreeBoolLiteral(tokens, literal.value)
}
class ASTIntrinsicUse(tokens: TokenLine, val intrinsic: Token.Intrinsic, val args: List<TokenLine>) : ASTObject(tokens) {
    private fun process(): Pair<TreeIntrinsicDeclaration, List<Any>> {
        val intrinsic = getIntrinsic(intrinsic)
        if(args.size < intrinsic.args.size || (args.size != intrinsic.args.size && !intrinsic.isVararg))
            illegal("Expected ${intrinsic.args.size} argument(s) but found ${args.size}", tokens.line())

        val args = args.mapIndexed { i, arg ->
            if(i < intrinsic.args.size) {
                if(intrinsic.args[i] == null) arg else parseObject(arg).obj(intrinsic.args[i])
            } else parseObject(arg).obj()
        }
        return intrinsic to args
    }
    override fun tree(): TreeElement {
        val p = process()
        return TreeIntrinsicUse(tokens, p.first, p.second)
    }

    override fun _obj(type: DType?): TreeObject {
        val p = process()
        return TreeIntrinsicUseO(tokens, p.first, p.second)
    }

    override fun toString() = "${intrinsic.name}!(${args.joinToString { it.toString() }})"
}
class ASTVariableUse(tokens: TokenLine, val name: Token.Name) : ASTObject(tokens) {
    override fun tree(): TreeElement { TODO() }
    override fun _obj(type: DType?): TreeObject {
        val v = getVariable(name)

        if(v is DLocal && !blocks.peek().variableInformation[v]!!.isInitialized)
            illegal("Variable may not be initialized", tokens.line())

        return TreeVariableUse(tokens, v)
    }
}
class ASTFuncCall(tokens: TokenLine, val func: ASTObject, val args: List<ASTObject>) : ASTObject(tokens, false) {
    private fun process(): Pair<TreeObject, List<TreeObject>> {
        val f = func.obj()

        val args = args.map { if(it is ASTUnwrap) it.unwrap() else listOf(it) }.flatten()

        if(f.type !is DTFunc) illegal("Expected function but found '${f.type}'", func.tokens.line())
        if(args.size < f.type.args.size || (args.size != f.type.args.size && !f.type.isVararg))
            illegal("Expected ${f.type.args.size} argument(s) but found ${args.size}", tokens.line())

        val treeArgs = args.mapIndexed { i, arg ->
            val expected = if (i < f.type.args.size) f.type.args[i] else null
            if(arg is TreeObject) {
                if(expected != null && !arg.type.isType(expected))
                    illegal("Expected '$expected' but found '${arg.type}' in unwrapped object", arg.tokens.line())
                arg
            } else (arg as ASTObject).obj(expected)
        }
        return f to treeArgs
    }
    override fun tree(): TreeElement {
        val p = process()
        return TreeFuncCall(p.first, p.second)
    }

    override fun _obj(type: DType?): TreeObject {
        val p = process()
        if((p.first.type as DTFunc).returnType is DTVoid)
            illegal("Cannot use 'void' as an object", tokens.line())
        return TreeFuncCallO(tokens, p.first, p.second)
    }
}
class ASTInitializedArray(tokens: TokenLine, val elementType: ASTType?, val objects: List<ASTObject>) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        if(type !is DTArray?) illegal("Expected '$type' but found array", tokens.line())
        val t = type?:(elementType?.tree()?:objects.firstOrNull()?.obj()?.type)?.let { DTArray(it) } ?:illegal("Cannot implicitly infer array type", tokens.line())
        val os = objects.map { it.obj(t.of) }
        return TreeInitializedArray(tokens, t.of, os)
    }
}
class ASTGenerateArray(tokens: TokenLine, val elementType: ASTType?, val size: ASTObject, val function: ASTObject) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        if(type !is DTArray?) illegal("Expected '$type' but found array", tokens.line())
        val o = function.obj(type?.of?.let {
            DTFunc(listOf(DTU32), false, it, null)
        } ?: if(function is ASTLambda && function.returnType != null) DTFunc(listOf(DTU32), false, function.returnType.tree(), null) else null)
        if(o.type !is DTFunc) illegal("Expected function but found '${o.type}'", o.tokens.line())
        if(o.type.isVararg || o.type.args.size != 1 || o.type.args[0] !is DTI32)
            illegal("Expected function signature 'func(i32): T' but found '${o.type}'", o.tokens.line())

        val t = type?: DTArray((elementType?.tree()?:o.type.returnType))
        val size = size.obj(DTU32)
        return TreeGenerateArray(tokens, o, size, t.of)
    }
}
class ASTGet(tokens: TokenLine, val obj: ASTObject, val index: ASTObject) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        val o = obj.obj(if(type == null) null else DTArray(type))
        val i = index.obj(DTI32)
        if(o.type !is DTArray) illegal("Expected array but found '${o.type}'", o.tokens.line())
        return TreeGet(tokens, o, i)
    }
}
class ASTNull(tokens: TokenLine) : ASTObject(tokens) {
    override fun tree(): TreeElement { TODO() }
    override fun _obj(type: DType?): TreeObject {
        if(type == null) illegal("Cannot implicitly infer variable type", tokens.line())
        if(type !is DTNullable) illegal("Expected '$type' but found null", tokens.line())
        return TreeNull(tokens, type)
    }
}
class ASTNullAssert(tokens: TokenLine, val obj: ASTObject) : ASTObject(tokens, false) {
    override fun tree(): TreeElement {
        val o = obj.obj()
        if(o.type !is DTNullable) illegal("Expected nullable but found '${o.type}'", o.tokens.line())

        if(o is TreeVariableUse) {
            blocks.peek().variableInformation[o.variable]!!.smartCastType = o.type.of
        }

        return TreeNullAssert(tokens, o)
    }
    override fun _obj(type: DType?): TreeObject {
        if(type !is DTNullable? && type !is DTComplexType) illegal("Expected '$type' but found complex object", tokens.line())
        val o = obj.obj(if(type == null) null else if(type is DTComplexType) DTNullable(type) else type)
        if(o.type !is DTNullable) illegal("Expected nullable but found '${o.type}'", o.tokens.line())

        if(o is TreeVariableUse) {
            blocks.peek().variableInformation[o.variable]!!.smartCastType = o.type.of
        }

        return TreeNullAssertO(tokens, o)
    }
}
class ASTScope(tokens: TokenLine, val elements: List<ASTElement>) : ASTObject(tokens, false) {
    companion object {
        fun process(block: Block, elements: List<ASTElement>, obj: Boolean, type: DType?): TreeObject? {
            elements.dropLast(1).forEach {
                block.elements += it.tree()
            }
            val o = if (obj) (elements.last() as ASTObject).obj(type) else {
                block.elements += elements.last().tree()
                null
            }
            return o
        }
    }
    override fun tree(): TreeElement {
        val t = TreeScope()

        t.block.variableInformation += blocks.peek().variableInformation.mapValues { it.value.copy() }
        t.block.returned = blocks.peek().returned
        blocks += t.block

        process(t.block, elements, false, null)

        blocks.pop()
        blocks.peek().variableInformation = t.block.variableInformation
        blocks.peek().returned = t.block.returned

        return t
    }

    override fun _obj(type: DType?): TreeObject {
        val block = Block()
        block.variableInformation += blocks.peek().variableInformation.mapValues { it.value.copy() }
        block.returned = blocks.peek().returned
        blocks += block

        val obj = process(block, elements, true, type)!!

        blocks.pop()
        blocks.peek().variableInformation = block.variableInformation
        blocks.peek().returned = block.returned

        return TreeScopeO(tokens, block, obj)
    }
}
class ASTLambda(tokens: TokenLine, val returnType: ASTType?, val names: List<Token.Name>, val types: List<ASTType?>, val elements: List<ASTElement>) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        if(type !is DTFunc?) illegal("Expected '$type' but found function", tokens.line())

        val treeFunc = TreeFuncDefinition(Token.Name(emptyLine, "<lambda${lambdas++}>"),
            isVararg = false,
            isVirtual = false,
            isOverride = false
        )

        treeFunc.returnType = returnType?.tree()?:type?.returnType?: illegal("Cannot infer return type", tokens.line())

        treeFunc.params = names.mapIndexed { i, name ->
            val type = types[i]?.tree() ?: type?.returnType?: illegal("Cannot infer argument type", name.line)
            DLocal(name, type, false)
        }

        treeFunc.global = DGlobal(
            tokens[0].line.file.pckg,
            treeFunc.name,
            DTFunc(treeFunc.params.map { it.type }, false, treeFunc.returnType, null),
            true,
            null,
            function = true,
            global = true
        )

        ASTFuncDefinition.code(tokens, treeFunc, elements)

        tree += treeFunc

        return TreeVariableUse(tokens, treeFunc.global)
    }
    companion object {
        var lambdas = 0
    }
}
class ASTNew(tokens: TokenLine, val type: ASTType, val args: List<ASTObject>) : ASTObject(tokens) {
    private fun process(): Pair<TreeFuncDefinition, List<TreeObject>> {
        val t = type.tree()
        if(t !is DTClass) illegal("Expected class but found '$t'", type.tokens.line())
        val f = t.constructor
        if(args.size < f.params.size || (args.size != f.params.size && !f.isVararg))
            illegal("Expected ${f.params.size} argument(s) but found ${args.size}", tokens.line())

        val args = args.mapIndexed { i, arg ->
            arg.obj(if (i < f.params.size) f.params[i].type else null)
        }
        return f to args
    }
    override fun tree(): TreeElement {
        TODO()
        //	val p = process()
        //	return TreeFuncCall(tokens, p.first, p.second)
    }

    override fun _obj(type: DType?): TreeObject {
        val p = process()
        return TreeNew(tokens, p.first, p.second)
    }
}
class ASTMember(tokens: TokenLine, val obj: ASTObject, val property: Token.Name) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        val o = obj.obj()
        when (o.type) {
            is DTClass -> {
                val index = o.type.properties.indexOfFirst { it.first.name == property.name }
                if (index == -1) illegal("Property '${o.type}.$property' doesn't exist", property.line)
                if (isConstantFunction(o.type.properties[index].third)) {
                    return TreeVariableUse(tokens, o.type.properties[index].third!!.global, o)
                }
                if (o is TreeThis) {
                    return TreeVariableUse(
                        tokens,
                        blocks.peek().variables.find { it is DReceiverMember && it.name.name == property.name }!!
                    )
                }
                return TreeMember(
                    tokens,
                    o,
                    index - o.type.properties.take(max(index - 1, 0)).count { isLocalFunction(it.third) })
            }
            is DTTuple -> {
                val index = o.type.elements.indexOfFirst { (n, _) -> n.name == property.name }
                if(index == -1) illegal("Tuple element '${o.type}.$property' doesn't exist", property.line)
                return TreeTupleMember(tokens, o, index)
            }
            else -> illegal("Expected class object but found '${o.type}'", obj.tokens.line())
        }
    }
}
private fun checkInt(tokens: TokenLine, l: TreeObject, r: TreeObject): Pair<TreeObject, TreeObject> {
    var l = l
    var r = r
    if (l.type !is DTInt) illegal("Expected int but found '${l.type}'", l.tokens.line())
    if (r.type !is DTInt) illegal("Expected int but found '${r.type}'", r.tokens.line())
    if ((l.type as DTInt).ir.size > (r.type as DTInt).ir.size) {
        r = TreeCast(tokens, r, l.type)
    } else if ((r.type as DTInt).ir.size > (l.type as DTInt).ir.size) {
        l = TreeCast(tokens, l, r.type)
    }
    if ((l.type as DTInt).signed != (r.type as DTInt).signed) illegal(
        "Cannot mix signed and unsigned value",
        tokens.line()
    )
    return l to r
}
class ASTBinaryOperator(tokens: TokenLine, val operator: Token, val left: ASTObject, val right: ASTObject) : ASTObject(tokens) {

    sealed class Operator(
        val wants: DType?,
        val check: (TokenLine, TreeObject, TreeObject) -> Pair<TreeObject, TreeObject>,
        val type: (TreeObject) -> DType,
        val func: ((IRBuilder.FunctionBuilder, TreeObject, TreeObject, DType) -> VValue)?,
        val irFunc: (IRBuilder.FunctionBuilder, VValue, VValue, DType) -> VValue
    ) {
        sealed class Calculation(f: (IRBuilder.FunctionBuilder, VValue, VValue, DType) -> VValue) : Operator(null, ::checkInt, { t -> t.type }, null, f)
        sealed class Comparator(f: (IRBuilder.FunctionBuilder, VValue, VValue, DType) -> VValue) : Operator(null, ::checkInt, { _ -> DTBool }, null, f)
        sealed class Logical(b: Boolean)
            : Operator(DTBool, { _, l, r -> l to r }, { _ -> DTBool }, { builder, l, r, _ ->
            val l = l.ir(builder)
            val y = builder.register
            val n = builder.register
            val h = builder.register
            builder.branch(".$h")
            builder.label(".$h")
            builder.branch(l, if(b) ".$n" else ".$y", if(b) ".$y" else ".$n")
            builder.label(".$y")
            val r = r.ir(builder)
            builder.branch(".$n")
            builder.label(".$n")
            builder.phi(tI1, VInt(tI1, if(b) 1 else 0) to ".$h", r to ".$y")
        }, { _, _, _, _ -> TODO() })

        object Plus : Operator(null, ::checkInt, { t -> t.type }, null, { builder, l, r, _ -> builder.add(l, r) })
        object Modulo : Calculation({ builder, l, r, type -> builder.modulo(l, r, (type as DTInt).signed) })
        object LessThan : Comparator({ builder, l, r, type -> builder.icmp("${if((type as DTInt).signed) 's' else 'u'}lt", l, r) })
        object LessEqual : Comparator({ builder, l, r, type -> builder.icmp("${if((type as DTInt).signed) 's' else 'u'}le", l, r) })
        object Equals : Comparator({ builder, l, r, _ -> builder.icmp("eq", l, r) })
        object Unequal : Comparator({ builder, l, r, _ -> builder.icmp("ne", l, r) })
        object GreaterEqual : Comparator({ builder, l, r, type -> builder.icmp("${if((type as DTInt).signed) 's' else 'u'}ge", l, r) })
        object GreaterThan : Comparator({ builder, l, r, type -> builder.icmp("${if((type as DTInt).signed) 's' else 'u'}gt", l, r) })
        object And : Logical(false)
        object Or : Logical(true)

        companion object {
            fun get(name: String): Operator {
                Operator::class.sealedSubclasses.forEach {
                    if(it.objectInstance != null && it.simpleName!!.substringAfterLast('.') == name)
                        return it.objectInstance!!
                    if(it.isSealed) {
                        val o = it.sealedSubclasses.find { it.simpleName!!.substringAfterLast('.') == name }?.objectInstance
                        if(o != null) return o
                    }
                }
                TODO(name)
            }
        }
    }
    override fun _obj(type: DType?): TreeObject {
        val operator = Operator.get(operator.javaClass.simpleName.substringAfterLast('.'))
        val (l, r) = operator.check(tokens, left.obj(operator.wants), right.obj(operator.wants))
        val type = operator.type(l)
        return TreeBinaryOperator(
            tokens,
            l,
            r,
            type,
            operator.func ?: { builder, l, r, type -> operator.irFunc(builder, l.ir(builder), r.ir(builder), type) }
        )
    }
}
class ASTIncrease(tokens: TokenLine, val storage: ASTStorage) : ASTObject(tokens, false) {
    override fun tree(): TreeElement {
        val s = storage.tree()
        if(!s.isInitialized) illegal("Variable may not be initialized", s.tokens.line())
        if(s.type !is DTInt) illegal("Expected integer but found '${s.type}'", storage.tokens.line())
        return TreeIncrease(s)
    }
    override fun _obj(type: DType?): TreeObject {
        val s = storage.tree()
        if(!s.isInitialized) illegal("Variable may not be initialized", s.tokens.line())
        if(s.type !is DTInt) illegal("Expected integer but found '${s.type}'", storage.tokens.line())
        return TreeIncreaseO(tokens, s)
    }
}
class ASTSign(tokens: TokenLine, val token: Token.Sign, val obj: ASTObject) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        val o = obj.obj(type)
        if(o.type !is DTInt || !o.type.signed) illegal("Expected signed integer but found '${o.type}'", obj.tokens.line())
        return TreeSign(tokens, token, o)
    }
}
class ASTThis(tokens: TokenLine) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        val func = openFuncDefinitions.peek()
        if(func.inClass == null && func.constructorOf == null) illegal("'this' is not allowed here", tokens.line())
        return TreeThis(tokens)
    }
}
class ASTCast(tokens: TokenLine, val obj: ASTObject, val type: ASTType) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        val o = obj.obj()
        val t = this.type.tree()
        if(!(o.type is DTClass && t is DTClass && t.isType(o.type) || o.type is DTInt && t is DTInt))
            illegal("Cannot cast '${o.type}' to '$t'", tokens.line())

        if(o is TreeVariableUse) {
            blocks.peek().variableInformation[o.variable]!!.also {
                if(it.smartCastType.isType(t)) warning("Unnecessary cast", tokens.line())
            }.smartCastType = t
        }

        return TreeCast(tokens, o, t)
    }
}
class ASTIs(tokens: TokenLine, val obj: ASTObject, val type: ASTType) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        val o = obj.obj()
        val t = this.type.tree()
        if(o.type !is DTClass || t !is DTClass || !t.isType(o.type))
            illegal("Cannot check whether '${o.type}' is of type '$t'", tokens.line())

        if(o is TreeVariableUse && requires.isNotEmpty()) {
            requires.peek()[o.variable]!!.also {
                if(it.smartCastType.isType(t))
                    warning("Type check will always return 'true'", tokens.line())
            }.smartCastType = t
        }

        return TreeIs(tokens, o, t)
    }
}
class ASTIf(tokens: TokenLine, val ifs: List<Pair<ASTObject, ASTElement>>, val elseE: ASTElement?) : ASTObject(tokens, false) {
    override fun tree(): TreeElement {

        val ifs = mutableListOf<Pair<TreeObject, Block>>()
        var elseE: Block? = null

        this.ifs.forEach { (condition, element) ->
            val b = Block()
            b.variableInformation += blocks.peek().variableInformation.mapValues { it.value.copy() }
            blocks += b
            val c = require(condition, DTBool)

            if(element is ASTScope) {
                element.elements.forEach {
                    b.elements += it.tree()
                }
            } else {
                b.elements += element.tree()
            }
            blocks.pop()
            ifs += c to b
        }
        if(this.elseE != null) {
            elseE = Block()
            elseE.variableInformation += blocks.peek().variableInformation.mapValues { it.value.copy() }
            blocks += elseE
            if(this.elseE is ASTScope) {
                this.elseE.elements.forEach {
                    elseE.elements += it.tree()
                }
            } else {
                elseE.elements += this.elseE.tree()
            }
            blocks.pop()
        }
        if(elseE != null) {
            val allBlocks: List<Block> = ifs.map { it.second } + elseE
            if (!blocks.peek().returned && allBlocks.all { it.returned }) blocks.peek().returned = true
            /*blocks.peek().variableInformation.forEach { (v, info) ->
				allBlocks.forEach {
					if (it.variableInformation[v]!!.smartCastType)
				}
			}*/
        }
        return TreeIf(ifs, elseE)
    }

    override fun _obj(type: DType?): TreeObject {

        if(elseE == null) illegal("Requires else-branch", tokens.line())

        val ifs = mutableListOf<Triple<TreeObject, Block, TreeObject>>()

        this.ifs.forEach {
            val block = Block()
            block.variableInformation += blocks.peek().variableInformation.mapValues { it.value.copy() }
            block.returned = blocks.peek().returned
            blocks += block

            val condition = require(it.first, DTBool)

            val obj = if(it.second is ASTScope) ASTScope.process(block, (it.second as ASTScope).elements, true, type)!! else {
                if(it.second !is ASTObject) illegal("Expected expression")
                (it.second as ASTObject).obj(type)
            }

            blocks.pop()
            blocks.peek().variableInformation = block.variableInformation
            blocks.peek().returned = block.returned

            ifs += Triple(condition, block, obj)
        }

        val block = Block()
        block.variableInformation += blocks.peek().variableInformation.mapValues { it.value.copy() }
        blocks += block

        val obj = if(elseE is ASTScope) ASTScope.process(block, elseE.elements, true, type)!! else {
            if(elseE !is ASTObject) illegal("Expected expression")
            elseE.obj(type)
        }

        blocks.peek().variableInformation = block.variableInformation
        blocks.peek().returned = block.returned
        blocks.pop()

        return TreeIfO(tokens, ifs, block to obj)
    }
}
class ASTTuple(tokens: TokenLine, val objects: List<Pair<Token.Name, ASTObject>>) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        val os = if(type == null) {
            objects.map { (n, t) -> n to t.obj() }
        } else {
            if(type !is DTTuple) illegal("Expected '$type' but found tuple", tokens.line())
            if(objects.size != type.elements.size) illegal("Expected ${type.elements.size} object(s) but found ${objects.size}", tokens.line())
            objects.mapIndexed { i, (n, o) -> n to o.obj(type.elements[i].second) }
        }
        return TreeTuple(tokens, os)
    }
}
class ASTUnwrap(tokens: TokenLine, val obj: ASTObject) : ASTObject(tokens) {
    override fun _obj(type: DType?): TreeObject {
        illegal("Cannot unwrap here", tokens.line())
    }
    fun unwrap(): List<TreeObject> {
        val o = obj.obj()
        return when(o.type) {
            is DTTuple -> o.type.elements.mapIndexed { i, it -> TreeTupleMember(tokens, o, i) }
            else -> illegal("Cannot unwrap objects of type '${o.type}'", o.tokens.line())
        }
    }
}
class ASTParentheses(tokens: TokenLine, val obj: ASTObject) : ASTObject(tokens) {
    override fun _obj(type: DType?) = obj.obj(type)
}



val requires = Stack<MutableMap<DVariable, VariableInformation>>()
fun require(obj: ASTObject, type: DType?): TreeObject {
    val info = blocks.peek().variableInformation
    requires += info
    val o = obj.obj(type)
    blocks.peek().variableInformation = info
    requires.pop()
    return o
}



abstract class ASTStorage(val tokens: TokenLine) {
    abstract fun tree(): TreeStorage
}
class ASTSVariableUse(tokens: TokenLine, val name: Token.Name) : ASTStorage(tokens) {
    override fun tree(): TreeStorage {
        return TreeSVariableUse(tokens, getVariable(name))
    }
}
class ASTSMember(tokens: TokenLine, val obj: ASTObject, val property: Token.Name) : ASTStorage(tokens) {
    override fun tree(): TreeStorage {
        val o = obj.obj()
        if(o.type !is DTClass) illegal("Expected class object but found '${o.type}'", obj.tokens.line())
        val index = o.type.properties.indexOfFirst { it.first.name == property.name }
        if(index == -1) illegal("Property '${o.type}.$property' doesn't exist", property.line)
        if(o.type.properties[index].third != null) illegal("Cannot modify constants", tokens.line())
        if(o is TreeThis) {
            return TreeSVariableUse(tokens, blocks.peek().variables.find { it is DReceiverMember && it.name.name == property.name }!!)
        }
        return TreeSMember(tokens, o, index)
    }
}