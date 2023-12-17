package de.drgn.sch

import java.io.File

data class Line(val file: DFile, val l: List<C>) {
    constructor(file: DFile, c: C) : this(file, listOf(c))

    init {
    	l.forEach { if(it.file == null) it.file = file }
    }
}
data class C(val line: Int, val index: Int) {
    var file: DFile? = null
}

val emptyLine = Line(DFile(File("")), emptyList())