package de.drgn.sch

fun List<Token>.string() = map { it.charForm }.joinToString("")
inline fun<reified T : Token> List<Token>.getFirst() = first { it is T } as T

fun parse(file: DFile, ite: ListIterator<TokenLine>) {
    if(!ite.hasNext()) return
    for (line in ite) {
        if (line.string() == "§n") {
            val name = line[1] as Token.Name
            file.imports += DPackage[name.name] ?: illegal(
                "Package doesn't exist", name.line
            )
        } else break
    }

    if(!ite.hasPrevious()) return
    ite.previous()

    fun parseFunction(line: TokenLine): ASTFuncDefinition {
        val (preCode, code) = line.beforeBrackets()!!
        val name = line.getFirst<Token.Name>()
        val (colon, leftOfReturn, rightOfReturn) = preCode.getOperators { it is Token.Colon } ?: Triple(
            null,
            preCode,
            listOf(Token.Name(preCode.last().line, "void"))
        )
        if(rightOfReturn.isEmpty()) {
            val c = colon!!.line.l.single()
            illegal("Expected return type", Line(colon.line.file, C(c.line, c.index + 1)))
        }
        val returnType = parseType(rightOfReturn, true)

        val tokenArgs = leftOfReturn.dropWhile { it !is Token.OpenRound }.drop(1).dropLast(1).splitBrackets<Token.Comma>()
        val isVararg = tokenArgs.lastOrNull()?.last() is Token.Vararg
        val args = (if (isVararg) tokenArgs.dropLast(1) else tokenArgs).map {
            if (!it.string().matches(Regex("n:.+"))) illegal("Expected parameter", it.line())
            it[0] as Token.Name to parseType(it.drop(2))
        }

        val elements =
            code.drop(1).dropLast(1).splitBrackets<Token.NewLine>().filter(TokenLine::isNotEmpty).map(::parseElement)

        return ASTFuncDefinition(line, name, args, isVararg, returnType, elements, line[0] is Token.Virtual, line[0].takeUnless { it !is Token.Override } as Token.Override?)
    }

    for(line in ite) {
        val setEqual = line.getFirstOperator<Token.SetEqual>()
        when {
            setEqual != null && line.string().startsWith("vn") -> {
                val (_, before, after) = setEqual
                before.drop(1).splitBrackets<Token.Comma>().let {
                    val objects = after.splitBrackets<Token.Comma>().map(::parseObject)

                    val types = Array<ASTType?>(it.size) { null }
                    val names = it.mapIndexed { i, it ->
                        if (it.string().matches(Regex("n:.+"))) {
                            types[i] = parseType(it.drop(2))
                        } else if (it.string() != "n")
                            illegal("Expected variable name", it.line())

                        it[0] as Token.Name
                    }

                    ast += ASTGlobalDeclaration(line, names, types.toList(), objects)
                }
            }
            line.string().matches(Regex("fn\\(.+}")) -> ast += parseFunction(line)
            line.string().matches(Regex("fm\\(.+")) -> {
                val (_, leftOfReturn, rightOfReturn) = line.getOperators { it is Token.Colon } ?: Triple(
                    line[0],
                    line,
                    listOf(Token.Name(line.last().line, "void"))
                )
                val returnType = parseType(rightOfReturn, true)

                val tokenArgs = leftOfReturn.drop(3).dropLast(1).splitBrackets<Token.Comma>()
                val isVararg = tokenArgs.lastOrNull()?.last() is Token.Vararg
                val args = (if(isVararg) tokenArgs.dropLast(1) else tokenArgs).map {
                    if(it[0] is Token.MacroArgument) null else parseType(it)
                }

                ast += ASTIntrinsicDeclaration(line, line[1] as Token.Intrinsic, args, isVararg, returnType)
            }
            line.string().matches(Regex("cn(:n)?\\{.*}")) -> {
                val (_, brackets) = line.beforeBrackets()!!
                val split = brackets.drop(1).dropLast(1).splitBrackets<Token.NewLine>().filter(TokenLine::isNotEmpty)

                var constructor: ASTFuncDefinition? = null

                val properties = mutableListOf<Pair<Token.Name, ASTType>>()
                val functions = mutableListOf<ASTFuncDefinition>()

                val name = line[1] as Token.Name

                val superClass = line[3].takeIf { line[2] is Token.Colon } as Token.Name?

                split.forEach { line ->
                    when {
                        line.string().matches(Regex("vn:.+")) -> properties += line[1] as Token.Name to parseType(line.drop(3))
                        line.string().matches(Regex("å\\(.+}")) -> {
                            if(constructor != null) illegal("Constructor already declared", constructor!!.name.line, line[0].line)
                            val (preCode, code) = line.beforeBrackets()!!

                            val tokenArgs = preCode.drop(2).dropLast(1).splitBrackets<Token.Comma>()
                            val isVararg = tokenArgs.lastOrNull()?.last() is Token.Vararg
                            val args = (if(isVararg) tokenArgs.dropLast(1) else tokenArgs).map {
                                if(!it.string().matches(Regex("n:.+"))) illegal("Expected parameter", it.line())
                                it[0] as Token.Name to parseType(it.drop(2))
                            }

                            val elements = code.drop(1).dropLast(1).splitBrackets<Token.NewLine>().filter(TokenLine::isNotEmpty).map(::parseElement)

                            val f = ASTFuncDefinition(line, Token.Name(line[0].line, "$name::constructor"), args, isVararg, ASTTVoid(listOf(line[0])), elements)

                            constructor = f
                        }
                        line.string().matches(Regex("[ó€]?fn\\(.+}")) -> functions += parseFunction(line)
                        else -> illegal("Expected class member", line.line())
                    }
                }
                ast += ASTClassDefinition(
                    line,
                    line[1] as Token.Name,
                    superClass,
                    constructor ?: illegal("No constructor defined", line[1].line),
                    properties,
                    functions
                )
            }
            else -> illegal("Expected top-level declaration", line.line())
        }
    }
}

fun parseType(tokens: TokenLine, isReturnType: Boolean = false): ASTType {

    val first = tokens[0]

    tokens.splitBrackets<Token.Comma>().let {
        if (it.size == 1) return@let
        return ASTTTuple(tokens, it.mapIndexed { i, it ->
            if(it.string().startsWith("n:")) it[0] as Token.Name to parseType(it.drop(2))
            else Token.Name(emptyLine, "$i") to parseType(it)
        })
    }
    tokens.afterBrackets()?.also { (brackets, after) ->
        if(brackets[0] is Token.OpenRound) {
            if (after.isEmpty()) return parseType(brackets.drop(1).dropLast(1))
        }
    }
    tokens.beforeBrackets()?.let { (before, brackets) ->
        if(brackets[0] !is Token.OpenRound) return@let
        val returnType = parseType(before, true)
        val arguments = brackets.drop(1).dropLast(1).splitBrackets<Token.Comma>().filter { it.isNotEmpty() }.map(::parseType)
        return ASTTFunction(tokens, returnType, arguments)
    }
    return when {
        tokens.size == 1 && first is Token.Name -> when {
            first.name == "void" -> if(isReturnType) ASTTVoid(tokens) else illegal("'void' can only be used as a return type", first.line)
            first.name == "bool" -> ASTTBool(tokens)
            first.name.matches(Regex("[ui](8|16|32|64)")) -> ASTTInt(tokens, first.name)
            first.name.matches(Regex("f(32|64)")) -> ASTTFloat(tokens, first.name)
            else -> ASTTName(tokens, first)
        }
        tokens.string().matches(Regex(".+\\[]")) -> ASTTArray(tokens, parseType(tokens.dropLast(2)))
        tokens.last() is Token.Nullable -> ASTTNullable(tokens, parseType(tokens.dropLast(1)))
        else -> illegal("Expected type", tokens.line())
    }
}
fun parseElement(tokens: TokenLine): ASTElement {
    val first = tokens[0]

    when(tokens.string()) {
        "→" -> return ASTContinue(tokens)
        "¦" -> return ASTBreak(tokens)
    }

    tokens.getFirstOperator<Token.SetEqual>()?.let { (_, before, after) ->
        if(before.isEmpty() || after.isEmpty()) return@let
        if (before.string().startsWith("vn")) {
            before.drop(1).splitBrackets<Token.Comma>().let {
                val objects = after.splitBrackets<Token.Comma>().map(::parseObject)

                val types = Array<ASTType?>(it.size) { null }
                val names = it.mapIndexed { i, it ->
                    if (it.string().matches(Regex("n:.+"))) {
                        types[i] = parseType(it.drop(2))
                    } else if (it.string() != "n")
                        illegal("Expected variable name", it.line())

                    it[0] as Token.Name
                }

                return ASTVariableDeclaration(tokens, names, types.toList(), objects)
            }
        }

        if (before.last() is Token.Plus) {
            val storage = parseStorage(before.dropLast(1))
            return ASTCalculationAssign(tokens, before.last(), storage, parseObject(after))
        }

        val split = before.splitBrackets<Token.Comma>()
        val objects = after.splitBrackets<Token.Comma>().map(::parseObject)
        val storages = split.map(::parseStorage)

        return ASTSet(tokens, storages, objects)
    }
    when {
        first is Token.Return -> return ASTReturn(tokens, if (tokens.size == 1) null else parseObject(tokens.drop(1)))
        tokens.string().startsWith("vn") -> {
            tokens.drop(1).splitBrackets<Token.Comma>().let {
                val types = Array<ASTType?>(it.size) { null }
                val names = it.mapIndexed { i, it ->
                    if(it.string().matches(Regex("n:.+"))) {
                        types[i] = parseType(it.drop(2))
                    }
                    else if(it.string() != "n")
                        illegal("Expected variable name", it.line())

                    it[0] as Token.Name
                }
                return ASTVariableDeclaration(tokens, names, types.toList(), null)
            }
        }
    }

    if(tokens.string().startsWith("w(")) {
        val (brackets, after) = tokens.drop(1).afterBrackets()!!
        val condition = parseObject(brackets.drop(1).dropLast(1))

        val element = parseElement(after)
        return ASTWhile(tokens, condition, element)
    }
    if(tokens.string().startsWith("┬(")) {
        val (brackets, after) = tokens.drop(1).afterBrackets()!!

        val split = brackets.drop(1).dropLast(1).splitBrackets<Token.Semicolon>(true)

        if(split.size < 3)
            illegal("Expected ';'", brackets.last().line)
        if(split.size > 3)
            illegal("Expected ')'", split[3][0].line)

        val pre = if(split[0].isEmpty()) null else parseElement(split[0])
        val condition = parseObject(split[1].drop(1))
        val post = if(split[2].size == 1) null else parseElement(split[2].drop(1))

        val element = parseElement(after)
        return ASTFor(tokens, pre, condition, post, element)
    }

    tokens.beforeBrackets()?.let { (before, brackets) ->
        when (brackets[0]) {
            is Token.OpenRound -> {
                val args = brackets.drop(1).dropLast(1).splitBrackets<Token.Comma>()
                if (before.size == 1 && before[0] is Token.Intrinsic) {
                    return ASTIntrinsicUse(tokens, before[0] as Token.Intrinsic, args)
                }
            }
        }
    }

    val obj = parseObject(tokens, true)
    if(obj.isOnlyObject) illegal("Expected statement", tokens.line())
    return obj
}
fun parseObject(tokens: TokenLine, isStatement: Boolean = false): ASTObject {
    val first = tokens[0]
    if(tokens.size == 1) {
        when(first) {
            is Token.IntLiteral -> return ASTIntLiteral(tokens, first)
            is Token.StringLiteral -> return ASTStringLiteral(tokens, first)
            is Token.BoolLiteral -> return ASTBoolLiteral(tokens, first)
            is Token.Name -> return ASTVariableUse(tokens, first)
            is Token.Null -> return ASTNull(tokens)
            is Token.This -> return ASTThis(tokens)
        }
    }
    if(tokens.string() == "i.n") return ASTFloatLiteral(tokens)

    if(tokens.last() is Token.Vararg) {
        return ASTUnwrap(tokens, parseObject(tokens.dropLast(1)))
    }

    tokens.splitBrackets<Token.Comma>().let {
        if(it.size == 1) return@let
        return ASTTuple(tokens, it.mapIndexed { i, it ->
            if(it.string().startsWith("n:")) it[0] as Token.Name to parseObject(it.drop(2))
            else Token.Name(emptyLine, "$i") to parseObject(it)
        })
    }

    tokens.getOperators { it is Token.Or }?.let { (operator, left, right) ->
        return ASTBinaryOperator(tokens, operator, parseObject(left), parseObject(right))
    }
    tokens.getOperators { it is Token.And }?.let { (operator, left, right) ->
        return ASTBinaryOperator(tokens, operator, parseObject(left), parseObject(right))
    }
    tokens.getOperators { it is Token.Equals || it is Token.Unequal }?.let { (operator, left, right) ->
        return ASTBinaryOperator(tokens, operator, parseObject(left), parseObject(right))
    }
    tokens.getOperators { it is Token.LessThan || it is Token.LessEqual || it is Token.GreaterEqual || it is Token.GreaterThan }?.let { (operator, left, right) ->
        return ASTBinaryOperator(tokens, operator, parseObject(left), parseObject(right))
    }
    tokens.getOperators { it is Token.Plus || it is Token.Minus }?.let {  (operator, left, right) ->
        val r = parseObject(right)
        return ASTBinaryOperator(tokens, operator, parseObject(left), r)
    }
    tokens.getOperators { it is Token.Multiply || it is Token.Divide || it is Token.Modulo }?.let {  (operator, left, right) ->
        val r = parseObject(right)
        return ASTBinaryOperator(tokens, operator, parseObject(left), r)
    }

    if(tokens.string().endsWith("~n")) {
        return ASTIs(tokens, parseObject(tokens.dropLast(2)), parseType(listOf(tokens.last())))
    }

    when(val t = tokens[0]) {
        is Token.Sign -> return ASTSign(tokens, t, parseObject(tokens.drop(1)))
        is Token.Not -> return ASTNot(tokens, parseObject(tokens.drop(1)))
    }



    tokens.afterBrackets()?.also { (brackets, after) ->
        if(brackets[0] is Token.OpenRound) {
            if (after.isEmpty()) return ASTParentheses(tokens, parseObject(brackets.drop(1).dropLast(1)))
            /*if (after[0] !is Token.Dot) {
                val type = parseType(brackets.drop(1).dropLast(1))
                val obj = parseObject(after)
                return ASTCast(tokens, obj, type)
            }*/
        }
    }

    if(tokens.string().startsWith("╗(")) {
        var start = 1
        val ifs = mutableListOf<TokenLine>()
        var brackets = 0
        var elseE: TokenLine? = null

        for(i in tokens.indices) {
            when (tokens[i]) {
                is Token.OpenBracket -> brackets++
                is Token.CloseBracket -> brackets--
                else -> {
                    if (brackets == 0) {
                        if (tokens.drop(i).string().startsWith("╝╗(")) {
                            ifs += tokens.subList(start, i).toList()
                            start = i + 2
                        } else if (tokens[i] is Token.Else) {
                            ifs += tokens.subList(start, i).toList()
                            elseE = tokens.drop(i + 1)
                            break
                        }
                    }
                }
            }
        }
        if(elseE == null)
            ifs += tokens.drop(start)

        val parsedIfs = ifs.mapIndexed { i, it ->
            val (condition, obj) = it.afterBrackets()!!
            if(obj.isEmpty()) {
                val lastChar = condition.last().line.l.last()
                illegal("Expected expression", Line(lastChar.file!!, C(lastChar.line, lastChar.index + 1)))
            }
            parseObject(condition) to if (isStatement) parseElement(obj) else parseObject(obj)
        }

        val parsedElseE = elseE?.let {
            if(isStatement) parseElement(it) else parseObject(it)
        }

        return ASTIf(tokens, parsedIfs, parsedElseE)
    }

    tokens.beforeBrackets()?.also { (before, brackets) ->
        when (brackets[0]) {
            is Token.OpenRound -> {
                val args = brackets.drop(1).dropLast(1).splitBrackets<Token.Comma>()
                if (before.size == 1 && before[0] is Token.Intrinsic) {
                    return ASTIntrinsicUse(tokens, before[0] as Token.Intrinsic, args)
                }
                if(before.string().matches(Regex("★n"))) {
                    return ASTNew(tokens, parseType(listOf(before[1])), args.map(::parseObject))
                }
                val func = parseObject(before)
                return ASTFuncCall(tokens, func, args.map(::parseObject))
            }
            is Token.OpenSquare -> {
                if((before.firstOrNull()?:return@also) is Token.New) {
                    val type = if(before.size == 1) null else parseType(before.drop(1))
                    val objects = brackets.drop(1).dropLast(1).splitBrackets<Token.Comma>()
                    return ASTInitializedArray(tokens, type, objects.map(::parseObject))
                }
                val obj = parseObject(before)
                val index = parseObject(brackets.drop(1).dropLast(1))
                return ASTGet(tokens, obj, index)
            }
            is Token.OpenCurly -> {

                val lines = brackets.drop(1).dropLast(1).splitBrackets<Token.NewLine>().filter(TokenLine::isNotEmpty)

                lines.firstOrNull()?.getFirstOperator<Token.Lambda>()?.let { (_, left, right) ->
                    val type = if(before.isEmpty()) null else parseType(before, true)

                    val args = left.splitBrackets<Token.Comma>()

                    val types = Array<ASTType?>(args.size) { null }
                    val names = args.mapIndexed { i, it ->
                        if (it.string().matches(Regex("n:.+"))) {
                            types[i] = parseType(it.drop(2))
                        } else if (it.string() != "n")
                            illegal("Expected variable name", it.line())

                        it[0] as Token.Name
                    }

                    val lines = lines.toMutableList()
                    lines[0] = if(lines.size == 1 && right.firstOrNull() !is Token.Return && type !is ASTTVoid) listOf(Token.Return(right.line())) + right else right

                    val elements = lines.filter(TokenLine::isNotEmpty).map(::parseElement)

                    return ASTLambda(tokens, type, names, types.toList(), elements)
                }

                if(before.isEmpty()) {
                    val elements = if (isStatement) lines.map(::parseElement) else lines.dropLast(1)
                        .map(::parseElement) + parseObject(lines.last())
                    return ASTScope(tokens, elements)
                }
                if(before.first() is Token.New) {
                    val type = if(before.size == 1) null else parseType(before.drop(1))
                    val split = brackets.drop(1).dropLast(1).splitBrackets<Token.Comma>()
                    if(split.size != 2)
                        illegal("Expected 'size, func'", brackets.drop(1).dropLast(1).line())
                    return ASTGenerateArray(tokens, type, parseObject(split[0]), parseObject(split[1]))
                }
            }
        }
    }
    if(tokens.last() is Token.NullAssert) return ASTNullAssert(tokens, parseObject(tokens.dropLast(1)))
    if(tokens.last() is Token.Increase) return ASTIncrease(tokens, parseStorage(tokens.dropLast(1)))
    tokens.getOperators { it is Token.Dot }?.let {  (_, before, after) ->
        if(before.isEmpty()) return@let
        val o = parseObject(before)
        if(after.string() == "n") return ASTMember(tokens, o, after[0] as Token.Name)
    }
    illegal("Expected ${if(isStatement) "statement" else "expression"}", tokens.line())
}
fun parseStorage(tokens: TokenLine): ASTStorage {
    val first = tokens[0]
    if(tokens.size == 1 && first is Token.Name)
        return ASTSVariableUse(tokens, first)
    tokens.getOperators { it is Token.Dot }?.let {  (_, before, after) ->
        val o = parseObject(before)
        if(after.string() != "n") return@let
        return ASTSMember(tokens, o, after[0] as Token.Name)
    }
    illegal("Expected variable", tokens.line())
}