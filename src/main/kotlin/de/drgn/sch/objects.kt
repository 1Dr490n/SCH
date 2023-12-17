package de.drgn.sch

import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.tI32
import de.drgn.irbuilder.values.VGlobal
import de.drgn.irbuilder.values.VInt
import de.drgn.irbuilder.values.VLocal
import de.drgn.irbuilder.values.VValue
import java.util.*

class Block {
    val elements = mutableListOf<TreeElement>()
    val variables = mutableListOf<DVariable>()
    var variableInformation = mutableMapOf<DVariable, VariableInformation>()

    var returned = false
}
val blocks = Stack<Block>()

class VariableInformation(val v: DVariable, var isInitialized: Boolean, smartCastType: DType) {
    var smartCastType = v.type
        set(value) {
            if(!field.isType(value) || !value.isType(field))
                field = value
        }

    init {
    	this.smartCastType = smartCastType
    }

    fun copy() = VariableInformation(v, isInitialized, smartCastType)
}

abstract class DVariable(val name: Token.Name, val type: DType, val constant: Boolean) {
    abstract fun ir(builder: IRBuilder.FunctionBuilder?): VValue
}
class DGlobal(
    val pckg: DPackage,
    name: Token.Name,
    type: DType,
    constant: Boolean,
    irName: String? = null,
    val function: Boolean = false,
    val global: Boolean = true
) : DVariable(name, type, constant) {
    val irName = irName?:"\"$pckg::$name\""
    init {
        if(global) {
            pckg.globals.find {
                it !== this && it.name.name == name.name
            }?.let {
                illegal("Variable declared twice", it.name.line, name.line)
            }
        }
    }

    override fun ir(builder: IRBuilder.FunctionBuilder?) = VGlobal(irName)
}
class DReceiverMember(name: Token.Name, type: DType, val classType: DTClass, val index: Int, val function: TreeFuncDefinition?) : DVariable(name, type, false) {
    override fun ir(builder: IRBuilder.FunctionBuilder?): VValue {
        return builder!!.elementPtr(classType.struct(), openFuncDefinitions.peek().receiver!!, VInt(tI32, 0), VInt(tI32, index.toLong()))
    }
}
class DLocal(name: Token.Name, type: DType, constant: Boolean) : DVariable(name, type, constant) {
    lateinit var vLocal: VLocal
    override fun ir(builder: IRBuilder.FunctionBuilder?) = vLocal
}

fun getIntrinsic(name: Token.Intrinsic): TreeIntrinsicDeclaration {
    DPackage["std"]?.intrinsics?.find { it.name.name == name.name }?.let {
        return it
    }
    illegal("Intrinsic doesn't exist", name.line)
}
fun getVariable(name: Token.Name): DVariable {
    if(name is Token.Namespace) {
        val pckg = DPackage[name.namespace] ?: illegal("Package doesn't exist", name.namespaceLine)
        return pckg.globals.find { it.name.name == name.name } ?: illegal("Variable doesn't exist", name.line)
    }
    for(i in blocks.size - 1 downTo 0) {
        val v = blocks[i].variables.find { it.name.name == name.name }
        if(v != null) return v
    }
    val inPackage = name.line.file.pckg.globals.find { it.name.name == name.name }
    if(inPackage != null) return inPackage

    val matches = name.line.file.imports.map {
        it.globals.find { it.name.name == name.name }
    }.filterNotNull()

    if(matches.size > 1)
        illegal("Multiple variables match", name.line, *matches.map { it.name.line }.toTypedArray())

    illegal("Variable doesn't exist", name.line)
}