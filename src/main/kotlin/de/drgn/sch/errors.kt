package de.drgn.sch

import kotlin.system.exitProcess

val RESET = Ansi(0)
val RED = Ansi(31)
val UNDERLINED = Ansi(4)
val CYAN = Ansi(36)
val YELLOW = Ansi(33)

class Ansi(vararg val attributes: Int) {
    override fun toString() = "\u001b[${attributes.joinToString(";") { it.toString() }}m"

    operator fun plus(b: Ansi) = Ansi(*(attributes + b.attributes))
}

fun illegal(reason: String, vararg errorLine: Line): Nothing {
    println("${RED}Error: $RESET$reason")
    printLine(RED, Line(errorLine[0].file, errorLine.flatMap { it.l }))
    throw Exception()
    //exitProcess(1)
}
fun illegalTodo(errorLine: Line): Nothing {
    println("${Ansi(35)}TODO: $RESET")
    printLine(Ansi(35), errorLine)
    throw Exception()
    //exitProcess(1)
}
fun illegalIntrinsic(reason: String, errorLine: Line): Nothing {
    println("${RED}Error in intrinsic function: $RESET$reason")
    printLine(RED, errorLine)
    throw Exception()
    //exitProcess(1)
}
fun debug(reason: String, errorLine: Line): Nothing {
    println("${RED}DEBUG ERROR: $RESET$reason")
    printLine(RED, errorLine)
    throw Exception()
    //exitProcess(1)
}
//fun illegal(reason: String, errorC: C): Nothing = illegal(reason, Line(listOf(errorC)))

fun illegal(reason: String): Nothing {
    println("${RED}Error: $RESET$reason")
    throw Exception()
    exitProcess(1)
}
fun warning(reason: String, errorLine: Line) {
    println("${YELLOW}Warning: $RESET$reason")
    printLine(YELLOW, errorLine)
}
//fun illegalWrongType(line: Line, expected: Type, found: Type): Nothing = illegal("Expected '$expected' but found '$found'", line)

fun printLine(color: Ansi, errorLine: Line) {
    print(formatLine(color, errorLine))
}
fun formatLine(color: Ansi, errorLine: Line): String {
    val linesPerFile = errorLine.l.groupBy { it.file!! }

    val errorLines = mutableListOf<Triple<Int, String, String>>()

    linesPerFile.forEach { (file, errorLine) ->
        val fileLines = file.file.readLines().toMutableList()

        val lines = errorLine.map { it.line }.toSet()

        errorLines += lines.map { line ->
            val sb = StringBuilder()

            if(fileLines.size <= line) fileLines += ""

            var isWhite = 0
            fileLines[line].forEachIndexed { i, c ->
                if(isWhite != -1) {
                    if (c.isWhitespace()) {
                        if(c == '\t' || ++isWhite == 4) {
                            isWhite = 0
                            sb.append("    ")
                        }
                        return@forEachIndexed
                    }
                    isWhite = -1
                }
                else if(c == '\t') sb.append(" ".repeat(4 - i % 4))
                if(errorLine.any { it.line == line && it.index == i }) {
                    if(!c.isWhitespace()) sb.append("$color$c$RESET")
                    else if(errorLine.size == 1) sb.append("${color + UNDERLINED}$c$RESET")
                } else if(c != '\t') sb.append(c)
            }
            if(errorLine.size == 1 && errorLine[0].line == line && errorLine[0].index == fileLines[line].length) {
                sb.append("$color$UNDERLINED $RESET")
            }
            Triple(line, sb.toString().trimEnd(), "${file.file}:${line + 1}")
        }
    }

    val trim = errorLines.minOf { it.second.takeWhile { it.isWhitespace() }.length }
    val add = errorLines.maxOf { it.third.length }
    return errorLines.joinToString("\n", postfix = "\n") { "$CYAN${it.third} ${" ".repeat(add - it.third.length)}$RESET${it.second.drop(trim)}" }
}