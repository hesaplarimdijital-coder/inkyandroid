// Torture file for the ink tokenizer goldens: every rule and state of ace-ink.js, and the corners
// where JavaScript and Java regular expressions differ.
# title: The Torture Test
# author: Somebody  
#   1: numbered key
#notdict
INCLUDE  includes/first.ink
INCLUDE other.ink  // not a comment: an include path runs to the end of the line
  INCLUDE indented.ink
INCLUDE
INCLUDEX notakeyword
VAR health = 100
VAR name = "Barış" // a Turkish name
CONST MAX_HEALTH = 200
  VAR   spaced   =   3
VARIABLE not_a_declaration
LIST colours = red, (green), blue = 5
LIST mood = ( happy ) , sad
EXTERNAL playSound(name)
EXTERNAL multiply(a, b, -> c)
EXTERNAL noParams
EXTERNALS not_external
TODO: write the beginning
  TODO tidy this
TODOS are not todos
Plain prose with words, numbers 123 and çalışkan öğrenci ığdır şimdi.
Glue at the end<>
<> and glue at the start
Escapes: \[ \] \( \) \\ \~ \{ \} \/ \# \* \+ \- and \x not an escape.
A tag at the end # some tag
Tags # one # two [not] # three
/* a block comment */ text after it
/** a documentation comment */
/* a comment
   spanning several lines
   with * stars and / slashes */ and text after
/** documentation
   on two lines **/
// a line comment // with more slashes
text // with a trailing comment
=== knot_one ===
===knot_two
  ==   knot_three   ==   
=== function add(a, b) ===
=== function noArgs
==== knot_params(x, -> y, ref z) =====
== function
=== 123 ===
= stitch_one
=stitch_two(a, b)
  =   stitch_three  
= function
-> knot_one
-> knot_one.stitch_one
->knot_two
-> knot_one  
-> knot two words
-> DONE
-> END
->END
<- thread_knot
<- DONE
-> tunnel_knot ->
->-> 
->-> knot_after_tunnel
->->
-> knot_with_args (1, -> other_knot, x)
-> knot_with_args(func(1, inner(2)), "str", -> target.stitch)
-> knot_with_args(-> a, -> b.c)
-> unclosed_args(1, 2
3) closes the arguments on the next line
Some text -> divert_in_text and more
A line with -> two -> diverts
-> 
->
* A choice
+ A sticky choice
* * A nested choice
*+* Mixed bullets
  *   An indented choice
* (label_one) A labelled choice
* ( spaced_label ) A labelled choice with spaces
*(nospace)Choice
* [Bracketed only]
* Before [inside] after
* Before[inside]after
* {visited} Conditional choice
* {not visited && health > 5} [Complex condition] -> knot_one
* [Inside {inline|logic} brackets] after
* Choice with a divert -> knot_one
* Choice with a tag # choice_tag
* [Choice tag in brackets # tag] after
* Choice with glue <>
* Choice /* comment */ text
* Choice // line comment
* Choice - with a dash
* Unclosed [bracket
still inside the bracket ] and out
* Choice with escaped \[ bracket
] closes the bracket the escape opened (choices have no escape rule)
- A gather
- - A nested gather
-- Double dash gather
- (gather_label) A labelled gather
- ( spaced ) Spaced label
-(nospace)Gather
- ~ temp x = 1
- * A choice after a gather
- Gather -> with divert
- Gather with {inline} logic # and tag
- Gather /* comment */
- Gather \- escape
-> not_a_gather
~ temp x = 5
~x = x + 1 // comment in logic
  ~   return x
~ x = "string with \" escape"
~ x++
Text {inline_variable} text
Text {x > 5: conditional text} after
Text {x > 5: yes|no} after
Text {x: {nested: deep|shallow}|no} after
Text {a|b|c} sequence
Text {~a|b|c} shuffle
Text {&a|b|c} cycle
Text {!a|b|c} once
Text {$a|b} odd
Text { ~ spaced|shuffle} spaced
Text {a||b} double pipe
Text {a|-> divert_in_sequence|c}
Text {a #tag|b}
} closes the sequence whose end the tag swallowed
Text {unclosed inline
still inline } closed on the next line
Text {x:
conditional | continued } closed on the next line
{x > 5:
    Multi-line content
  - else:
    Other content
}
{
  - x > 5: big
  - x < 0:
      negative
  -> not_a_branch
  - else: other
}
{ shuffle:
  - one
  - two
}
{x: one line} trailing
{x:
  VAR inside = 1
  * choice inside logic
  ~ y = 2
  // comment inside logic
  {inline|in multiline}
  else: not anchored
else: anchored
}
{a} {b}
TURNS_SINCE(-> knot_one) CHOICE_COUNT() LIST_COUNT(colours)
Tabs:	after	tabs
	* A choice after a tab
		- A gather after tabs
	=== tab_knot ===
Unicode spaces: nbsp em　ideographic﻿bom end
 * choice after nbsp
 -> divert_after_em_space
 === knot_after_em_space ===
// comment with a line separator   inside
// comment with a next line  inside
text with   paragraph separator
Ünïcödé wörds: çok güzel, İstanbul, ışık, ĞÜŞÖÇ
Emoji 😀 in text # tag with 😀 emoji
* Choice with emoji 😀 [inside 😀] after
-> ünicode_target
=== ünicode_knot ===
>>> CAMERA: Wide shot
  >>> indented instruction
Text before >>> an instruction
@ at-instruction
$ dollar instruction

   
Line with CRLF ending
Another CRLF line
Line with a lone CRxxxxxxxxxx \[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[\[
The end.
