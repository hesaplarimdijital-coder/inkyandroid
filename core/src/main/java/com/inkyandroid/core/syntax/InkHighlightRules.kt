package com.inkyandroid.core.syntax

/**
 * Upstream's ink highlight rules, `app/renderer/ace-ink-mode/ace-ink.js` (`inkHighlightRules` and the
 * `#customInstruction` rule `InkMode` adds for an instruction prefix), transcribed unchanged: the same
 * states in the same order, the same rules, and each regex as its JavaScript source, exactly as written
 * between the slashes upstream. `RulesGoldenTest` compares the normalized result with what Ace makes of
 * upstream's file.
 *
 * Upstream's own comments are kept where they explain a rule.
 */
internal object InkHighlightRules {

    private fun rule(regex: String, token: Any, next: Any? = null, push: Any? = null) =
        AceRule(regex = regex, token = token, next = next, push = push)

    private fun include(name: String) = AceRule(include = name)

    private fun defaultToken(token: String) = AceRule(defaultToken = token)

    private fun state(vararg rules: AceRule): MutableList<AceRule> = mutableListOf(*rules)

    /** A fresh, normalized rule set (`new inkHighlightRules().getRules()`), or with the instruction prefix rule. */
    fun build(instructionPrefix: String?): AceRuleSet {
        val rules = standardRules()
        normalizeRules(rules)
        if (!instructionPrefix.isNullOrEmpty()) {
            // InkMode(instructionPrefix): the normalized standard rules, plus this state, normalized again
            rules["#customInstruction"] = state(
                rule(
                    regex = "(\\s*)($instructionPrefix)(.*)",
                    token = listOf(
                        "customInstruction", // whitespace
                        "customInstruction.punctuation", // prefix
                        "customInstruction", // user content
                    ),
                ),
            )
            normalizeRules(rules)
        }
        return rules
    }

    private fun standardRules(): AceRuleSet {
        val rules = AceRuleSet()
        rules["start"] = state(
            include("#escapes"),
            include("#comments"),
            rule(
                regex = """^(\s*)(={2,})(\s*)((?:function)?)(\s*)(\w+)(\s*)(\([\w,\s->]*\))?(\s*)((?:={1,})?)""",
                token = listOf(
                    "",
                    "flow.knot.declaration.punctuation", // ===
                    "flow.knot.declaration", // whitespace
                    "flow.knot.declaration.function", // function (optional)
                    "flow.knot.declaration", // whitespace
                    "flow.knot.declaration.name", // knot_name
                    "flow.knot.declaration", // whitespace
                    "flow.knot.declaration.parameters", // (arg1, arg2)
                    "flow.knot.declaration", // whitespace
                    "flow.knot.declaration.punctuation", // ====
                ),
            ),
            rule(
                regex = """^(\s*)(=)(\s*)(\w+)(\s*)(\([\w,\s->]*\))?""",
                token = listOf(
                    "flow.stitch.declaration", // whitespace
                    "flow.stitch.declaration.punctuation", // =
                    "flow.stitch.declaration", // whitespace
                    "flow.stitch.declaration.name", // stitch_name
                    "flow.stitch.declaration", // whitespace
                    "flow.stitch.declaration.parameters", // parameters
                ),
            ),
            include("#statements"),
        )
        rules["#TODO"] = state(
            rule(
                regex = """^(\s*)(TODO\b)(.*)""",
                token = listOf(
                    "todo", // whitespace
                    "todo.TODO", // TODO
                    "todo", // user text
                ),
            ),
        )
        rules["#choice"] = state(
            rule(
                regex = """^(\s*)((?:[\*\+]\s?)+)(\s*)(?:(\(\s*)(\w+)(\s*\)))?""",
                token = listOf(
                    "choice", // whitespace
                    "choice.bullets", // * or +
                    "choice", // whitespace
                    "choice.label", // (
                    "choice.label.name", // label_name
                    "choice.label", // )
                ),
                // Sub section within choice
                push = state(
                    rule(token = "choice", regex = """$""", next = "pop"),
                    rule(
                        token = "choice.weaveBracket",
                        regex = """\s*\[\s*""", // [ weave start
                        push = state(
                            rule(
                                token = "choice.weaveBracket",
                                regex = """\s*\]\s*""", // ] weave end
                                next = "start",
                            ),
                            include("#inlineContent"),
                            defaultToken("choice.weaveInsideBrackets"),
                        ),
                    ),
                    include("#choice"),
                    include("#gather"),
                    include("#mixedContent"),
                    include("#comments"),
                    defaultToken("choice"),
                ),
            ),
        )
        rules["#escapes"] = state(
            // backslash escape sequences (e.g. \\ or \[ or \] or \~ or \#)
            rule(token = "escape", regex = """\\[\[\]()\\~{}\/#*+-]"""),
        )
        rules["#comments"] = state(
            rule(
                token = "punctuation.definition.comment.json",
                regex = """\/\*\*""", // /** comment block
                push = state(
                    rule(token = "punctuation.definition.comment.json", regex = """\*\/""", next = "pop"), // end comment block */
                    defaultToken("comment.block.documentation.json"),
                ),
            ),
            rule(
                token = "punctuation.definition.comment.json",
                regex = """\/\*""", // /* comment block
                push = state(
                    rule(token = "punctuation.definition.comment.json", regex = """\*\/""", next = "pop"), // end comment block */
                    defaultToken("comment.block.json"),
                ),
            ),
            rule(
                token = listOf("punctuation.definition.comment.json", "comment.line.double-slash.js"),
                regex = """(\/\/)(.*$)""", // // comment
            ),
        )

        // Try different types of divert in sequence, since it's a bit complicated
        // to try to do it in one expression!
        // It's partly complicated because we need to parse it fairly accurately in
        // order to find the divert targets for hyperlinking.
        rules["#divert"] = state(
            // -> DONE|END
            rule(
                regex = """(->|<-)(\s*)(DONE|END)(\s*)""",
                token = listOf(
                    "divert.operator", // ->
                    "divert", // whitespace
                    "divert.to-special", // DONE / END
                    "divert", // whitespace
                ),
            ),
            // Tunnel onwards
            rule(
                regex = """(->->)(\s*)(\w[\w\.\s]*)""",
                token = listOf(
                    "divert.to-tunnel", // ->->
                    "divert", // whitespace
                    "divert.target", // target.name
                ),
            ),
            // Divert with parameters: -> knot (param, -> param2)
            rule(
                regex = """(->|<-)(\s*)(\w[\w\.\s]*?)(\s*)(\()""",
                token = listOf(
                    "divert.operator", // ->
                    "divert", // whitespace
                    "divert.target", // target.name
                    "divert", // whitespace
                    "divert.operator", // (
                ),
                push = state(
                    // Divert target, as parameter to the current divert
                    rule(
                        regex = """(->)(\s*)(\w[\w\.\s]*?)(\s*)(?![\w\.])""",
                        token = listOf(
                            "divert.parameter.operator", // ->
                            "divert.parameter", // whitespace
                            "divert.target", // target.name
                            "divert.parameter", // whitespace
                        ),
                    ),
                    // Explicitly parse function calls so that the close bracket
                    // doesn't accidentally get parsed as the end of the parameters
                    include("#functionCallInDivertParameter"),
                    rule(regex = """\)""", token = "divert.parameter.operator", next = "pop"),
                    defaultToken("divert.parameter"),
                ),
            ),
            // Vanilla divert
            rule(
                regex = """(->|<-)(\s*)(\w[\w\.\s]*?)(\s*)(?![\w\.])""",
                token = listOf(
                    "divert.operator", // -> | <-
                    "divert", // whitespace
                    "divert.target", // target.name
                    "divert", // whitespace
                ),
            ),
            // Divert to gather/choice point, or end of tunnel divert
            rule(regex = """->""", token = "divert.operator"),
        )

        // Used to parse function calls within divert parameters so that
        // the closing bracket doesn't accidentally cause the rule to end early.
        // Having it as a separate rule also allows it to be recursive.
        rules["#functionCallInDivertParameter"] = state(
            rule(
                regex = """\w+\s*\(""",
                token = "divert.parameter",
                push = state(
                    include("#functionCallInDivertParameter"),
                    rule(regex = """\)""", token = "divert.parameter", next = "pop"),
                    defaultToken("divert.parameter"),
                ),
            ),
        )

        rules["#gather"] = state(
            rule(
                regex = """^(\s*)((?:-(?!>)\s*)+)""",
                token = listOf(
                    "gather", // whitespace
                    "gather.bullets", // - -
                ),
                push = state(
                    rule(regex = """$""", token = "gather", next = "pop"),
                    include("#escapes"),
                    include("#gather"),
                    include("#comments"),
                    include("#logicLineInsert"),
                    rule(
                        regex = """(\(\s*)(\w+)(\s*\)\s*)""",
                        token = listOf(
                            "gather.label", // (
                            "gather.label.name", // label_name
                            "gather.label", // )
                        ),
                    ),
                    include("#choice"),
                    include("#mixedContent"),
                    defaultToken("gather.innerContent"),
                ),
            ),
        )

        rules["#globalVAR"] = state(
            rule(
                regex = """^(\s*)(VAR|CONST)\b""", // (\s*)(\w+)(\s*)
                token = listOf(
                    "var-decl", // whitespace
                    "var-decl.keyword",
                ),
                push = state(
                    rule(
                        regex = """(\s*)(\w+)(\s*)""",
                        token = listOf(
                            "var-decl", // whitespace
                            "var-decl.name", // var_name
                            "var-decl", // whitespace
                        ),
                    ),
                    // The rest of the assignment line
                    rule(regex = """$""", token = "var-decl", next = "pop"),
                    include("#comments"),
                    defaultToken("var-decl"),
                ),
            ),
        )

        rules["#listDef"] = state(
            rule(
                regex = """^(\s*)(LIST)\b""",
                token = listOf(
                    "list-decl", // whitespace
                    "list-decl.keyword",
                ),
                push = state(
                    rule(
                        regex = """(\s*)(\w+)(\s*)""",
                        token = listOf(
                            "list-decl", // whitespace
                            "list-decl.name", // var_name
                            "list-decl", // whitespace
                        ),
                    ),
                    // The rest of the assignment line
                    rule(regex = """$""", token = "list-decl", next = "pop"),
                    include("#comments"),
                    defaultToken("list-decl"),
                ),
            ),
        )

        rules["#INCLUDE"] = state(
            rule(
                regex = """(\s*)(INCLUDE\b)""",
                token = listOf("include", "include.keyword"),
                push = state(
                    rule(
                        regex = """(\s*)([^\r\n]+)""",
                        token = listOf(
                            "include", // whitespace
                            "include.filepath",
                        ),
                    ),
                    // The rest of the assignment line
                    rule(regex = """$""", token = "include", next = "pop"),
                    defaultToken("include"),
                ),
            ),
        )

        rules["#EXTERNAL"] = state(
            rule(
                regex = """(\s*)(EXTERNAL\b)""",
                token = listOf("external", "external.keyword"),
                // The rest of the external line until a newline
                push = state(
                    rule(
                        regex = """(\s*)(\w+)(\([\w,\s->]*\))?""",
                        token = listOf(
                            "external", // whitespace
                            "external.declaration.name", // function name
                            "external.declaration.parameters", // function parameters
                        ),
                    ),
                    rule(regex = """$""", token = "external", next = "pop"),
                    defaultToken("external"),
                ),
            ),
        )

        rules["#inlineConditional"] = state(
            rule(
                regex = """(\{)([^:\|\}]+:)""",
                token = listOf("logic.punctuation", "logic.inline.conditional.condition"),
                push = state(
                    rule(token = "logic.inline.conditional.punctuation", regex = """\}""", next = "pop"),
                    rule(token = "logic.inline.conditional.punctuation", regex = """\|"""),
                    include("#mixedContent"),
                    defaultToken("logic.inline.innerContent"),
                ),
            ),
        )
        rules["#inlineSequence"] = state(
            rule(
                regex = """(\{)(\s*)((?:~|&|!|\$)?)(?=[^\|\}]*\|)""", // Try look ahead to make sure there's a pipe char
                token = listOf(
                    "logic.punctuation", // {
                    "logic.sequence", // whitespace
                    "logic.sequence.operator", // sequence type char (~&!$)
                ),
                push = state(
                    rule(token = "logic.punctuation", regex = """\}""", next = "pop"), // }
                    rule(token = "logic.sequence.punctuation", regex = """\|(?!\|)"""), // | (but not ||)
                    include("#mixedContent"),
                    defaultToken("logic.sequence.innerContent"),
                ),
            ),
        )
        rules["#inlineLogic"] = state(
            rule(
                token = "logic.punctuation",
                regex = """\{""",
                push = state(
                    rule(token = "logic.punctuation", regex = """\}""", next = "pop"),
                    defaultToken("logic.inline"),
                ),
            ),
        )
        rules["#multiLineLogic"] = state(
            rule(
                regex = """^(\s*)(\{)(?:([^}:]+)(:))?(?=[^}]*$)""",
                token = listOf(
                    "logic", // whitespace
                    "logic.punctuation", // {
                    "logic.conditional.multiline.condition", // optional initial condition
                    "logic.conditional.multiline.condition.punctuation", // :
                ),
                push = state(
                    rule(token = "logic.punctuation", regex = """\}""", next = "pop"), // }
                    rule(regex = """^\s*else\s*\:""", token = "conditional.multiline.else"), // else :
                    rule(
                        regex = """^(\s*)(-)(?!>)((?:\s?[^:\{}]+):)?""",
                        token = listOf(
                            "logic.multiline.branch", // whitespace
                            "logic.multiline.branch.operator", // -
                            "logic.multiline.branch.condition", //
                        ),
                    ),
                    include("#statements"),
                    defaultToken("logic.multiline.innerContent"),
                ),
            ),
        )
        rules["#logicLine"] = state(
            rule(
                token = "logic.tilda",
                regex = """^\s*~\s*""",
                push = state(
                    rule(token = "logic.tilda", regex = """$""", next = "pop"),
                    include("#escapes"),
                    include("#comments"),
                    defaultToken("logic.tilda"),
                ),
            ),
        )
        rules["#logicLineInsert"] = state(
            rule(
                token = "logic.tilda",
                regex = """\s*~\s*""",
                push = state(
                    rule(token = "logic.tilda", regex = """$""", next = "pop"),
                    include("#escapes"),
                    include("#comments"),
                    defaultToken("logic.tilda"),
                ),
            ),
        )
        rules["#tags"] = state(
            // e.g. #tag should be highlighted.
            // Upstream: tags are assumed to run to the end of the line, unless they are in a choice,
            // where "[" and "]" end them; so `{red #red|blue #blue}` is not handled.
            rule(token = "tag", regex = """#[^\[\]$]+"""),
        )
        rules["#inlineContent"] = state(
            include("#inlineConditional"),
            include("#inlineSequence"),
            include("#inlineLogic"),
        )
        rules["#mixedContent"] = state(
            include("#inlineContent"),
            include("#divert"),
            include("#tags"),
            rule(token = "glue", regex = """<>"""),
        )
        rules["#statements"] = state(
            include("#comments"),
            include("#escapes"),
            include("#TODO"),
            include("#globalVAR"),
            include("#listDef"),
            include("#EXTERNAL"),
            include("#INCLUDE"),
            include("#choice"),
            include("#gather"),
            include("#multiLineLogic"),
            include("#endOfSection"),
            include("#logicLine"),
            include("#mixedContent"),
            include("#customInstruction"),
        )

        // This will be added when overriden in project settings
        // "#customInstruction": [{
        // }],
        return rules
    }
}
