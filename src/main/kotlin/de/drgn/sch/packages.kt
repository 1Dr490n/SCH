package de.drgn.sch

import java.io.File

data class DFile(val file: File) {
    lateinit var pckg: DPackage
    val imports = mutableListOf<DPackage>()
}
class DPackage(val name: String) {
    init {
        packages += this
    }

    val globals = mutableListOf<DGlobal>()
    val intrinsics = mutableListOf<TreeIntrinsicDeclaration>()
    val classes = mutableListOf<DTClass>()

    override fun toString() = name

    companion object {
        val packages = mutableListOf<DPackage>()
        operator fun get(name: String) = packages.find { it.name == name }
    }
}