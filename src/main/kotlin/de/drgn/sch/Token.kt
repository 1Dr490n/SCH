package de.drgn.sch

abstract class Token(val line: Line, val charForm: Char, val isObject: Boolean) {

    abstract class OpenBracket(line: Line, char: Char) : Token(line, char, false)
    abstract class CloseBracket(line: Line, char: Char) : Token(line, char, true)

    class Function(line: Line) : Token(line, 'f', false)
    open class Name(line: Line, val name: String) : Token(line, 'n', true) {
        override fun toString() = name
    }
    class IntLiteral(line: Line, val value: String) : Token(line, 'i', true) {
        override fun toString() = "$charForm $value"
    }
    class StringLiteral(line: Line, val string: String) : Token(line, '"', true) {
        override fun toString() = "$charForm \"$string\""
    }
    class BoolLiteral(line: Line, val value: Boolean) : Token(line, 'b', true) {
        override fun toString() = "$charForm $value"
    }
    class OpenRound(line: Line) : OpenBracket(line, '(')
    class CloseRound(line: Line) : CloseBracket(line, ')')
    class OpenSquare(line: Line) : OpenBracket(line, '[')
    class CloseSquare(line: Line) : CloseBracket(line, ']')
    class OpenCurly(line: Line) : OpenBracket(line, '{')
    class CloseCurly(line: Line) : CloseBracket(line, '}')
    class Intrinsic(line: Line, val name: String) : Token(line, 'm', false) {
        override fun toString() = "$charForm $name!"
    }
    class NewLine(line: Line) : Token(line, '\\', false)
    class Colon(line: Line) : Token(line, ':', false)
    class Comma(line: Line) : Token(line, ',', false)
    class Vararg(line: Line) : Token(line, '^', false)
    class Return(line: Line) : Token(line, 'r', false)
    class Package(line: Line) : Token(line, 'p', false)
    class Import(line: Line) : Token(line, '§', false)
    class MacroArgument(line: Line) : Token(line, '#', false)
    class Variable(line: Line) : Token(line, 'v', false)
    class SetEqual(line: Line) : Token(line, '←', false)
    class New(line: Line) : Token(line, '★', false)
    class Null(line: Line) : Token(line, '0', true)
    class Nullable(line: Line) : Token(line, '?', false)
    class NullAssert(line: Line) : Token(line, '!', true)
    class Class(line: Line) : Token(line, 'c', false)
    class Constructor(line: Line) : Token(line, 'å', false)
    class Dot(line: Line) : Token(line, '.', false)
    class LessThan(line: Line) : Token(line, '<', false)
    class LessEqual(line: Line) : Token(line, '≤', false)
    class Equals(line: Line) : Token(line, '=', false)
    class Unequal(line: Line) : Token(line, '≠', false)
    class GreaterEqual(line: Line) : Token(line, '≥', false)
    class GreaterThan(line: Line) : Token(line, '>', false)
    class While(line: Line) : Token(line, 'w', false)
    class If(line: Line) : Token(line, '╗', false)
    class Else(line: Line) : Token(line, '╝', false)
    class Increase(line: Line) : Token(line, '↑', true)
    class Decrease(line: Line) : Token(line, '↓', true)
    class Plus(line: Line) : Token(line, '+', false)
    class Minus(line: Line) : Token(line, '-', false)
    class Multiply(line: Line) : Token(line, '*', false)
    class Divide(line: Line) : Token(line, '/', false)
    class Modulo(line: Line) : Token(line, '%', false)
    class Sign(line: Line, val c: Char) : Token(line, '\'', false)
    class This(line: Line) : Token(line, 't', true)
    class Virtual(line: Line) : Token(line, '€', false)
    class Override(line: Line) : Token(line, 'ó', false)
    class Is(line: Line) : Token(line, '~', false)
    class Semicolon(line: Line) : Token(line, ';', false)
    class For(line: Line) : Token(line, '┬', false)
    class And(line: Line) : Token(line, '&', false)
    class Or(line: Line) : Token(line, '|', false)
    class Lambda(line: Line) : Token(line, 'λ', false)
    class Continue(line: Line) : Token(line, '→', false)
    class Break(line: Line) : Token(line, '¦', false)

    class Namespace(line: Line, val namespaceLine: Line, val namespace: String, name: String) : Name(line, name)

    class Comment(line: Line) : Token(line, '☺', false)

    override fun toString() = charForm.toString()
}

typealias TokenLine = List<Token>

fun TokenLine.getOperators(u: (Token) -> Boolean): Triple<Token, TokenLine, TokenLine>? {
    var last: Triple<Token, TokenLine, TokenLine>? = null
    var brackets = 0

    forEachIndexed { i, token ->
        when {
            token is Token.OpenBracket -> brackets++
            token is Token.CloseBracket -> brackets--
            u(token) && brackets == 0 -> {
                last = Triple(token, subList(0, i), drop(i + 1))
            }
        }
    }
    return last
}
fun TokenLine.line() = Line(first().line.file, flatMap { it.line.l })

inline fun<reified T : Token> TokenLine.splitBrackets(keepSeparator: Boolean = false): List<TokenLine> {
    val res = mutableListOf<TokenLine>()

    var start = 0
    var brackets = 0

    forEachIndexed { i, token ->
        when {
            token is Token.OpenBracket -> brackets++
            token is Token.CloseBracket -> brackets--
            token is T && brackets == 0 -> {
                res += subList(start, i)
                start = if(keepSeparator) i else i + 1
            }
        }
    }
    res += drop(start)
    return res.takeUnless { it.size == 1 && it[0].isEmpty() }?: emptyList()
}
fun TokenLine.beforeBrackets(): Pair<TokenLine, TokenLine>? {
    if (size < 2 || last() !is Token.CloseBracket) return null
    var brackets = 0

    for(i in size - 1 downTo 0) {
        when (get(i)) {
            is Token.OpenBracket -> {
                if(--brackets == 0)
                    return subList(0, i) to drop(i)
            }
            is Token.CloseBracket -> brackets++
        }
    }
    return null
}
fun TokenLine.afterBrackets(): Pair<TokenLine, TokenLine>? {
    if (size < 2 || first() !is Token.OpenBracket) return null
    var brackets = 0

    for(i in indices) {
        when (get(i)) {
            is Token.CloseBracket -> {
                if(--brackets == 0)
                    return subList(0, i + 1) to drop(i + 1)
            }
            is Token.OpenBracket -> brackets++
        }
    }
    return null
}
inline fun<reified T : Token> TokenLine.getFirstOperator(): Triple<T, TokenLine, TokenLine>? {
    var brackets = 0

    forEachIndexed { i, token ->
        when {
            token is Token.OpenBracket -> brackets++
            token is Token.CloseBracket -> brackets--
            brackets == 0 && token is T -> {
                return Triple(token, subList(0, i), drop(i + 1))
            }
        }
    }
    return null
}

fun TokenLine.groupBrackets(): List<TokenLine> {
    var brackets = 0
    val res = mutableListOf<TokenLine>()
    var last = 0
    forEachIndexed { i, token ->
        when {
            token is Token.OpenBracket -> brackets++
            token is Token.CloseBracket -> {
                if(--brackets == 0) {
                    res += subList(last, i + 1)
                    last = i + 1
                }
            }
        }
    }
    return res
}