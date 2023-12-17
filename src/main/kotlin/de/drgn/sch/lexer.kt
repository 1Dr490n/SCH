package de.drgn.sch

import java.io.File
import java.util.*

val openBrackets = listOf('(', '[', '{')
val closeBrackets = listOf(')', ']', '}')

val wordCharacters = ('A'..'Z') + ('a'..'z') + ('0'..'9') + '_'

fun lex(file: File, keepComments: Boolean = false): Triple<DFile, MutableList<Token>, ListIterator<TokenLine>> {
    val text = file.readText().replace("\r", "").toList().let {
        if(it.last() != '\n') it + '\n' else it
    }
    val sb = StringBuilder()

    val file = DFile(file)
    val tokens = mutableListOf<Token>()

    var lineNumber = 0
    var charNumber = 0

    val ite = text.listIterator()

    val brackets = Stack<Int>()

    // 0: none
    // 1: line
    // 2: block
    var comment = 0
    val commentC = mutableListOf<C>()

    var waitingNamespace: Pair<List<C>, String>? = null

    for(c in ite) {
        val singleCharLine = Line(file, C(lineNumber, charNumber))
        val next: Pair<Char, Line>? = if(ite.hasNext()) {
            text[ite.nextIndex()] to Line(file, listOf(C(lineNumber, charNumber), C(lineNumber, charNumber + 1)))
        } else null

        /*if(waitingNamespace != null && c !in wordCharacters) {
            illegal("Expected name")
        }*/

        when {
            c == '/' && next?.first == '/' -> {
                commentC += C(lineNumber, charNumber)
                comment = 1
            }
            c == '/' && next?.first == '*' -> {
                commentC += C(lineNumber, charNumber)
                comment = 2
            }
            comment == 1 -> {
                if(c == '\n') {
                    lineNumber++
                    charNumber = -1
                    comment = 0
                    if(keepComments) {
                        tokens += Token.Comment(Line(file, commentC.toList()))
                        commentC.clear()
                    }
                    if(brackets.isEmpty() || openBrackets[brackets.peek()] !in "([") tokens += Token.NewLine(singleCharLine)
                }
                else {
                    commentC += C(lineNumber, charNumber)
                }
            }
            comment == 2 -> {
                if(c != '\n') {
                    if(c == '*' && next?.first == '/') {
                        charNumber++
                        ite.next()
                        comment = 0
                        if(keepComments) {
                            commentC += next.second.l
                            tokens += Token.Comment(Line(file, commentC.toList()))
                            commentC.clear()
                        }
                    }
                    else commentC += C(lineNumber, charNumber)
                }
                else {
                    lineNumber++
                    charNumber = -1
                    if(keepComments) {
                        tokens += Token.Comment(Line(file, commentC.toList()))
                        commentC.clear()
                    }
                }
            }
            sb.isNotEmpty() && c !in wordCharacters -> {
                if (c == '!' && next?.first != '!') {
                    tokens += Token.Intrinsic(
                        Line(file, sb.mapIndexed { i, _ -> C(lineNumber, charNumber - i - 1) } + C(lineNumber, charNumber)),
                        sb.toString()
                    )
                } else if(c == ':' && next?.first == ':') {
                    waitingNamespace = sb.mapIndexed { i, _ ->
                        C(lineNumber, charNumber - i - 1)
                    } + C(lineNumber, charNumber) + C(lineNumber, charNumber + 1) to sb.toString()
                    ite.next()
                    charNumber++
                } else {
                    val str = sb.toString()
                    val line = Line(file, str.mapIndexed { i, _ -> C(lineNumber, charNumber - i - 1) })
                    tokens += when {
                        str == "func" -> Token.Function(line)
                        str == "return" -> Token.Return(line)
                        str == "package" -> Token.Package(line)
                        str == "import" -> Token.Import(line)
                        str == "var" -> Token.Variable(line)
                        str == "new" -> Token.New(line)
                        str == "null" -> Token.Null(line)
                        str == "class" -> Token.Class(line)
                        str == "constructor" -> Token.Constructor(line)
                        str == "while" -> Token.While(line)
                        str == "this" -> Token.This(line)
                        str == "virtual" -> Token.Virtual(line)
                        str == "override" -> Token.Override(line)
                        str == "for" -> Token.For(line)
                        str == "continue" -> Token.Continue(line)
                        str == "break" -> Token.Break(line)
                        str == "if" -> Token.If(line)
                        str == "else" -> {
                            while(tokens.lastOrNull() is Token.NewLine)
                                tokens.removeLast()
                            Token.Else(line)
                        }
                        str == "true" || str == "false" -> Token.BoolLiteral(line, str.toBoolean())
                        str.all { it.isDigit() } && tokens.lastOrNull() is Token.Dot -> Token.Name(line, str)
                        (str[0] in "+-" || str[0].isDigit()) && str != "_" && str.drop(1).all { it.isDigit() || it == '_' } -> {
                            val n = when {
                                str[0] == '_' -> 0
                                str[0] in "+-" && str[1] == '_' -> 1
                                str.last() == '_' -> str.length - 1
                                else -> -1
                            }
                            if(n != -1)
                                illegal("Invalid digit separator", Line(file, C(lineNumber, charNumber - str.length + n)))

                            var groupSize = 0

                            for(i in str.indices) {
                                if (str[str.length - i - 1] != '_')
                                    groupSize++
                                else if(groupSize == 0)
                                    illegal("Invalid digit separator", Line(file, C(lineNumber, charNumber - i)))
                                else if (groupSize % 3 != 0) {
                                    warning(
                                        "Unconventional digit grouping",
                                        Line(file, (0 until groupSize).map { C(lineNumber, charNumber - i + it) })
                                    )
                                    groupSize = 0
                                } else groupSize = 0
                            }
                            Token.IntLiteral(line, str.filter { it != '_' })
                        }
                        else -> {
                            if(waitingNamespace != null) {
                                val n = Token.Namespace(
                                    Line(file, waitingNamespace.first + line.l),
                                    Line(file, waitingNamespace.first.dropLast(2)),
                                    waitingNamespace.second,
                                    str
                                )
                                waitingNamespace = null
                                n
                            }
                            else Token.Name(line, str)
                        }
                    }
                    ite.previous()
                    charNumber--
                }
                sb.clear()
            }
            c == '!' && next?.first == '!' -> {
                tokens += Token.NullAssert(next.second)
                ite.next()
                charNumber++
            }
            /*c == '+' && next?.first == '+' && (last.isWhitespace() || last in "({[,;:") -> {
                tokens += Token.Increase(next.second)
                ite.next()
                charNumber++
            }*/
            c in "+-" && !tokens.last().isObject -> {
                if(next?.first?.isDigit() == true) sb.append(c)
                else tokens += Token.Sign(singleCharLine, c)
            }
            c == '\n' -> {
                val newLine = run {
                    when(tokens.lastOrNull()) {
                        is Token.CloseRound -> {
                            val (before, _) = tokens.beforeBrackets()!!
                            if(before.last() is Token.If || before.last() is Token.While || before.last() is Token.For) return@run false
                        }
                        is Token.Else, is Token.Plus, is Token.Multiply, is Token.LessThan, is Token.GreaterThan, is Token.SetEqual -> return@run false
                    }
                    brackets.isEmpty() || openBrackets[brackets.peek()] !in "(["
                }
                if(newLine) {
                    tokens += Token.NewLine(singleCharLine)
                }
                lineNumber++
                charNumber = -1
            }
            c.isWhitespace() -> {}
            c in wordCharacters -> {
                sb.append(c)
            }
            c == '(' -> {
                tokens += Token.OpenRound(singleCharLine)
                brackets += 0
            }
            c == ')' -> {
                if(brackets.peek() != 0) illegal("Expected '${closeBrackets[brackets.peek()]}'", singleCharLine)
                tokens += Token.CloseRound(singleCharLine)
                brackets.pop()
            }
            c == '[' -> {
                tokens += Token.OpenSquare(singleCharLine)
                brackets += 1
            }
            c == ']' -> {
                if(brackets.peek() != 1) illegal("Expected '${closeBrackets[brackets.peek()]}'", singleCharLine)
                tokens += Token.CloseSquare(singleCharLine)
                brackets.pop()
            }
            c == '{' -> {
                tokens += Token.OpenCurly(singleCharLine)
                brackets += 2
            }
            c == '}' -> {
                if(brackets.peek() != 2) illegal("Expected '${closeBrackets[brackets.peek()]}'", singleCharLine)
                tokens += Token.CloseCurly(singleCharLine)
                brackets.pop()
            }

            c == '=' && next?.first == '=' -> {
                tokens += Token.Equals(next.second)
                ite.next()
                charNumber++
            }
            c == '!' && next?.first == '=' -> {
                tokens += Token.Unequal(next.second)
                ite.next()
                charNumber++
            }

            c == '=' && next?.first == '>' -> {
                tokens += Token.Lambda(next.second)
                ite.next()
                charNumber++
            }

            c == '&' && next?.first == '&' -> {
                tokens += Token.And(next.second)
                ite.next()
                charNumber++
            }
            c == '|' && next?.first == '|' -> {
                tokens += Token.Or(next.second)
                ite.next()
                charNumber++
            }
            c == '<' && next?.first == '=' -> {
                tokens += Token.LessEqual(next.second)
                ite.next()
                charNumber++
            }
            c == '>' && next?.first == '=' -> {
                tokens += Token.GreaterEqual(next.second)
                ite.next()
                charNumber++
            }

            c == ':' -> tokens += Token.Colon(singleCharLine)
            c == ',' -> tokens += Token.Comma(singleCharLine)
            c == ';' -> tokens += Token.Semicolon(singleCharLine)

            c == '#' -> tokens += Token.MacroArgument(singleCharLine)
            c == '=' -> tokens += Token.SetEqual(singleCharLine)
            c == '?' -> tokens += Token.Nullable(singleCharLine)
            c == '<' -> tokens += Token.LessThan(singleCharLine)
            c == '>' -> tokens += Token.GreaterThan(singleCharLine)
            c == '+' -> tokens += Token.Plus(singleCharLine)
            c == '-' -> tokens += Token.Minus(singleCharLine)
            c == '*' -> tokens += Token.Multiply(singleCharLine)
            c == '/' -> tokens += Token.Divide(singleCharLine)
            c == '%' -> tokens += Token.Modulo(singleCharLine)
            c == '~' -> tokens += Token.Is(singleCharLine)

            text.drop(ite.nextIndex() - 1).joinToString("").startsWith("...") -> {
                ite.next()
                ite.next()
                tokens += Token.Vararg(Line(file, (0..2).mapIndexed { i, _ -> C(lineNumber, charNumber - i + 2) }))
                charNumber += 2
            }

            c == '.' -> tokens += Token.Dot(singleCharLine)

            c == '"' -> {
                for(c in ite) {
                    when(c) {
                        '\\' -> {
                            sb.append(when(ite.next()) {
                                't' -> '\t'
                                'n' -> '\n'
                                '\\' -> '\\'
                                'r' -> '\r'
                                '"' -> '"'
                                else -> illegal("Illegal escape character", Line(file, C(lineNumber, charNumber)))
                            })
                        }
                        '"' -> {
                            tokens += Token.StringLiteral(
                                Line(
                                    file,
                                    sb.mapIndexed { i, _ -> C(lineNumber, charNumber - i - 1) } + C(lineNumber, charNumber) + C(
                                        lineNumber,
                                        charNumber + 1
                                    )
                                ),
                                sb.toString())
                            sb.clear()
                            charNumber++
                            break
                        }
                        else -> sb.append(c)
                    }
                    charNumber++
                }
            }
            else -> illegal("Unexpected '$c'", singleCharLine)
        }
        charNumber++
    }
    if(brackets.isNotEmpty()) illegal("Expected '${closeBrackets[brackets.peek()]}'", Line(file, C(lineNumber, charNumber)))


    val lines = tokens.splitBrackets<Token.NewLine>().filter(TokenLine::isNotEmpty)
    val ite2 = lines.listIterator()

    if(lines.firstOrNull()?.get(0) is Token.Package) {
        if(!lines.first().string().matches(Regex("pn")))
            illegal("Expected package", lines[0].drop(1).line())

        val name = (tokens[1] as Token.Name).name
        file.pckg = DPackage[name]?:DPackage(name)

        if(ite2.hasNext()) ite2.next()
    } else file.pckg = DPackage["main"]?:DPackage("main")

    return Triple(file, tokens, ite2)
}