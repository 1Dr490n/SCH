package de.drgn.sch

import de.drgn.irbuilder.FuncSignature
import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.*
import de.drgn.irbuilder.values.*
import java.util.Stack

val tree = mutableListOf<TreeTopLevel>()
val openFuncDefinitions = Stack<TreeFuncDefinition>()

abstract class TreeTopLevel {
    abstract fun ir()
}
class TreeFuncDefinition(
    val name: Token.Name,
    val isVararg: Boolean,
    val isVirtual: Boolean,
    val isOverride: Boolean
) : TreeTopLevel() {
    lateinit var returnType: DType
    lateinit var global: DGlobal
    lateinit var returnValue: VValue
    lateinit var params: List<DLocal>

    var constructorOf: DTClass? = null
    var destructorOf: DTClass? = null
    var inClass: DTClass? = null

    var receiver: VLocal? = null

    val block = Block()

    override fun ir() {
        openFuncDefinitions += this

        val args = if(destructorOf == null) params.map { it.type.ir() }.let { if(inClass != null) listOf(TPtr) + it else it } else listOf(TPtr)

        IRBuilder.func(
            global.irName,
            if (destructorOf != null) TVoid else constructorOf?.ir() ?: returnType.ir(),
            *args.toTypedArray()
        ) {
            blocks += block
            if(returnType != DTVoid)
                returnValue = alloca(returnType.ir())

            if(constructorOf != null) {
                receiver = callFunc(func_malloc.first, func_malloc.second, VInt(tI64, constructorOf!!.struct().size()))
                store(receiver!!, VInt(tI32, 0))
                store(
                    elementPtr(constructorOf!!.struct(), receiver!!, VInt(tI32, 0), VInt(tI32, DTClass.TYPE)), VInt(
                        tI32,
                        constructorOf!!.number.toLong()
                    )
                )
                store(elementPtr(constructorOf!!.struct(), receiver!!, VInt(tI32, 0), VInt(tI32, DTClass.DESTRUCTOR)), constructorOf!!.destructor.global.ir(this@func))
                block.variables.forEach {
                    if(it is DReceiverMember) {
                        if(isLocalFunction(it.function)) {
                            store(it.ir(this@func), it.function!!.global.ir(this@func))
                        } else {
                            val default = it.type.default(this@func)?:return@forEach
                            store(it.ir(this@func), default)
                        }
                    }
                }
            } else if((destructorOf ?: inClass) != null) receiver = this.args[0]

            params.forEachIndexed { i, local ->
                local.vLocal = alloca(this.args[i].type)
                store(local.vLocal, this.args[i])
                increment(this@func, local.vLocal, local.type)
            }

            block.elements.forEach {
                it.ir(this)
            }

            if(constructorOf != null) {
                ret(receiver!!)
            } else if(destructorOf != null) {
                destructorOf!!.properties.forEachIndexed { i, (_, t, f) ->
                    if (f == null) {
                        decrement(
                            this@func,
                            elementPtr(destructorOf!!.struct(), receiver!!, VInt(tI32, 0), VInt(tI32, i + DTClass.INTERNAL_VALUES)),
                            t
                        )
                    }
                }
                callFunc(func_free.first, func_free.second, receiver!!)
                ret(null)
            } else {
                if (block.elements.lastOrNull() !is TreeReturn) {
                    block.variables.forEach { v ->
                        decrement(this@func, v.ir(this@func), v.type)
                    }
                    branch(".ret")
                }
                label(".ret")
                ret(if (returnType != DTVoid) load(returnType.ir(), returnValue) else null)
            }
            blocks.pop()
        }
        openFuncDefinitions.pop()
    }
}
class TreeIntrinsicDeclaration(
    tokens: TokenLine,
    val name: Token.Intrinsic,
    val isVararg: Boolean,
    val macro: (line: TokenLine, args: List<Any>, builder: IRBuilder.FunctionBuilder) -> VValue?
) : TreeTopLevel() {
    lateinit var returnType: DType
    lateinit var args: List<DType?>

    override fun ir() {}
}
class TreeGlobalDeclaration : TreeTopLevel() {
    val variables = mutableListOf<DGlobal>()
    val objects = mutableListOf<TreeObject?>()

    override fun ir() {
        variables.forEachIndexed { i, v ->
            IRBuilder.global(v.irName, v.type.undefined())
        }
    }
}




abstract class TreeElement {
    abstract fun ir(builder: IRBuilder.FunctionBuilder)
}
class TreeReturn(val obj: TreeObject?) : TreeElement() {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        if(obj != null) {
            val returnVar = openFuncDefinitions.peek().returnValue
            builder.store(returnVar, obj.ir(builder))

            increment(builder, returnVar, obj.type)
        }

        blocks.forEach {
            it.variables.forEach { v ->
                decrement(builder, v.ir(builder), v.type)
            }
        }

        builder.branch(".ret")
    }
}
class TreeIntrinsicUse(val tokens: TokenLine, val macro: TreeIntrinsicDeclaration, val args: List<Any>) : TreeElement() {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        macro.macro(tokens, args, builder)
    }
}
class TreeVariableDeclaration(val variables: List<DLocal>, val objects: List<TreeObject>?) : TreeElement() {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        variables.forEachIndexed { i, local ->
            val obj = objects?.get(i)
            local.vLocal = builder.alloca(local.type.ir())
            if (obj != null) {
                builder.store(local.vLocal, obj.ir(builder))
                increment(builder, local.vLocal, local.type)
            } else {
                val default = local.type.default(builder)
                if (default != null)
                    builder.store(local.vLocal, default)
            }
        }
    }
}
class TreeSet(val storages: List<TreeStorage>, val objects: List<TreeObject>) : TreeElement() {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        storages.zip(objects).forEach { (storage, obj) ->
            val s = storage.ir(builder)
            decrement(builder, s, storage.type)
            builder.store(s, obj.ir(builder))
            increment(builder, s, storage.type)
        }
    }
}
class TreeFuncCall(val func: TreeObject, val args: List<TreeObject>) : TreeElement() {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        val t = func.type as DTFunc
        val args = when {
            func is TreeVariableUse && func.receiver != null -> listOf(func.receiver) + args
            func is TreeMember -> listOf(func.obj) + args
            else -> args
        }
        process(builder, t.signature() to func.ir(builder), t.returnType, args.map { it.ir(builder) })
    }

    companion object {
        fun process(
            builder: IRBuilder.FunctionBuilder,
            func: Pair<FuncSignature, VValue>,
            returnType: DType,
            args: List<VValue>
        ): Pair<VValue, List<DLocal>>? {
            val o = builder.callFunc(func.first, func.second, *args.toTypedArray())?:return null
            return o to returnType.createVars(builder).also { returnType.storeInVars(builder, o, it) }
        }
    }
}
class TreeNullAssert(val tokens: TokenLine, val obj: TreeObject) : TreeElement() {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        val o = obj.ir(builder)
        builder.callFunc(func_null_assert.first, func_null_assert.second, o, VString(formatLine(RED, tokens.line())))
    }
}
class TreeScope : TreeElement() {
    val block = Block()
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        blocks += block
        block.elements.forEach {
            it.ir(builder)
        }

        block.variables.forEach { v ->
            decrement(builder, v.ir(builder), v.type)
        }
        blocks.pop()
    }
}
abstract class TreeLoop(val label: Token.Name?) : TreeElement() {
    var end = 0
    var gotoContinue = 0
    lateinit var block: Block

    companion object {
        val loops = Stack<TreeLoop>()
    }
}
class TreeWhile(val condition: TreeObject) : TreeLoop(null) {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        loops += this
        val start = builder.register++
        gotoContinue = builder.register++
        end = builder.register++

        builder.branch(".$gotoContinue")
        builder.label(".$start")

        blocks += block
        block.elements.forEach {
            it.ir(builder)
        }
        block.variables.forEach { v ->
            decrement(builder, v.ir(builder), v.type)
        }
        blocks.pop()
        builder.branch(".$gotoContinue")
        builder.label(".$gotoContinue")
        builder.branch(condition.ir(builder), ".$start", ".$end")
        builder.label(".$end")

        loops.pop()
    }
}
class TreeFor(val pre: TreeElement?, val condition: TreeObject, val post: TreeElement?, val outerBlock: Block) : TreeLoop(null) {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        loops += this

        blocks += outerBlock
        pre?.ir(builder)

        val start = builder.register++
        val firstCondition = builder.register++
        gotoContinue = builder.register++
        end = builder.register++

        builder.branch(".$firstCondition")
        builder.label(".$start")

        blocks += block
        block.elements.forEach {
            it.ir(builder)
        }
        block.variables.forEach { v ->
            decrement(builder, v.ir(builder), v.type)
        }
        blocks.pop()

        loops.pop()


        builder.branch(".$gotoContinue")
        builder.label(".$gotoContinue")
        post?.ir(builder)
        builder.branch(".$firstCondition")
        builder.label(".$firstCondition")
        builder.branch(condition.ir(builder), ".$start", ".$end")
        builder.label(".$end")

        outerBlock.variables.forEach { v ->
            decrement(builder, v.ir(builder), v.type)
        }
        blocks.pop()
    }
}
class TreeContinue(val token: Token.Continue) : TreeElement() {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        if(TreeLoop.loops.isEmpty())
            illegal("'continue' only allowed inside of loops", token.line)

        val loop = TreeLoop.loops.peek()
        val blocks = blocks.takeLastWhile { it !== loop.block } + loop.block
        for(i in blocks.size - 1 downTo 0) {
            blocks[i].variables.forEach { v ->
                decrement(builder, v.ir(builder), v.type)
            }
        }

        builder.branch(".${loop.gotoContinue}")
    }
}
class TreeBreak(val token: Token.Break) : TreeElement() {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        if(TreeLoop.loops.isEmpty())
            illegal("'break' only allowed inside of loops", token.line)

        val loop = TreeLoop.loops.peek()
        val blocks = blocks.takeLastWhile { it !== loop.block } + loop.block
        for(i in blocks.size - 1 downTo 0) {
            blocks[i].variables.forEach { v ->
                decrement(builder, v.ir(builder), v.type)
            }
        }

        builder.branch(".${loop.end}")
    }
}
class TreeIncrease(val storage: TreeStorage) : TreeElement() {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        val s = storage.ir(builder)
        val v = builder.load(storage.type.ir(), s)
        builder.store(s, builder.add(v, VInt(v.type as TInt, 1)))
    }
}
class TreeCalculationAssign(val operator: Token, val storage: TreeStorage, val obj: TreeObject) : TreeElement() {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        val s = storage.ir(builder)
        val v = builder.load(storage.type.ir(), s)
        val o = obj.ir(builder)
        builder.store(s, ASTBinaryOperator.Operator.get(operator.javaClass.simpleName.substringAfterLast('.')).irFunc(builder, v, o, obj.type))
    }
}
class TreeIf(val ifs: List<Pair<TreeObject, Block>>, val elseE: Block?) : TreeElement() {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        val e = builder.register++
        ifs.forEachIndexed { i, (condition, block) ->
            val y = builder.register++
            val n = if(i + 1 == ifs.size && elseE == null) e else builder.register++
            builder.branch(condition.ir(builder), ".$y", ".$n")
            builder.label(".$y")
            blocks += block
            block.elements.forEach { it.ir(builder) }
            blocks.pop()
            block.variables.forEach { v ->
                decrement(builder, v.ir(builder), v.type)
            }
            builder.branch(".$e")
            builder.label(".$n")
        }
        if(elseE != null) {
            blocks += elseE
            elseE.elements.forEach { it.ir(builder) }
            blocks.pop()
            elseE.variables.forEach { v ->
                decrement(builder, v.ir(builder), v.type)
            }
            builder.branch(".$e")
            builder.label(".$e")
        }
    }
}



abstract class TreeObject(val tokens: TokenLine, val type: DType) {
    private var value: VValue? = null
    fun ir(builder: IRBuilder.FunctionBuilder) = value?:_ir(builder).also { value = it }
    abstract fun _ir(builder: IRBuilder.FunctionBuilder): VValue
}
class TreeIntLiteral(tokens: TokenLine, val value: Long, type: DTInt) : TreeObject(tokens, type) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) = VInt(type.ir() as TInt, value)
}
class TreeStringLiteral(tokens: TokenLine, val value: String) : TreeObject(tokens, DTArray(DTI8)) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val v = TreeFuncCall.process(
            builder,
            func_String_constructor,
            type,
            listOf(VInt(tI32, value.length.toLong()), VString(value))
        )!!

        builder.callFunc(func_inc.first, func_inc.second, v.second.single().vLocal)
        return v.first
    }
}
class TreeBoolLiteral(tokens: TokenLine, val value: Boolean) : TreeObject(tokens, DTBool) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) = VInt(tI1, if(value) 1 else 0)
}
class TreeCast(tokens: TokenLine, val obj: TreeObject, to: DType) : TreeObject(tokens, to) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        return when {
            obj.type is DTInt && type is DTInt -> {
                if(obj.type.bits < type.bits) builder.sext(obj.ir(builder), type.ir())
                else builder.trunc(obj.ir(builder), type.ir())
            }
            obj.type is DTClass && type is DTClass -> {
                val o = obj.ir(builder)
                builder.callFunc(
                    func_is_subclass_assert.first,
                    func_is_subclass_assert.second,
                    o,
                    VInt(tI32, type.number.toLong()),
                    classInheritances,
                    classNames,
                    VString(formatLine(RED, tokens.line()))
                )
                o
            }
            else -> obj.ir(builder)
        }
    }
}
class TreeIs(tokens: TokenLine, val obj: TreeObject, val to: DTClass) : TreeObject(tokens, DTBool) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.callFunc(
            func_is_subclass.first,
            func_is_subclass.second,
            obj.ir(builder),
            VInt(tI32, to.number.toLong()),
            classInheritances
        )!!
    }
}
class TreeVariableUse(tokens: TokenLine, val variable: DVariable, val receiver: TreeObject? = null) : TreeObject(
    tokens,
    blocks.lastOrNull()?.variableInformation?.get(variable)?.smartCastType?:variable.type
) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) = if(variable is DGlobal && variable.function) variable.ir(builder) else builder.load(type.ir(), variable.ir(builder))
}
class TreeIntrinsicUseO(tokens: TokenLine, val macro: TreeIntrinsicDeclaration, val args: List<Any>) : TreeObject(
    tokens,
    macro.returnType
) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        return macro.macro(tokens, args, builder)!!
    }
}
class TreeFuncCallO(tokens: TokenLine, val func: TreeObject, val args: List<TreeObject>) : TreeObject(
    tokens,
    (func.type as DTFunc).returnType
) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val t = func.type as DTFunc
        val args = if(func is TreeVariableUse && func.receiver != null) listOf(func.receiver) + args else args
        return TreeFuncCall.process(
            builder,
            t.signature() to func.ir(builder),
            t.returnType,
            args.map { it.ir(builder) })!!.first
    }
}
class TreeInitializedArray(tokens: TokenLine, val arrayType: DType, val objects: List<TreeObject>) : TreeObject(
    tokens,
    DTArray(arrayType)
) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val v = TreeFuncCall.process(
            builder,
            func_Array_constructor,
            type,
            listOf(
                VInt(tI32, objects.size.toLong()),
                VInt(tI64, arrayType.ir().size()),
                arrayType.destroyFunction()
            )
        )!!
        builder.callFunc(func_inc.first, func_inc.second, v.second.single().vLocal)

        val arr = builder.load(TPtr, builder.elementPtr(struct_Array, v.first, VInt(tI32, 0), VInt(tI32, 2)))

        objects.forEachIndexed { i, o ->
            val ptr = builder.elementPtr(TArray(arrayType.ir(), 1), arr, VInt(tI64, i.toLong()))
            builder.store(ptr, o.ir(builder))
            if(arrayType.ir() is TPtr)
                builder.callFunc(func_inc.first, func_inc.second, ptr)
        }

        return v.first
    }
}
class TreeGenerateArray(tokens: TokenLine, val func: TreeObject, val size: TreeObject, val of: DType) : TreeObject(tokens, DTArray(of)) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val size = size.ir(builder)
        val arr = TreeFuncCall.process(builder, func_Array_constructor2, type, listOf(size, VInt(tI64, of.ir().size()), of.destroyFunction(), VString(
            formatLine(RED, this.size.tokens.line())
        )))!!

        builder.callFunc(func_inc.first, func_inc.second, arr.second.single().vLocal)


        val arrV = builder.load(TPtr, builder.elementPtr(struct_Array, arr.first, VInt(tI32, 0), VInt(tI32, 2)))
        val i = builder.alloca(tI32)
        builder.store(i, VInt(tI32, 0))
        val condition = builder.register
        val func = func.ir(builder)
        builder.branch(".$condition")
        builder.label(".$condition")
        val start = builder.register
        val end = builder.register
        val iv = builder.load(tI32, i)
        val less = builder.icmp("ult", iv, size)
        builder.branch(less, ".$start", ".$end")
        builder.label(".$start")
        val e = builder.callFunc((this.func.type as DTFunc).signature(), func, iv)!!
        builder.store(builder.elementPtr(TArray(of.ir(), 1), arrV, builder.zext(iv, tI64)), e)
        builder.store(i, builder.add(iv, VInt(tI32, 1)))
        builder.branch(".$condition")
        builder.label(".$end")
        return arr.first
    }
}
class TreeGet(tokens: TokenLine, val obj: TreeObject, val index: TreeObject) : TreeObject(
    tokens,
    (obj.type as DTArray).of
) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val o = obj.ir(builder)
        val t = obj.type as DTArray
        val v = builder.callFunc(
            func_Array_get.first,
            func_Array_get.second,
            o,
            VInt(tI32, t.of.ir().size()),
            index.ir(builder),
            VString(formatLine(RED, index.tokens.line()))
        )!!
        return builder.load(t.of.ir(), v)
    }
}
class TreeNull(tokens: TokenLine, type: DTNullable) : TreeObject(tokens, type) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) = VNull
}
class TreeNullAssertO(tokens: TokenLine, val obj: TreeObject) : TreeObject(tokens, (obj.type as DTNullable).of) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val o = obj.ir(builder)
        builder.callFunc(func_null_assert.first, func_null_assert.second, o, VString(formatLine(RED, tokens.line())))
        return o
    }
}
class TreeScopeO(tokens: TokenLine, val block: Block, val obj: TreeObject) : TreeObject(tokens, obj.type) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        blocks += block


        block.elements.forEach {
            it.ir(builder)
        }

        val o = obj.ir(builder)

        blocks.pop()
        obj.type.createVars(builder).also { obj.type.storeInVars(builder, o, it) }
        blocks += block

        incrementD(builder, o, type)

        block.variables.forEach { v ->
            decrement(builder, v.ir(builder), v.type)
        }
        blocks.pop()
        return o
    }
}
class TreeNew(tokens: TokenLine, val initializer: TreeFuncDefinition, val args: List<TreeObject>) : TreeObject(
    tokens,
    initializer.constructorOf!!
) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val v = TreeFuncCall.process(
            builder,
            (initializer.global.type as DTFunc).signature() to initializer.global.ir(builder),
            type,
            args.map { it.ir(builder) })!!
        builder.callFunc(func_inc.first, func_inc.second, v.second.single().vLocal)
        return v.first
    }
}
class TreeMember(tokens: TokenLine, val obj: TreeObject, val index: Int) : TreeObject(
    tokens,
    (obj.type as DTClass).properties[index].second
) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val o = obj.ir(builder)
        return builder.load(type.ir(), builder.elementPtr((obj.type as DTClass).struct(), o, VInt(tI32, 0), VInt(tI32, index + DTClass.INTERNAL_VALUES)))
    }
}
class TreeBinaryOperator(
    tokens: TokenLine,
    val left: TreeObject,
    val right: TreeObject,
    type: DType,
    val func: ((IRBuilder.FunctionBuilder, TreeObject, TreeObject, DType) -> VValue)
) : TreeObject(tokens, type) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        return func(builder, left, right, left.type)
    }
}
class TreeIncreaseO(tokens: TokenLine, val storage: TreeStorage) : TreeObject(tokens, storage.type) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val s = storage.ir(builder)
        val v = builder.load(storage.type.ir(), s)
        builder.store(s, builder.add(v, VInt(v.type as TInt, 1)))
        return v
    }
}
class TreeSign(tokens: TokenLine, val sign: Token.Sign, val obj: TreeObject) : TreeObject(tokens, obj.type) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val o = obj.ir(builder)
        if(sign.c == '+') return o
        return builder.multiply(o, VInt(o.type as TInt, -1))
    }
}
class TreeThis(tokens: TokenLine) : TreeObject(tokens, openFuncDefinitions.peek().let { it.inClass?:it.constructorOf!! }) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) = openFuncDefinitions.peek().receiver!!
}
class TreeIfO(tokens: TokenLine, val ifs: List<Triple<TreeObject, Block, TreeObject>>, val elseE: Pair<Block, TreeObject>) : TreeObject(tokens, elseE.second.type) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val e = builder.register++

        val res = mutableListOf<Pair<VValue, String>>()
        var lastN = ""

        val vars = type.default(builder)?.let { type.createVars(builder).also { v -> type.storeInVars(builder, it, v) } }

        ifs.forEachIndexed { i, (condition, block, obj) ->
            val y = builder.register++
            val n = builder.register++
            lastN = ".$n"
            builder.branch(condition.ir(builder), ".$y", ".$n")
            builder.label(".$y")
            blocks += block
            block.elements.forEach { it.ir(builder) }

            val o = obj.ir(builder)
            if(vars != null)
                obj.type.storeInVars(builder, o, vars)

            res += o to ".$y"
            incrementD(builder, o, elseE.second.type)

            blocks.pop()
            block.variables.forEach { v ->
                decrement(builder, v.ir(builder), v.type)
            }
            builder.branch(".$e")
            builder.label(".$n")
        }
        blocks += elseE.first
        elseE.first.elements.forEach { it.ir(builder) }

        val o = elseE.second.ir(builder)
        if(vars != null)
            elseE.second.type.storeInVars(builder, o, vars)

        res += o to lastN
        incrementD(builder, o, elseE.second.type)


        blocks.pop()
        elseE.first.variables.forEach { v ->
            decrement(builder, v.ir(builder), v.type)
        }
        builder.branch(".$e")
        builder.label(".$e")

        return builder.phi(type.ir(), *res.toTypedArray())
    }
}
class TreeTuple(tokens: TokenLine, val objects: List<Pair<Token.Name, TreeObject>>) : TreeObject(tokens, DTTuple(objects.map { (n, t) -> n to t.type })) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        var res: VValue = VUndef(type.ir())
        objects.forEachIndexed { i, (_, o) ->
            res = builder.insertValue(res, o.ir(builder), i)
        }
        return res
    }
}
class TreeTupleMember(tokens: TokenLine, val obj: TreeObject, val index: Int) : TreeObject(
    tokens,
    (obj.type as DTTuple).elements[index].second
) {
    override fun _ir(builder: IRBuilder.FunctionBuilder): VValue {
        val o = obj.ir(builder)
        return builder.extractValue(o, index)
    }
}




abstract class TreeStorage(val tokens: TokenLine, val type: DType, val isInitialized: Boolean) {
    abstract fun ir(builder: IRBuilder.FunctionBuilder): VValue
    abstract fun initialize(obj: TreeObject)
}
class TreeSVariableUse(tokens: TokenLine, val variable: DVariable) : TreeStorage(
    tokens,
    variable.type,
    blocks.peek().variableInformation[variable]?.isInitialized != false
) {
    override fun ir(builder: IRBuilder.FunctionBuilder) = variable.ir(builder)
    override fun initialize(obj: TreeObject) {
        blocks.peek().variableInformation[variable]!!.isInitialized = true
        blocks.peek().variableInformation[variable]!!.smartCastType = obj.type
    }
}
class TreeSMember(tokens: TokenLine, val obj: TreeObject, val index: Int) : TreeStorage(tokens, (obj.type as DTClass).properties[index].second, true) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val o = obj.ir(builder)
        return builder.elementPtr((obj.type as DTClass).struct(), o, VInt(tI32, 0), VInt(tI32, index + DTClass.INTERNAL_VALUES))
    }

    override fun initialize(obj: TreeObject) {}
}

fun increment(builder: IRBuilder.FunctionBuilder, v: VValue, type: DType) {
    when (type) {
        is DTComplexType -> builder.callFunc(func_inc.first, func_inc.second, v)
        is DTTuple -> {
            type.elements.forEachIndexed { i, it ->
                val ptr = builder.elementPtr(type.ir(), v, VInt(tI32, 0), VInt(tI32, i.toLong()))
                increment(builder, ptr, it.second)
            }
        }
    }
}

fun incrementD(builder: IRBuilder.FunctionBuilder, v: VValue, type: DType) {
    when (type) {
        is DTComplexType -> builder.callFunc(func_inc_d.first, func_inc_d.second, v)
        is DTTuple -> {
            type.elements.forEachIndexed { i, it ->
                incrementD(builder, builder.extractValue(v, i), it.second)
            }
        }
    }
}
fun decrement(builder: IRBuilder.FunctionBuilder, v: VValue, type: DType) {
    when (type) {
        is DTComplexType -> builder.callFunc(func_dec.first, func_dec.second, v, type.destructor(builder, builder.load(type.ir(), v)))
        is DTTuple -> {
            type.elements.forEachIndexed { i, it ->
                val ptr = builder.elementPtr(type.ir(), v, VInt(tI32, 0), VInt(tI32, i.toLong()))
                decrement(builder, ptr, it.second)
            }
        }
    }
}