package de.drgn.sch

import de.drgn.irbuilder.FuncSignature
import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.*
import de.drgn.irbuilder.values.VInt
import de.drgn.irbuilder.values.VNull
import de.drgn.irbuilder.values.VUndef
import de.drgn.irbuilder.values.VValue

abstract class DType {
    abstract fun ir(): TType
    abstract override fun toString(): String
    open fun destroyFunction(): VValue {
        return VNull
    }
    fun isType(other: DType): Boolean {
        if(this === other) return true
        if(other is DTNullable && isType(other.of)) return true
        return _isType(other)
    }
    open fun _isType(other: DType) = toString() == other.toString()

    open fun default(builder: IRBuilder.FunctionBuilder): VValue? = null
    abstract fun undefined(): VValue
    open fun createVars(builder: IRBuilder.FunctionBuilder): List<DLocal> {
        return emptyList()
    }
    open fun storeInVars(builder: IRBuilder.FunctionBuilder, obj: VValue, vars: List<DLocal>) = 0
}
object DTVoid : DType() {
    override fun ir() = TVoid
    override fun toString() = "void"
    override fun undefined() = TODO()
}
object DTBool : DType() {
    override fun ir() = tI1
    override fun toString() = "bool"
    override fun undefined() = VInt(tI1, 0)
}
abstract class DTInt(val signed: Boolean, val bits: Int, val ir: TInt) : DType() {
    override fun ir() = ir
    override fun toString() = "${if(signed) 'i' else 'u'}$bits"
    override fun undefined() = VInt(ir, 0)
}
object DTI8 : DTInt(true, 8, tI8)
object DTI16 : DTInt(true, 16, tI16)
object DTI32 : DTInt(true, 32, tI32)
object DTI64 : DTInt(true, 64, tI64)
object DTU8 : DTInt(false, 8, tI8)
object DTU16 : DTInt(false, 16, tI16)
object DTU32 : DTInt(false, 32, tI32)
object DTU64 : DTInt(false, 64, tI64)

abstract class DTComplexType : DType() {
    override fun ir() = TPtr
    abstract fun destructor(builder: IRBuilder.FunctionBuilder, obj: VValue): VValue
    override fun default(builder: IRBuilder.FunctionBuilder) = VNull
    override fun createVars(builder: IRBuilder.FunctionBuilder): List<DLocal> {
        val local = DLocal(Token.Name(emptyLine, ""), this, true)
        blocks.peek().variables += local
        blocks.peek().variableInformation[local] = VariableInformation(local, true, local.type)
        val v = builder.alloca(ir())
        local.vLocal = v
        return listOf(local)
    }

    override fun storeInVars(builder: IRBuilder.FunctionBuilder, obj: VValue, vars: List<DLocal>): Int {
        builder.store(vars[0].vLocal, obj)
        return 1
    }

    override fun undefined() = VNull
}

class DTArray(val of: DType) : DTComplexType() {
    override fun toString() = "$of[]"
    override fun _isType(other: DType): Boolean {
        return other is DTArray && other.of == of
    }
    override fun destructor(builder: IRBuilder.FunctionBuilder, obj: VValue) = func_Array_destructor.second
}
class DTFunc(val args: List<DType>, val isVararg: Boolean, val returnType: DType, val receiver: DType?) : DType() {
    override fun ir() = TPtr
    override fun toString() = "func(${args.let { if(receiver == null) it else listOf(receiver) + it }.joinToString { it.toString() }}): $returnType"
    fun signature() = FuncSignature(returnType.ir(), *args.map { it.ir() }.let { if(receiver == null) it else listOf(receiver.ir()) + it }.toTypedArray(), isVararg = isVararg)
    override fun _isType(other: DType): Boolean {
        if(other !is DTFunc) return false
        return returnType.isType(other.returnType) && args.size == other.args.size && isVararg == other.isVararg && args.zip(other.args).all { it.second.isType(it.first) }
    }

    override fun undefined() = VNull
}
class DTNullable(val of: DTComplexType) : DTComplexType() {
    override fun toString() = "$of?"

    override fun _isType(other: DType): Boolean {
        return other is DTNullable && other.of == of
    }

    override fun destructor(builder: IRBuilder.FunctionBuilder, obj: VValue) = of.destructor(builder, obj)
}

fun isLocalFunction(obj: TreeFuncDefinition?) = obj?.isVirtual == true || obj?.isOverride == true
fun isConstantFunction(obj: TreeFuncDefinition?) = obj?.isVirtual == false && !obj.isOverride

class DTClass(
    val name: Token.Name,
    val constructor: TreeFuncDefinition,
    val destructor: TreeFuncDefinition,
    val functions: List<TreeFuncDefinition>
) : DTComplexType() {
    var superClass: DTClass? = null
    lateinit var properties: List<Triple<Token.Name, DType, TreeFuncDefinition?>>

    override fun toString() = "${name.line.file.pckg}::$name"

    fun struct() = TStruct(tI32, tI32, TPtr, *properties.filter { it.third == null || it.third!!.isVirtual || it.third!!.isOverride }.map { it.second.ir() }.toTypedArray())

    val number = ++numClasses

    override fun destructor(builder: IRBuilder.FunctionBuilder, obj: VValue) =
        builder.load(TPtr, builder.elementPtr(struct(), obj, VInt(tI32, 0), VInt(tI32, 2)))

    override fun _isType(other: DType) = superClass?.isType(other) == true

    init {
    	classes += this
    }

    companion object {
        var numClasses = 0
        const val INTERNAL_VALUES = 3L
        const val POINTERS = 0L
        const val TYPE = 1L
        const val DESTRUCTOR = 2L

        val classes = mutableListOf<DTClass>()
    }
}
class DTTuple(val elements: List<Pair<Token.Name, DType>>): DType() {
    override fun toString() = "(${elements.joinToString { "${it.first.name}: ${it.second}" }})"

    override fun ir() = TStruct(*elements.map { it.second.ir() }.toTypedArray())

    override fun default(builder: IRBuilder.FunctionBuilder): VValue? {
        val defaults = elements.mapNotNull { it.second.default(builder) }
        if(defaults.isEmpty()) return null
        var f: VValue = VUndef(ir())
        defaults.forEachIndexed { i, d ->
            f = builder.insertValue(f, d, i)
        }
        return f
    }

    override fun undefined() = VUndef(ir())

    override fun createVars(builder: IRBuilder.FunctionBuilder): List<DLocal> {
        val vars = mutableListOf<DLocal>()

        elements.forEachIndexed { i, (_, t) ->
            if(t is DTComplexType || t is DTTuple)
                vars += t.createVars(builder)
        }
        return vars
    }

    override fun storeInVars(builder: IRBuilder.FunctionBuilder, obj: VValue, vars: List<DLocal>): Int {
        var j = 0
        elements.forEachIndexed { i, (_, t) ->
            if(t is DTComplexType || t is DTTuple)
                j += t.storeInVars(builder, builder.extractValue(obj, i), vars.drop(j))
        }
        return j
    }

    override fun _isType(other: DType) = other is DTTuple && other.elements.size == elements.size && elements.zip(other.elements).all { it.first.second.isType(it.second.second) }
}

class DTUnknown(val getType: () -> DType) : DType() {
    override fun ir(): TType {
        TODO("Not yet implemented")
    }

    override fun toString(): String {
        TODO("Not yet implemented")
    }

    override fun undefined(): VValue {
        TODO("Not yet implemented")
    }

}