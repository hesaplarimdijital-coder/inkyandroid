INCLUDE sub/part.ink
VAR x = 1
TODO: write the intro
Hello {x}
-> knot_a
== knot_a ==
{unknown_var}
* choice -> nowhere
* other
  -> END
== knot_c
{ x:
- 1: one
}
== function twice(n)
~ return n * 2
== knot_d
~ twice(1, 2)
-> END
