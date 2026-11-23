VAR x = 7
VAR y = 2
VAR label = "ink"
VAR flag = true
CONST LIMIT = 10
VAR knot_ref = -> target
Sum {x + y}, difference {x - y}, product {x * y}, quotient {x / y}, remainder {x % y}, mod word {x mod y}.
Negation {-x}, grouping {(x + y) * 2}, precedence {x + y * 2}.
Compare {x > y} {x < y} {x >= 7} {x <= 6} {x == 7} {x != 7}.
Logic {flag and x > y} {flag && false} {false or flag} {not flag} {!flag}.
Strings {label + "y"} {"a" + "b" == "ab"} {label == "ink"} {label != "ink"} {label ? "nk"} {"ink" !? "z"}.
Constant {LIMIT}, {x < LIMIT}.
~ x++
~ y--
~ x += 10
~ y -= 1
After changes {x} and {y}.
~ temp local = x * LIMIT
Temp {local}.
{ x > 10 and y == 0:
    Both hold.
- else:
    Not both.
}
{ x:
- 18: eighteen
- else: something else
}
Int from float {INT(7.9)}, min {MIN(x, y)}, max {MAX(x, y)}.
Booleans print as {true} and {false}; numbers as {1 == 1} and {1 == 2}.
~ label = "{label} and {x}"
Built string: {label}.
-> knot_ref
== target ==
Reached through a variable divert target. Visits: {target}.
-> END
