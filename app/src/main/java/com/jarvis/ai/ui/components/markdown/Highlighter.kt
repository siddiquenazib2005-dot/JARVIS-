package com.jarvis.ai.ui.components.markdown

enum class TokenKind { PLAIN, COMMENT, STRING, NUMBER, KEYWORD, ANNOTATION, PUNCT }

data class Token(val text: String, val kind: TokenKind)

object Highlighter {

    val SUPPORTED: Set<String> = setOf(
        "kotlin", "kt",
        "java",
        "js", "javascript",
        "ts", "typescript",
        "python", "py",
        "bash", "sh", "shell",
        "json",
        "c", "cpp",
        "sql",
        "xml", "html"
    )

    private enum class Lang { KOTLIN, JAVA, JS, TS, PYTHON, BASH, JSON, CFAMILY, SQL, MARKUP, UNKNOWN }

    private fun resolve(language: String): Lang = when (language.trim().lowercase()) {
        "kotlin", "kt" -> Lang.KOTLIN
        "java" -> Lang.JAVA
        "js", "javascript" -> Lang.JS
        "ts", "typescript" -> Lang.TS
        "python", "py" -> Lang.PYTHON
        "bash", "sh", "shell" -> Lang.BASH
        "json" -> Lang.JSON
        "c", "cpp" -> Lang.CFAMILY
        "sql" -> Lang.SQL
        "xml", "html" -> Lang.MARKUP
        else -> Lang.UNKNOWN
    }

    private val KOTLIN_KEYWORDS = setOf(
        "fun", "val", "var", "if", "else", "when", "for", "while", "return",
        "class", "object", "data", "interface", "import", "package", "null",
        "true", "false", "this", "super", "is", "in", "as", "try", "catch",
        "finally", "throw", "companion", "sealed", "enum", "const", "lateinit",
        "suspend", "override", "open", "private", "public", "internal", "protected"
    )

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "continue", "default", "do", "double", "else", "enum", "extends",
        "final", "finally", "float", "for", "if", "implements", "import",
        "instanceof", "int", "interface", "long", "native", "new", "null",
        "package", "private", "protected", "public", "record", "return", "short",
        "static", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "true", "false"
    )

    private val JS_KEYWORDS = setOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue",
        "debugger", "default", "delete", "do", "else", "export", "extends",
        "false", "finally", "for", "function", "if", "import", "in",
        "instanceof", "let", "new", "null", "return", "super", "switch", "this",
        "throw", "true", "try", "typeof", "undefined", "var", "void", "while", "yield"
    )

    private val TS_KEYWORDS = JS_KEYWORDS + setOf(
        "any", "as", "boolean", "declare", "enum", "implements", "infer", "is",
        "keyof", "namespace", "never", "number", "readonly", "string", "type",
        "unknown"
    )

    private val PYTHON_KEYWORDS = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue",
        "def", "del", "elif", "else", "except", "False", "finally", "for",
        "from", "global", "if", "import", "in", "is", "lambda", "None",
        "nonlocal", "not", "or", "pass", "raise", "return", "True", "try",
        "while", "with", "yield"
    )

    private val BASH_COMMANDS = setOf(
        "if", "then", "else", "fi", "for", "do", "done", "case", "esac",
        "while", "function", "echo", "export", "cd", "sudo", "apt", "npm",
        "pip", "git", "ls", "cat", "grep", "sed", "awk", "curl", "wget",
        "chmod", "mkdir", "rm", "cp", "mv"
    )

    private val JSON_KEYWORDS = setOf("true", "false", "null")

    private val C_FAMILY_KEYWORDS = setOf(
        "auto", "bool", "break", "case", "catch", "char", "class", "const",
        "constexpr", "continue", "default", "delete", "do", "double", "else",
        "enum", "extern", "false", "float", "for", "goto", "if", "inline",
        "int", "long", "namespace", "new", "nullptr", "operator", "private",
        "protected", "public", "return", "short", "signed", "sizeof", "static",
        "struct", "switch", "template", "this", "throw", "true", "try",
        "typedef", "typename", "union", "unsigned", "using", "virtual", "void",
        "volatile", "while"
    )

    private val SQL_KEYWORDS = setOf(
        "select", "from", "where", "insert", "update", "delete", "create",
        "table", "drop", "join", "left", "right", "inner", "group", "by",
        "order", "limit", "into", "values", "set", "and", "or", "not", "null",
        "on", "distinct", "having", "as"
    )

    private fun keywordsFor(lang: Lang): Set<String> = when (lang) {
        Lang.KOTLIN -> KOTLIN_KEYWORDS
        Lang.JAVA -> JAVA_KEYWORDS
        Lang.JS -> JS_KEYWORDS
        Lang.TS -> TS_KEYWORDS
        Lang.PYTHON -> PYTHON_KEYWORDS
        Lang.BASH -> BASH_COMMANDS
        Lang.JSON -> JSON_KEYWORDS
        Lang.CFAMILY -> C_FAMILY_KEYWORDS
        Lang.SQL -> SQL_KEYWORDS
        Lang.MARKUP, Lang.UNKNOWN -> emptySet()
    }

    private val PUNCTUATION = "{}[]()<>;,=+-*/!&|:?%~^#`'\"".toSet()

    fun highlight(language: String, code: String): List<Token> {
        if (code.isEmpty()) return emptyList()
        val lang = resolve(language)
        if (lang == Lang.UNKNOWN) return listOf(Token(code, TokenKind.PLAIN))

        val keywords = keywordsFor(lang)
        val lineComment: String? = when (lang) {
            Lang.KOTLIN, Lang.JAVA, Lang.JS, Lang.TS, Lang.CFAMILY -> "//"
            Lang.SQL -> "--"
            Lang.PYTHON, Lang.BASH -> "#"
            else -> null
        }
        val hasBlockComment = lang in setOf(
            Lang.KOTLIN, Lang.JAVA, Lang.JS, Lang.TS, Lang.CFAMILY, Lang.SQL
        )
        val supportsAnnotations = lang in setOf(
            Lang.KOTLIN, Lang.JAVA, Lang.JS, Lang.TS, Lang.PYTHON
        )
        val stringQuotes = if (lang == Lang.BASH) "\"'`" else "\"'"

        val tokens = mutableListOf<Token>()
        val plainBuffer = StringBuilder()
        var i = 0
        val n = code.length

        fun flushPlain() {
            if (plainBuffer.isNotEmpty()) {
                tokens += Token(plainBuffer.toString(), TokenKind.PLAIN)
                plainBuffer.clear()
            }
        }

        while (i < n) {
            val c = code[i]

            when {
                lang == Lang.MARKUP && code.startsWith("<!--", i) -> {
                    flushPlain()
                    val end = code.indexOf("-->", i + 4)
                    val stop = if (end == -1) n else end + 3
                    tokens += Token(code.substring(i, stop), TokenKind.COMMENT)
                    i = stop
                }

                lineComment != null && code.startsWith(lineComment, i) -> {
                    flushPlain()
                    val end = code.indexOf('\n', i)
                    val stop = if (end == -1) n else end
                    tokens += Token(code.substring(i, stop), TokenKind.COMMENT)
                    i = stop
                }

                hasBlockComment && code.startsWith("/*", i) -> {
                    flushPlain()
                    val end = code.indexOf("*/", i + 2)
                    val stop = if (end == -1) n else end + 2
                    tokens += Token(code.substring(i, stop), TokenKind.COMMENT)
                    i = stop
                }

                c in stringQuotes -> {
                    flushPlain()
                    var j = i + 1
                    while (j < n) {
                        val sc = code[j]
                        if (sc == '\\' && j + 1 < n) {
                            j += 2
                        } else {
                            val closedHere = sc == c
                            j++
                            if (closedHere) break
                        }
                    }
                    tokens += Token(code.substring(i, j), TokenKind.STRING)
                    i = j
                }

                c.isDigit() -> {
                    flushPlain()
                    var j = i
                    if (c == '0' && i + 1 < n && (code[i + 1] == 'x' || code[i + 1] == 'X')) {
                        j = i + 2
                        while (j < n && (code[j].isDigit() || code[j] in 'a'..'f' || code[j] in 'A'..'F')) j++
                    } else {
                        while (j < n && (code[j].isDigit() || code[j] == '.' || code[j] == '_')) j++
                    }
                    tokens += Token(code.substring(i, j), TokenKind.NUMBER)
                    i = j
                }

                supportsAnnotations && c == '@' && i + 1 < n && code[i + 1].isLetter() -> {
                    flushPlain()
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    tokens += Token(code.substring(i, j), TokenKind.ANNOTATION)
                    i = j
                }

                c.isLetter() || c == '_' || c == '$' -> {
                    flushPlain()
                    var j = i
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '$')) j++
                    val word = code.substring(i, j)
                    tokens += Token(word, if (word in keywords) TokenKind.KEYWORD else TokenKind.PLAIN)
                    i = j
                }

                c in PUNCTUATION -> {
                    flushPlain()
                    var j = i
                    while (j < n && code[j] in PUNCTUATION) j++
                    tokens += Token(code.substring(i, j), TokenKind.PUNCT)
                    i = j
                }

                else -> {
                    plainBuffer.append(c)
                    i++
                }
            }
        }
        flushPlain()
        return tokens
    }
}
